/* =========================================
   APPLICATION LOGIC
   ========================================= */
const App = {
    state: {
        wake: "07:00", condition: 100, wed: false, obsess: false, bootAdj: 0, 
        tokens: [false, false, false, false], lastDate: null, schedule: [],
        library: [{name: "Lofi Girl", url: "https://www.youtube.com/watch?v=jfKfPfyJRdk"}],
        calTimed: [], // New: Store timed events
        calAllDay: [] // New: Store all day events
    },
    isPaused: false,
    lastActiveIdx: -1,
    dragSrc: null,
    hubConnection: null,

    // --- INITIALIZATION ---
    init() {
        const saved = localStorage.getItem('cortex_v14');
        if(saved) this.state = {...this.state, ...JSON.parse(saved)};

        const today = new Date().toDateString();
        if (this.state.lastDate !== today) {
            this.state.tokens = [false, false, false, false];
            this.state.lastDate = today;
            this.state.bootAdj = 0; 
            // Clear calendar data on new day until fetch returns
            this.state.calTimed = [];
            this.state.calAllDay = [];
            this.regenerate();
        } else {
            if (!this.state.schedule || this.state.schedule.length === 0) this.regenerate();
            else this.render();
        }
        
        // Populate Inputs
        document.getElementById('wakeTime').value = this.state.wake;
        document.getElementById('rn-cond').value = this.state.condition;
        document.getElementById('cb-wed').checked = this.state.wed;
        document.getElementById('cb-obsess').checked = this.state.obsess;
        
        this.updateCondUI(); 
        this.renderLibrary();
        
        // Start GCal
        GCal.init();

        // Initialize SignalR
        this.initSignalR();
        
        setInterval(() => this.tick(), 1000);
    },

    async initSignalR() {
        this.hubConnection = new signalR.HubConnectionBuilder()
            .withUrl(HUB_URL)
            .withAutomaticReconnect()
            .build();

        try {
            await this.hubConnection.start();
            console.log("SignalR Connected to Hub");
            await this.hubConnection.invoke("Authenticate", API_KEY);
        } catch (err) {
            console.error("SignalR Connection Error: ", err);
            setTimeout(() => this.initSignalR(), 5000);
        }
    },

    notifyActivityChange(block) {
        if (this.hubConnection && this.hubConnection.state === signalR.HubConnectionState.Connected) {
            this.hubConnection.invoke("NotifyCortexActivity", block.name, block.type);
        }
    },

    persist() { localStorage.setItem('cortex_v14', JSON.stringify(this.state)); },

    hardReset() { 
        if(confirm("Reset Schedule to Default?")) { 
            this.state.bootAdj=0; 
            this.regenerate(); 
        } 
    },

    updateCondUI() {
        const c = document.getElementById('rn-cond').value;
        document.getElementById('cond-display').innerText = c + "%";
        const txt = document.getElementById('cond-text');
        if(c > 80) { txt.innerText = "STANDARD (3h DEV)"; txt.style.color = "var(--c-work)"; }
        else if(c > 50) { txt.innerText = "INTERMEDIATE (2h DEV)"; txt.style.color = "var(--c-flex)"; }
        else if(c > 20) { txt.innerText = "FOG (ZOMBIE TASKS)"; txt.style.color = "var(--c-rest)"; }
        else { txt.innerText = "MAINTENANCE (REST ONLY)"; txt.style.color = "var(--c-bed)"; }
    },

    // --- CALENDAR HANDLER ---
    receiveCalendarData(timed, allDay) {
        this.state.calTimed = timed;
        this.state.calAllDay = allDay;
        this.renderAllDayEvents();
        this.regenerate(); // Trigger full regen to merge timed events
    },

    renderAllDayEvents() {
        const list = document.getElementById('gcal-sidebar-list');
        if (!list) return; // Guard against missing element
        
        list.innerHTML = '';
        if (this.state.calAllDay.length === 0) {
            list.innerHTML = '<div style="font-size:11px; color:var(--text-dimmer); padding:8px; text-align:center;">No all-day events</div>';
            return;
        }
        this.state.calAllDay.forEach(e => {
            const div = document.createElement('div');
            div.className = 'gcal-item';
            div.innerHTML = `<span class="gcal-marker">::</span> <span>${e.summary}</span>`;
            list.appendChild(div);
        });
    },

    // --- LOGIC ENGINE ---
    regenerate() {
        this.state.wake = document.getElementById('wakeTime').value;
        this.state.condition = parseInt(document.getElementById('rn-cond').value);
        this.state.wed = document.getElementById('cb-wed').checked;
        this.state.obsess = document.getElementById('cb-obsess').checked;

        let templateName = 'standard';
        if (this.state.wed) templateName = 'chaos';
        else if (this.state.obsess) templateName = 'obsession';
        else if (this.state.condition <= 20) templateName = 'maintenance';
        else if (this.state.condition <= 50) templateName = 'fog';
        else if (this.state.condition <= 79) templateName = 'intermediate';

        let s = JSON.parse(JSON.stringify(DAY_TEMPLATES[templateName]));

        // 1. Apply Boot Adjustment
        if(this.state.bootAdj !== 0) {
            const bootIdx = s.findIndex(b => b.type === 'boot');
            if(bootIdx !== -1) {
                s[bootIdx].dur += this.state.bootAdj;
                if(s[bootIdx].dur < 0) s[bootIdx].dur = 0;
            }
        }

        // 2. Merge Calendar Events
        // We do this by calculating absolute times, inserting, and pushing.
        if (this.state.calTimed.length > 0) {
            s = this.mergeCalendarEvents(s);
        }

        this.state.schedule = s; 
        this.state.tokens = [false, false, false, false]; 
        this.render(); this.persist();
    },

    mergeCalendarEvents(baseSchedule) {
        // Convert base schedule to absolute blocks
        let cursor = this.timeToMins(this.state.wake);
        let blocks = baseSchedule.map(b => {
            const start = cursor;
            const end = cursor + b.dur;
            cursor += b.dur;
            return { ...b, start, end };
        });

        this.state.calTimed.forEach(ev => {
            const evStart = (ev.start.getHours() * 60) + ev.start.getMinutes();
            // Default 60m duration if not specified (though Google usually provides end time, our parser simplified it)
            // Let's assume 60m for safety or parse end time in GCal (future improvement). 
            // For now, let's treat single-point events as 30m blocks for schedule clarity.
            const evDur = 30; 
            const evEnd = evStart + evDur;

            // Find overlapping block
            const idx = blocks.findIndex(b => evStart >= b.start && evStart < b.end);
            
            if (idx !== -1) {
                const target = blocks[idx];
                const preDur = evStart - target.start;
                const postDur = target.end - evStart; // Remainder of original block

                // Create new sequence replacing target
                const replacements = [];
                
                // 1. Part of block before event
                if (preDur > 0) {
                    replacements.push({ ...target, dur: preDur, start: target.start, end: evStart });
                }

                // 2. The Event
                replacements.push({ name: `CAL: ${ev.summary}`, dur: evDur, type: 'calendar', start: evStart, end: evEnd });

                // 3. Remainder of block (pushed)
                // Note: In "Insert and Push" logic, the remainder stays the same duration but moves later?
                // Or does it get cut?
                // User requirement: "Chaos blocks should cut into flex blocks".
                // But this is a fixed event. Let's Insert and Push everything down.
                
                if (postDur > 0) {
                    replacements.push({ ...target, name: target.name + " (CONT)", dur: postDur, start: evEnd, end: evEnd + postDur });
                }

                // Replace the single block with the new array
                blocks.splice(idx, 1, ...replacements);

                // Re-calculate start times for all subsequent blocks to push them down
                let flowCursor = replacements[replacements.length-1].end;
                for (let i = idx + replacements.length; i < blocks.length; i++) {
                    blocks[i].start = flowCursor;
                    blocks[i].end = flowCursor + blocks[i].dur;
                    flowCursor += blocks[i].dur;
                }
            }
        });

        // Convert back to simple dur-based objects
        return blocks.map(b => ({
            name: b.name,
            dur: b.dur,
            type: b.type === 'calendar' ? 'calendar' : b.type
        }));
    },


    // --- RENDERING ---
    render() {
        const list = document.getElementById('list-container');
        const track = document.getElementById('vis-track');
        list.innerHTML = '';
        track.innerHTML = '<div class="now-marker" id="now-line"></div>';

        this.state.tokens.forEach((u, i) => {
            const el = document.getElementById('t'+i);
            if(u) el.classList.add('used'); else el.classList.remove('used');
        });

        let cursor = this.timeToMins(this.state.wake);
        
        this.state.schedule.forEach((block, idx) => {
            block.start = cursor; block.end = cursor + block.dur; cursor += block.dur;

            // List Item
            const li = document.createElement('div');
            li.className = `schedule-item ${block.type} future`;
            li.style.borderLeftColor = `var(--c-${block.type})`;
            if(block.type === 'calendar') li.style.borderLeftColor = 'var(--c-cal)'; // Cyan override
            
            li.draggable = true; li.dataset.idx = idx;
            li.innerHTML = `
                <div class="handle">::</div><div class="si-time">${this.minsToTime(block.start)}</div>
                <div class="si-name" style="${block.type==='calendar' ? 'color:var(--c-cal)' : `color:var(--c-${block.type})`}">${block.name}</div>
                <div class="si-dur">${block.dur}m</div>`;
            
            li.addEventListener('dragstart', (e) => { this.dragSrc = idx; li.classList.add('dragging'); });
            li.addEventListener('dragover', (e) => e.preventDefault());
            li.addEventListener('drop', (e) => this.handleDrop(e, idx));
            
            list.appendChild(li);

            // Timeline Block
            const tb = document.createElement('div');
            tb.className = 'time-block';
            tb.style.flex = block.dur; 
            tb.style.background = `var(--c-${block.type})`;
            if (block.type === 'calendar') tb.style.background = 'var(--c-cal)';
            
            tb.innerText = block.name.split(' ')[0];
            
            if(block.type === 'boot') { tb.style.background='transparent'; tb.style.color='#444'; tb.style.border='1px dashed #333'; }
            else if(block.type === 'sleep') { tb.style.background='var(--c-sleep)'; tb.style.color='#444'; }
            else tb.style.color = '#000';
            
            track.appendChild(tb);
        });
    },

    // --- ACTIONS ---
    handleDrop(e, dropIdx) {
        e.stopPropagation();
        const dragIdx = this.dragSrc;
        if(dragIdx !== dropIdx) {
            const item = this.state.schedule.splice(dragIdx, 1)[0];
            this.state.schedule.splice(dropIdx, 0, item);
            this.render(); this.persist();
        }
        document.querySelectorAll('.schedule-item').forEach(el => el.classList.remove('dragging'));
    },

    useToken(idx) {
        if (this.state.tokens[idx]) return;
        const now = new Date(); const nowMins = (now.getHours() * 60) + now.getMinutes();
        const activeIdx = this.state.schedule.findIndex(b => nowMins >= b.start && nowMins < b.end);

        if (activeIdx !== -1) {
            const activeBlock = this.state.schedule[activeIdx];
            const chaosDur = 30;

            if (activeBlock.type === 'chaos') {
                activeBlock.dur += chaosDur; 
            } else {
                const elapsed = nowMins - activeBlock.start;
                const remaining = activeBlock.dur - elapsed;
                activeBlock.dur = elapsed;
                const chaos = {name: "CHAOS INJECTION", dur: chaosDur, type: "chaos"};
                const cont = {...activeBlock, dur: remaining, name: activeBlock.name + " (CONT)"};
                this.state.schedule.splice(activeIdx + 1, 0, chaos, cont);
            }
            this.state.schedule = this.state.schedule.filter(b => b.dur > 0);
            this.state.tokens[idx] = true; this.persist(); this.render();
        } else alert("Cannot inject chaos outside schedule.");
    },

    togglePause() { 
        this.isPaused = !this.isPaused; 
        document.getElementById('btn-pause').innerText = this.isPaused ? "RESUME" : "PAUSE"; 
    },

    adjBoot(m) { this.state.bootAdj += m; this.regenerate(); },

    // --- TICKER ---
    tick() {
        const now = new Date(); const nowMins = (now.getHours() * 60) + now.getMinutes(); const sec = now.getSeconds();
        document.getElementById('clock').innerText = this.minsToTime(nowMins);
        const idx = this.state.schedule.findIndex(b => nowMins >= b.start && nowMins < b.end);

        if (idx !== -1 && idx !== this.lastActiveIdx) {
            CortexAudio.playTone(this.state.schedule[idx].type);
            this.notifyActivityChange(this.state.schedule[idx]);
            this.lastActiveIdx = idx;
        }

        const listItems = document.querySelectorAll('.schedule-item');
        listItems.forEach((el, i) => {
            el.classList.remove('past', 'active', 'future');
            if (i < idx) el.classList.add('past');
            else if (i === idx) el.classList.add('active');
            else el.classList.add('future');
        });

        if (idx !== -1) {
            const marker = document.getElementById('now-line');
            const blocks = document.querySelectorAll('.time-block');
            if(blocks[idx]) {
                marker.style.display = 'block'; blocks[idx].appendChild(marker);
                const b = this.state.schedule[idx];
                marker.style.left = ((nowMins - b.start) / b.dur * 100) + "%";

                if (!this.isPaused) {
                    const diff = (b.end*60) - (nowMins*60 + sec);
                    const h = Math.floor(diff/3600); const m = Math.floor((diff%3600)/60); const s = diff%60;
                    document.getElementById('countdown').innerText = `${h}:${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}`;
                    document.getElementById('active-name').innerText = b.name;
                    document.getElementById('active-type').innerText = b.type.toUpperCase();
                    
                    let colorVar = `var(--c-${b.type})`;
                    if (b.type === 'calendar') colorVar = 'var(--c-cal)';
                    
                    const focusPanel = document.getElementById('focus-panel');
                    document.getElementById('active-type').style.color = colorVar;
                    focusPanel.style.borderLeftColor = colorVar;
                    focusPanel.style.borderLeftWidth = '4px';
                } else document.getElementById('countdown').innerText = "PAUSED";
            }
        } else document.getElementById('countdown').innerText = "--:--";
    },

    // --- UTILS ---
    timeToMins(s) { const [h,m] = s.split(':').map(Number); return h*60+m; },
    minsToTime(m) { let h = Math.floor(m/60); let mn = m%60; if(h>=24) h-=24; return `${h.toString().padStart(2,'0')}:${mn.toString().padStart(2,'0')}`; },
    
    addVideo() {
        let url = document.getElementById('new-vid-url').value;
        if(url) { 
            if(!url.includes('http')) url = "https://www.youtube.com/watch?v="+url; 
            this.state.library.push({name: "Video "+(this.state.library.length+1), url}); 
            this.persist(); this.renderLibrary(); 
        }
    },
    
    renderLibrary() {
        const l = document.getElementById('library-list'); l.innerHTML = '';
        this.state.library.forEach((v,i) => {
            const div = document.createElement('div'); div.className = 'audio-item';
            div.innerHTML = `<span onclick="window.open('${v.url}')">${v.name}</span><span class="del-btn" id="del-${i}">x</span>`;
            l.appendChild(div);
            document.getElementById(`del-${i}`).onclick = (e) => { 
                e.stopPropagation(); this.state.library.splice(i,1); this.persist(); this.renderLibrary(); 
            };
        });
    }
};

App.init();