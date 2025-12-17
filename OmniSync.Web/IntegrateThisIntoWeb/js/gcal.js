/* =========================================
   GOOGLE CALENDAR ENGINE
   ========================================= */
const GCal = {
    // Public ICS URL
    URL: "https://calendar.google.com/calendar/ical/jjonjex%40gmail.com/public/basic.ics",
    PROXY: "https://corsproxy.io/?",

    init() {
        this.sync();
        // Auto-refresh every 15 minutes
        setInterval(() => this.sync(), 15 * 60 * 1000);
    },

    async sync() {
        const status = document.getElementById('gcal-status');
        status.innerText = "SYNCING...";
        status.style.color = "#666";

        try {
            const response = await fetch(this.PROXY + encodeURIComponent(this.URL));
            if (!response.ok) throw new Error("Net Error");
            
            const text = await response.text();
            const events = this.parse(text);
            const todayEvents = this.filterToday(events);

            // Separate All-Day vs Timed
            const timed = todayEvents.filter(e => !e.allDay);
            const allDay = todayEvents.filter(e => e.allDay);

            // Pass to App Logic
            App.receiveCalendarData(timed, allDay);
            
            const time = new Date().toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
            status.innerText = `UPDATED ${time}`;
            status.style.color = "#2d5a27"; 

        } catch (e) {
            console.error(e);
            status.innerText = "SYNC FAILED";
            status.style.color = "#8a1c1c"; 
        }
    },

    parse(data) {
        const events = [];
        const lines = data.split(/\r\n|\n|\r/);
        let inEvent = false;
        let current = {};

        lines.forEach(line => {
            if (line.startsWith("BEGIN:VEVENT")) {
                inEvent = true;
                current = {};
            } else if (line.startsWith("END:VEVENT")) {
                inEvent = false;
                if (current.dtstart) {
                    events.push({
                        start: this.parseDate(current.dtstart),
                        summary: current.summary || "No Title",
                        allDay: current.isAllDay || false
                    });
                }
            } else if (inEvent) {
                if (line.startsWith("DTSTART")) {
                    current.dtstart = line.split(':')[1];
                    if (line.includes("VALUE=DATE")) current.isAllDay = true;
                }
                else if (line.startsWith("SUMMARY")) current.summary = line.substring(8);
            }
        });
        return events;
    },

    parseDate(icalStr) {
        if (icalStr.length === 8) {
            const y = icalStr.substr(0,4), m = icalStr.substr(4,2), d = icalStr.substr(6,2);
            return new Date(`${y}-${m}-${d}T00:00:00`);
        }
        const clean = icalStr.replace('Z', ''); 
        const y = clean.substr(0,4), m = clean.substr(4,2), d = clean.substr(6,2);
        const h = clean.substr(9,2), min = clean.substr(11,2), s = clean.substr(13,2);
        return new Date(`${y}-${m}-${d}T${h}:${min}:${s}`);
    },

    filterToday(events) {
        const now = new Date();
        const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate());
        const endOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59);

        return events.filter(e => {
            // Check overlaps for today
            return e.start >= startOfDay && e.start <= endOfDay;
        }).sort((a,b) => a.start - b.start);
    }
};