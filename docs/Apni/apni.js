(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const clone = data => JSON.parse(JSON.stringify(data));
  const initial = [
    {name:"Daily",icon:"spark",items:[["T3 Code","app","code"],["Fomni","app","folder"],["Obsidian","app","diamond"],["Browser","app","globe"],["Start day","setup","layers"],["Focus","script","terminal"]]},
    {name:"Projects",icon:"folder",items:[["Wartribes","setup","shield"],["Unreal Editor","app","cube"],["VS Code","app","code"],["Review tasks","app","list"],["Build game","script","terminal"],["Git sync","script","branch"]]},
    {name:"Create",icon:"pen",items:[["Blender","app","cube"],["Asset review","app","eye"],["Capture","script","camera"],["Screenshots","app","image"],["References","app","diamond"],["Export","script","share"]]},
    {name:"Automate",icon:"terminal",items:[["Backups","script","layers"],["Check sync","script","branch"],["Reset layout","setup","grid"],["PowerShell","app","terminal"],["Deploy","script","share"],["Monitor","script","eye"]]},
    {name:"Setups",icon:"layers",items:[["Coding desk","setup","code"],["Game dev","setup","cube"],["Writing","setup","pen"],["Present","setup","grid"],["Clear desk","setup","spark"],["Night mode","setup","moon"]]},
    {name:"System",icon:"gear",items:[["Sound","action","music"],["Lock PC","action","lock"],["Displays","action","grid"],["Timer","app","clock"],["Restart shell","script","terminal"],["Clean temp","script","spark"]]}
  ];
  const windows = [
    {name:"T3 Code",icon:"code",items:[["Wartribes agent","window","code"],["Omni worker","window","code"],["TaskBoard review","window","list"]]},
    {name:"Chrome",icon:"globe",items:[["Gola reference","window","globe"],["OmniHub Gateway","window","globe"],["Unreal docs","window","globe"],["ChatGPT","window","spark"]]},
    {name:"Fomni",icon:"folder",items:[["Shared projects","window","folder"],["Screenshots","window","image"]]},
    {name:"Unreal",icon:"cube",items:[["Wartribes Editor","window","cube"],["Combat Lab","window","shield"]]},
    {name:"Obsidian",icon:"diamond",items:[["Omni Tasks","window","list"],["Wartribes design","window","pen"],["Personal notes","window","diamond"]]},
    {name:"VS Code",icon:"code",items:[["Omni workspace","window","code"],["Wartribes C++","window","code"]]}
  ];
  const S = {mode:"launcher",state:"explore",data:clone(initial),active:0,child:-1,pending:null,editing:null,pressed:false,hoverGap:null,navLocked:false};
  const radial = ApniNavigation.create({active:S.active});
  let settleTimer = null;
  const pointerContext = () => ({categories:datum().length,actions:items().length,
    windowsMode:S.mode==="windows",addMode:S.state==="add",
    emptyWheel:S.state==="empty-wheel",emptyCategory:S.state==="empty-category"});
  function cancelSettle() { clearTimeout(settleTimer); settleTimer=null; }
  function resetNavigation() { cancelSettle(); radial.reset(S.active); S.hoverGap=null; S.navLocked=false; }
  function applyNavigation(next) {
    const gap = next.gap && next.gap.which+":"+next.gap.at;
    const oldGap = S.hoverGap && S.hoverGap.which+":"+S.hoverGap.at;
    const categoryChanged = S.active!==next.category;
    const changed = categoryChanged || S.child!==next.action || oldGap!==gap || S.navLocked!==next.transitLocked;
    if(categoryChanged && S.state==="edit"){S.editing={which:"cat",index:next.category};S.pending=null;}
    S.active=next.category; S.child=next.action; S.hoverGap=next.gap; S.navLocked=next.transitLocked;
    if (changed) render();
    cancelSettle();
    if (next.dueAt!==null) settleTimer=setTimeout(() => applyNavigation(radial.settle(performance.now())),Math.max(0,next.dueAt-performance.now()));
  }
  function pointerCoordinates(e) {
    const svg=$("wheel");
    const matrix=svg.getScreenCTM();
    if(!matrix)return null;
    const p=svg.createSVGPoint();p.x=e.clientX;p.y=e.clientY;
    return p.matrixTransform(matrix.inverse());
  }
  function trackPointer(e) {
    const p=pointerCoordinates(e);if(!p)return;
    applyNavigation(radial.move(p.x,p.y,performance.now(),pointerContext()));
  }
  const polar=(r,deg)=>[380+Math.cos(deg*Math.PI/180)*r,380+Math.sin(deg*Math.PI/180)*r];
  const safe=v=>String(v).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  const fmt=n=>n.toFixed(2);
  function arc(ri,ro,a,b){
    const [x1,y1]=polar(ro,a),[x2,y2]=polar(ro,b),[x3,y3]=polar(ri,b),[x4,y4]=polar(ri,a);
    return "M"+fmt(x1)+" "+fmt(y1)+" A"+ro+" "+ro+" 0 "+(b-a>180?1:0)+" 1 "+fmt(x2)+" "+fmt(y2)+" L"+fmt(x3)+" "+fmt(y3)+" A"+ri+" "+ri+" 0 "+(b-a>180?1:0)+" 0 "+fmt(x4)+" "+fmt(y4)+" Z";
  }
  function arcLine(r,a,b){const [x1,y1]=polar(r,a),[x2,y2]=polar(r,b);return "M"+fmt(x1)+" "+fmt(y1)+" A"+r+" "+r+" 0 "+(b-a>180?1:0)+" 1 "+fmt(x2)+" "+fmt(y2)}
  const shapes={
    spark:'<path d="M12 2.5 14.6 9.4 21.5 12l-6.9 2.6L12 21.5l-2.6-6.9L2.5 12l6.9-2.6Z"/>',
    folder:'<path d="M3 6a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v11H3Z"/><path d="M3 9h18"/>',
    terminal:'<rect x="2.5" y="4" width="19" height="16" rx="3"/><path d="m6.5 9 3 3-3 3M12 16h5"/>',
    pen:'<path d="m4 20 4-1 12-12a2 2 0 0 0-3-3L5 16Z"/><path d="m15 6 3 3M4 20l4-1"/>',
    layers:'<path d="m12 3 9 5-9 5-9-5Z"/><path d="m3 12 9 5 9-5M3 16l9 5 9-5"/>',
    gear:'<circle cx="12" cy="12" r="3.3"/><path d="M10 2h4l.7 2.6 2.2 1.1 2.4-1.1 2.3 3.4-1.7 2.1.1 2.4 1.6 2.1-2.3 3.4-2.5-1-2.1 1L14 21h-4l-.7-2.7-2.2-1.1-2.4 1.1-2.3-3.4 1.7-2.1-.1-2.4-1.6-2.1 2.3-3.4 2.5 1 2.1-1Z"/>',
    code:'<path d="m8.5 5-6 7 6 7M15.5 5l6 7-6 7M13.8 3l-3.6 18"/>',
    globe:'<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c-5 4-5 14 0 18M12 3c5 4 5 14 0 18"/>',
    diamond:'<path d="m12 2 9 10-9 10L3 12Z"/><path d="M3 12h18M12 2l3 10-3 10-3-10Z"/>',
    cube:'<path d="m12 2 9 5v10l-9 5-9-5V7Z"/><path d="m3 7 9 5 9-5M12 12v10"/>',
    list:'<path d="M9 6h12M9 12h12M9 18h12M3 6h2M3 12h2M3 18h2"/>',
    shield:'<path d="m12 2 9 4v6c0 5-4 8-9 10-5-2-9-5-9-10V6Z"/><path d="m8 12 3 3 5-6"/>',
    branch:'<circle cx="6" cy="5" r="2"/><circle cx="18" cy="19" r="2"/><circle cx="6" cy="19" r="2"/><path d="M6 7v10m0-7c0 5 12 0 12 7"/>',
    eye:'<path d="M2 12s4-6 10-6 10 6 10 6-4 6-10 6S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
    camera:'<path d="M3 7h4l2-3h6l2 3h4v12H3Z"/><circle cx="12" cy="13" r="4"/>',
    image:'<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m3 18 5-5 4 4 3-3 6 5"/>',
    share:'<path d="M12 16V3m-5 5 5-5 5 5"/><path d="M5 15v5h14v-5"/>',
    grid:'<rect x="3" y="3" width="8" height="8" rx="1"/><rect x="13" y="3" width="8" height="8" rx="1"/><rect x="3" y="13" width="8" height="8" rx="1"/><rect x="13" y="13" width="8" height="8" rx="1"/>',
    moon:'<path d="M20 15.5A9 9 0 0 1 8.5 4 9 9 0 1 0 20 15.5Z"/>',
    music:'<path d="M9 18V5l11-2v13M9 5l11-2"/><ellipse cx="6" cy="18" rx="3" ry="2"/><ellipse cx="17" cy="16" rx="3" ry="2"/>',
    lock:'<rect x="4" y="10" width="16" height="12" rx="2"/><path d="M7 10V7a5 5 0 0 1 10 0v3"/>',
    clock:'<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 3"/>',
    plus:'<path d="M12 4v16M4 12h16"/>'
  };
  const glyph=(name,x,y,scale=1)=>'<g transform="translate('+fmt(x)+' '+fmt(y)+') scale('+scale+')" class="glyph"><g transform="translate(-12 -12)">'+(shapes[name]||shapes.spark)+'</g></g>';
  const datum=()=>S.mode==="windows"?windows:S.data;
  const cat=()=>datum()[S.active];
  const items=()=>S.state==="empty-category" && S.mode==="launcher" ? [] : (cat()?.items||[]);
  const specs='<defs><radialGradient id="body" cx="31%" cy="20%" r="88%"><stop stop-color="#314d71" stop-opacity=".98"/><stop offset=".46" stop-color="#172c47" stop-opacity=".99"/><stop offset="1" stop-color="#071322"/></radialGradient><linearGradient id="rim" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#e0f4ff" stop-opacity=".45"/><stop offset=".45" stop-color="#a4d0ff" stop-opacity=".9"/><stop offset=".72" stop-color="#daeaff" stop-opacity=".16"/><stop offset="1" stop-color="#b6d5ff" stop-opacity=".6"/></linearGradient><radialGradient id="activeSheen"><stop stop-color="#97c5ff" stop-opacity=".03"/><stop offset="1" stop-color="#bedaff" stop-opacity=".18"/></radialGradient><linearGradient id="centerBg" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#1b3856"/><stop offset=".5" stop-color="#0e2339"/><stop offset="1" stop-color="#071729"/></linearGradient><linearGradient id="satBg" x1="0" x2="1" y1="0" y2="1"><stop stop-color="#355776"/><stop offset="1" stop-color="#142840"/></linearGradient><filter id="rimGlow" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur in="SourceGraphic" stdDeviation="1.1" result="blur"/><feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge></filter><filter id="dotGlow"><feGaussianBlur in="SourceGraphic" stdDeviation="1" result="g"/><feMerge><feMergeNode in="g"/><feMergeNode in="SourceGraphic"/></feMerge></filter><filter id="centerShadow" x="-100%" y="-100%" width="300%" height="300%"><feDropShadow dx="0" dy="8" stdDeviation="10" flood-color="#000" flood-opacity=".8"/></filter></defs>';

  function groupMarkup(g,i,total){
    const angle=-90+i*360/total,part=360/total, [ix,iy]=polar(163,angle),[tx,ty]=polar(212,angle);
    return '<g class="category '+(S.active===i?"active":"")+'" data-cat="'+i+'" role="button" tabindex="0" aria-label="'+safe(g.name)+'"><path class="cat-hit" d="'+arc(101,242,angle-part/2+1,angle+part/2-1)+'"/><path class="cat-active-fill" d="'+arc(105,241,angle-part/2+2,angle+part/2-2)+'"/><circle class="category-icon-disc" cx="'+fmt(ix)+'" cy="'+fmt(iy)+'" r="29"/>'+glyph(g.icon,ix,iy,1.22)+'<text class="cat-label" x="'+fmt(tx)+'" y="'+fmt(ty+4)+'">'+safe(g.name)+'</text>'+(S.mode==="windows" ? '<text class="windows-count" x="'+fmt(tx)+'" y="'+fmt(ty+18)+'">'+g.items.length+' OPEN</text>':'')+'</g>';
  }
  function satelliteMarkup(item,i,total){
    const angle=-90+i*360/total;const [x,y]=polar(302,angle),[tx,ty]=polar(354,angle);
    const selected=i===S.child?"selected":"";
    const type=item[1]==="script"?"SCRIPT":item[1]==="setup"?"SETUP":item[1]==="window"?"WINDOW":item[1]==="action"?"ACTION":"APP";
    return '<g class="satellite '+selected+'" style="--n:'+i+'" data-child="'+i+'" role="button" tabindex="0" aria-label="'+safe(item[0])+' '+type+'"><circle cx="'+fmt(x)+'" cy="'+fmt(y)+'" r="31" class="sat-disc"/>'+glyph(item[2],x,y-4,1.07)+'<circle class="type-dot" cx="'+fmt(x+20)+'" cy="'+fmt(y-22)+'" r="3"/><text class="type-mark" x="'+fmt(x)+'" y="'+fmt(y+23)+'">'+type+'</text><text class="satellite-label" x="'+fmt(tx)+'" y="'+fmt(ty+4)+'">'+safe(item[0].length>16?item[0].slice(0,15)+"…":item[0])+'</text></g>';
  }
  function addPlus(which,at,angle,r,label){
    const [x,y]=polar(r,angle);
    const selected=S.hoverGap?.which===which && S.hoverGap?.at===at?" selected":"";
    return '<g class="insert'+selected+'" tabindex="0" role="button" aria-label="Insert '+label+'" data-add="'+which+'" data-at="'+at+'"><circle cx="'+fmt(x)+'" cy="'+fmt(y)+'" r="14"/><text x="'+fmt(x)+'" y="'+fmt(y+5)+'">+</text></g>';
  }
  function render(){
    const data=datum(),isEmpty=S.state==="empty-wheel",group=cat(),outer=items(),count=data.length;
    let svg=specs;
    svg+='<circle cx="380" cy="380" r="262" fill="#010b18" opacity=".3"/><circle class="surface" cx="380" cy="380" r="246"/><circle class="body-rim" cx="380" cy="380" r="248"/><circle class="light-orbit" cx="380" cy="380" r="253"/><circle class="light-orbit" cx="380" cy="380" r="238"/><circle class="orbit-dash" cx="380" cy="380" r="302"/>';
    if(!isEmpty && count){
      for(let i=0;i<count;i++){
        const a=-90+(i-.5)*360/count;
        const [a1,b1]=polar(109,a),[a2,b2]=polar(236,a);
        svg+='<line class="etched-line" x1="'+fmt(a1)+'" y1="'+fmt(b1)+'" x2="'+fmt(a2)+'" y2="'+fmt(b2)+'"/>';
      }
      data.forEach((d,i)=>svg+=groupMarkup(d,i,count));
      const a=-90+(S.active-.5)*360/count+3,b=-90+(S.active+.5)*360/count-3;
      svg+='<path class="active-trace" d="'+arcLine(244,a,b)+'"/>';
      if(outer.length && S.mode!=="windows"){
        outer.forEach((item,i)=>{const [x,y]=polar(243,-90+i*360/outer.length),[tx,ty]=polar(270,-90+i*360/outer.length);svg+='<line class="spoke" x1="'+fmt(x)+'" y1="'+fmt(y)+'" x2="'+fmt(tx)+'" y2="'+fmt(ty)+'"/>';});
        outer.forEach((item,i)=>svg+=satelliteMarkup(item,i,outer.length));
      }else if(S.mode!=="windows"){
        svg+='<text x="380" y="47" class="empty-caption">This group is empty</text>';
        svg+=addPlus("child",0,-90,303,"first action");
      }
      if(S.state==="add"){
        for(let i=0;i<count;i++)svg+=addPlus("cat",i+1,-90+(i+.5)*360/count,242,"group here");
        for(let i=0;i<outer.length;i++)svg+=addPlus("child",i+1,-90+(i+.5)*360/outer.length,302,"action here");
      }
    }else{
      svg+='<circle cx="380" cy="380" r="192" fill="none" stroke="#c7dfff2c" stroke-width="1.2" stroke-dasharray="4 9"/>';
      for(let i=0;i<8;i++)svg+=addPlus("cat",i,-90+i*45,196,"first group");
      svg+='<text x="380" y="118" class="empty-caption">Add your first group</text>';
    }
    svg+='<circle cx="380" cy="380" r="98" fill="#091728" stroke="#b3d4ff1f" stroke-width="8"/><circle cx="380" cy="380" r="87" class="center-metal"/><circle cx="380" cy="380" r="78" class="center-line"/><path d="'+arcLine(88,-85,5)+'" stroke="#d3eaff" stroke-width="3" fill="none" stroke-linecap="round"/>';
    svg+='<text class="core-word" x="380" y="364">apni</text><circle class="core-dot" cx="407" cy="350" r="3"/><text class="core-sub" x="380" y="387">'+(S.mode==="windows"?"OPEN WINDOWS":"LAUNCHER")+'</text><text class="core-mode" x="380" y="411">'+safe(isEmpty?"Ready to begin":group?.name||"")+'</text><text class="core-help" x="380" y="433">'+(S.pressed?"release to select":"center to cancel")+'</text>';
    $("wheel").innerHTML=svg;
    $("wheel").classList.toggle("crossing",S.navLocked);
    updateCopy();
    renderFan();
    renderEditor();
  }
  function updateCopy(){
    const isEmpty=S.state==="empty-wheel", group=cat(), item=items()[S.child];
    const windowsMode=S.mode==="windows";
    $("intro-title").innerHTML=windowsMode?"Every open window.<br><em>Immediately.</em>":"Everything you need.<br><em>One movement.</em>";
    $("intro-desc").textContent=windowsMode?"Your open apps are grouped by icon. Hover to fan out their windows, then choose the exact instance. No intermediate menu.":"Apps, scripts, and saved Setups live together. Hover a group, aim at an action, and release to launch.";
    $("selection-icon").textContent=windowsMode?"▣":"◈";
    $("selection-type").textContent=isEmpty?"Empty launcher":item?item[1]==="window"?"Window instance":item[1].toUpperCase()+" action":windowsMode?"Open application":"Selected group";
    $("selection-name").textContent=isEmpty?"No groups yet":item?item[0]:group?.name||"";
    $("selection-desc").textContent=isEmpty?"Choose a plus to create your first group":item?"Ready to "+(windowsMode?"focus window":"run action")+" (preview)":windowsMode?String(group?.items.length||0)+" windows — choose an instance":"Choose an action in the outer orbit";
    $("wheel-foot").textContent=S.navLocked?"Cross through to keep "+(group?.name||"this category")+" · pause on a group to switch":S.hoverGap?"Insertion gap · "+(S.hoverGap.which==="cat"?"new group":"action")+" at position "+S.hoverGap.at:S.state==="add"?"Choose a plus to insert exactly where you want":S.state==="edit"?"Select a group or action to edit it":S.state.startsWith("empty")?"Create a group or add an action":windowsMode?"Choose a window from the fan on the right":"Hover a category to reveal its actions";
    $("hint").textContent=S.state==="explore"?(windowsMode?"Click any preview card to simulate window focus":"Hover icons and click an action to preview launch"):S.state==="edit"?"Click a group or action to edit its details":S.state==="add"?"Plus markers are insertion points, not replacement buttons":"Choose a plus to add your first item";
    $("tab-launcher").classList.toggle("active",!windowsMode);$("tab-launcher").setAttribute("aria-pressed",String(!windowsMode));
    $("tab-windows").classList.toggle("active",windowsMode);$("tab-windows").setAttribute("aria-pressed",String(windowsMode));
    document.querySelectorAll("[data-state]").forEach(b=>b.classList.toggle("current",b.dataset.state===S.state));
  }
  function renderFan(){
    const fan=$("window-fan");
    if(S.mode!=="windows" || S.state!=="explore" || !cat()){fan.hidden=true;fan.innerHTML="";return;}
    const group=cat(),shown=group.items.slice(0,4);
    fan.hidden=false;
    fan.innerHTML='<div class="fan-topline"><b>'+safe(group.name)+'</b><span>'+group.items.length+' windows</span></div><div class="fan-cards">'+shown.map((w,i)=>{
      const theme=["violet","teal","gold",""][i%4];
      return '<button type="button" class="fan-card" data-win="'+i+'" style="--n:'+i+'" aria-label="Focus '+safe(w[0])+'"><div class="thumb '+theme+'"><div class="thumb-side"><i></i><i></i><i></i><i></i></div><div class="thumb-body"><i></i><i></i><i></i><i></i></div></div><div class="fan-label"><span class="mini-dot"></span>'+safe(w[0])+'<small>Focus ↗</small></div></button>';
    }).join("")+'</div>';
  }
  let toastTimer;
  function notify(msg){$("toast").textContent=msg;$("toast").classList.add("visible");clearTimeout(toastTimer);toastTimer=setTimeout(()=>$("toast").classList.remove("visible"),2600);}
  function renderEditor(){
    const el=$("floating-editor"),p=S.pending;
    if(!p && S.state!=="edit"){el.hidden=true;el.innerHTML="";return;}
    const data=S.data;
    const isCat=p? p.which==="cat":(S.editing?.which==="cat");
    const index=p?p.at:(S.editing?.index ?? 0);
    const current=!p?(isCat?data[index]:data[S.active]?.items[index]):null;
    const name=p?"":(isCat?current?.name:current?.[0])||"";
    const type=p?"App":(isCat?"Category":current?.[1]||"App");
    el.hidden=false;
    el.innerHTML='<div class="editor-top"><span>APNI / '+(p?"NEW ITEM":"EDIT ITEM")+'</span><button type="button" class="editor-close" data-editor="close" aria-label="Close editor">×</button></div>'+
      '<h2>'+(p?"Add "+(isCat?"a group":"an action"):"Edit "+(isCat?"group":"action"))+'</h2>'+
      '<label>Name<input id="field-name" maxlength="30" value="'+safe(name)+'" placeholder="'+(isCat?"New group":"Action name")+'"></label>'+
      '<label>Type<select id="field-type"><option value="app">App</option><option value="script">Script</option><option value="setup">Setup</option><option value="action">Omni action</option>'+(isCat?'<option value="category">Category</option>':"")+'</select></label>'+
      '<div class="editor-actions">'+(!p?'<button type="button" data-editor="left" title="Move earlier">←</button><button type="button" data-editor="right" title="Move later">→</button><button type="button" class="delete" data-editor="delete">Remove</button>': '<button type="button" data-editor="close">Cancel</button>')+'<button type="button" class="primary" data-editor="save">'+(p?"Add item":"Save")+'</button></div>'+
      '<p class="editor-footnote">Changes are local to this interactive design study. No system actions or settings are modified.</p>';
    $("field-type").value=isCat?"category":type.toLowerCase();
  }
  function editorAction(action){
    if(action==="close"){S.pending=null;S.editing=null;S.state="explore";render();return;}
    const p=S.pending, e=S.editing,isCat=p?p.which==="cat":e?.which==="cat",index=p?p.at:e?.index;
    const list=isCat?S.data:S.data[S.active]?.items;
    if(!list)return;
    if(action==="save"){
      const value=$("field-name").value.trim(),type=$("field-type").value;
      if(!value)return notify("Enter a name first");
      if(p){
        list.splice(Math.min(index,list.length),0,isCat?{name:value,icon:"spark",items:[]}: [value,type,type==="script"?"terminal":type==="setup"?"layers":"spark"]);
        S.active=isCat?Math.min(index,list.length-1):S.active;S.child=isCat?-1:Math.min(index,list.length-1);
      }else{
        if(isCat)list[index].name=value;
        else {list[index][0]=value;list[index][1]=type;}
      }
      notify(p?"Added "+value:"Saved "+value);
      S.pending=null;S.editing=null;S.state="explore";
    }else if(action==="delete"){
      list.splice(index,1);if(isCat){S.active=Math.max(0,Math.min(S.active,list.length-1));S.child=-1;}else S.child=-1;
      S.state=list.length?"explore":isCat?"empty-wheel":"empty-category";S.editing=null;notify("Removed from preview");
    }else if(action==="left"||action==="right"){
      const next=Math.max(0,Math.min(list.length-1,index+(action==="left"?-1:1)));
      [list[index],list[next]]=[list[next],list[index]];
      if(isCat)S.active=next;else S.child=next;
      S.editing.index=next;notify("Moved in wheel");
    }
    resetNavigation();render();
  }
  function selectCat(i){
    if(!datum()[i])return;
    cancelSettle();radial.chooseCategory(i);
    S.active=i;S.child=-1;S.hoverGap=null;S.navLocked=false;
    if(S.state==="empty-wheel")S.state="explore";
    if(S.state==="edit"){S.editing={which:"cat",index:i};S.pending=null;}
    render();
  }
  function selectChild(i){
    const item=items()[i];if(!item)return;
    S.child=i;
    if(S.state==="edit"){S.editing={which:"child",index:i};S.pending=null;render();return;}
    if(S.state==="add"){render();return;}
    render();
    notify(S.mode==="windows"?"Focus "+item[0]+" (preview only)":"Launch "+item[0]+" (preview only)");
  }
  function handleWheelTarget(t,execute){
    const add=t.closest("[data-add]"),group=t.closest("[data-cat]"),child=t.closest("[data-child]");
    if(add){if(execute){S.pending={which:add.dataset.add,at:Number(add.dataset.at)};if(S.state.startsWith("empty"))S.state="add";render();}return;}
    if(group){const i=Number(group.dataset.cat);if(S.active!==i || (execute && S.state==="edit"))selectCat(i);return;}
    if(child){const i=Number(child.dataset.child);if(S.child!==i && !execute){S.child=i;render();}else if(execute)selectChild(i);return;}
    if(execute && t.closest(".center-metal")){S.child=-1;S.pressed=false;resetNavigation();notify("Cancelled");render();}
  }
  function setMode(mode){
    S.mode=mode;S.state="explore";S.active=0;S.child=-1;S.pending=null;S.editing=null;resetNavigation();render();
  }
  function setState(value){
    if(value!=="explore" && S.mode==="windows")S.mode="launcher";
    if(value==="empty-wheel")S.data=[];
    if(value==="empty-category" && S.data[4])S.data[4].items=[];
    if(value==="explore" && !S.data.length)S.data=clone(initial);
    S.state=value;S.active=value==="empty-category"?4:0;S.child=value==="edit"?0:-1;S.pending=null;S.editing=value==="edit"?{which:"child",index:0}:null;resetNavigation();render();
  }
  $("tab-launcher").onclick=()=>setMode("launcher");$("tab-windows").onclick=()=>setMode("windows");
  document.querySelectorAll("[data-state]").forEach(b=>b.addEventListener("click",()=>setState(b.dataset.state)));
  $("reset").onclick=()=>{S.data=clone(initial);setMode("launcher");notify("Preview reset");};
  // Coordinate-space navigation is stable across SVG rerenders and ignores incidental DOM hover crossings.
  $("wheel-pool").addEventListener("pointermove",trackPointer);
  $("wheel-pool").addEventListener("pointerleave",()=>applyNavigation(radial.leave()));
  $("wheel").addEventListener("click",e=>{
    trackPointer(e);
    const direct=e.target.closest("[data-add]");
    if(!direct && S.hoverGap && (S.state==="add" || S.state.startsWith("empty"))) {
      S.pending={...S.hoverGap}; if(S.state.startsWith("empty"))S.state="add"; render();return;
    }
    handleWheelTarget(e.target,true);
  });
  $("wheel").addEventListener("keydown",e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();handleWheelTarget(e.target,true);}});
  $("window-fan").addEventListener("click",e=>{const b=e.target.closest("[data-win]");if(b)notify("Focus "+cat().items[Number(b.dataset.win)][0]+" (preview only)");});
  $("floating-editor").addEventListener("click",e=>{const b=e.target.closest("[data-editor]");if(b)editorAction(b.dataset.editor);});
  $("floating-editor").addEventListener("keydown",e=>{if(e.key==="Enter"&&e.target.tagName==="INPUT"){e.preventDefault();editorAction("save");}});
  window.addEventListener("keydown",e=>{
    if(e.key==="Escape"){S.pending=null;S.editing=null;S.pressed=false;S.state="explore";S.child=-1;resetNavigation();render();notify("Cancelled");}
    if(e.code==="Space"&&!e.repeat&&!["INPUT","SELECT","TEXTAREA","BUTTON"].includes(document.activeElement.tagName)){e.preventDefault();S.pressed=true;render();}
  });
  window.addEventListener("keyup",e=>{if(e.code==="Space"&&S.pressed){
    e.preventDefault();S.pressed=false;
    const target=radial.snapshot();
    if(S.state==="add" && target.gap){S.pending={...target.gap};render();}
    else if(S.state==="explore" && target.action>=0 && S.mode!=="windows")selectChild(target.action);
    else render();
  }});
  // Short damped spring. It responds to pointer travel and settles instead of endlessly animating.
  const pool=$("wheel-pool"),reduced=window.matchMedia("(prefers-reduced-motion: reduce)");
  let tx=0,ty=0,x=0,y=0,vx=0,vy=0,ticking=false;
  function tick(){
    vx=(vx+(tx-x)*.085)*.75;vy=(vy+(ty-y)*.085)*.75;x+=vx;y+=vy;
    $("wheel").style.setProperty("--spring-x",x.toFixed(2)+"px");$("wheel").style.setProperty("--spring-y",y.toFixed(2)+"px");
    $("wheel").style.setProperty("--spring-r",(x*.025).toFixed(3)+"deg");
    if(Math.abs(x-tx)+Math.abs(y-ty)+Math.abs(vx)+Math.abs(vy)>.04)requestAnimationFrame(tick);else ticking=false;
  }
  pool.addEventListener("pointermove",e=>{
    if(reduced.matches)return;
    const box=pool.getBoundingClientRect();tx=Math.max(-8,Math.min(8,(e.clientX-(box.left+box.width/2))*.023));ty=Math.max(-8,Math.min(8,(e.clientY-(box.top+box.height/2))*.023));
    if(!ticking){ticking=true;requestAnimationFrame(tick);}
  });
  pool.addEventListener("pointerleave",()=>{tx=ty=0;if(!ticking&&!reduced.matches){ticking=true;requestAnimationFrame(tick);}});
  // Reproducible visual-review states for screenshot capture and TaskBoard embeds.
  const query=new URLSearchParams(window.location.search);
  if(query.get("mode")==="windows")S.mode="windows";
  const scene=query.get("state");
  if(["add","edit","empty-category","empty-wheel"].includes(scene)){
    S.mode="launcher";S.state=scene;
    if(scene==="empty-wheel")S.data=[];
    if(scene==="empty-category")S.data[4].items=[];S.active=scene==="empty-category"?4:0;
    S.child=scene==="edit"?0:-1;
    S.editing=scene==="edit"?{which:"child",index:0}:null;
  }
  render();
})();