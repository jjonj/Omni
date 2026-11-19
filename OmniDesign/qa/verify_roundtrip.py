"""MISC-0170 focused, isolated full-stack OmniDesign acceptance walkthrough.

Runs the real FastAPI/SQLite service, shipped Vite editor, Python agent CLI and a
Chromium mobile WebView-shaped native transport adapter. Does NOT claim a handset.
All server data and evidence stay in task-scoped E: scratch; never touch live DB.
"""
import json, os, pathlib, subprocess, sys, tempfile, time, urllib.error, urllib.request, uuid, traceback
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRATCH = pathlib.Path(os.environ.get("OMNIDESIGN_QA_DIR",
                                       str(pathlib.Path(tempfile.gettempdir()) / "OmniDesign-MISC-0170")))
TEMP = SCRATCH / "Temporary"
EVID = SCRATCH / "Evidence"
for folder in (TEMP,EVID): folder.mkdir(parents=True,exist_ok=True)
DATA = TEMP / ("roundtrip_db_"+uuid.uuid4().hex)
DATA.mkdir(parents=True,exist_ok=True)
PORT = 8898
BASE = f"http://127.0.0.1:{PORT}"
CLI = ROOT/"OmniDesign/service/agent_cli.py"
SERVICE = ROOT/"OmniDesign/service"
ENV = {**os.environ, "OMNIDESIGN_DATA_DIR":str(DATA)}
server = None
log = {"run":DATA.name,"checks":[],"device":"390x844 Chromium mobile WebView-shaped bridge; no physical handset"}
def check(name, data=None):
    log["checks"].append({"step":name,"passed":True,"details":data})
    print("PASS",name,data or "",flush=True)
def emit():
    (EVID/"roundtrip-result.json").write_text(json.dumps(log,indent=2,ensure_ascii=False),encoding="utf-8")
def launch():
    return subprocess.Popen([sys.executable,"-B","-m","uvicorn","server:app","--host","127.0.0.1",
                             "--port",str(PORT),"--no-access-log"],
                            cwd=str(SERVICE),env=ENV,creationflags=subprocess.CREATE_NO_WINDOW,
                            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
def req(method,path,payload=None,headers=None):
    body=json.dumps(payload).encode("utf-8") if payload is not None else None
    h={"Accept":"application/json",**(headers or {})}
    if body:h["Content-Type"]="application/json"
    with urllib.request.urlopen(urllib.request.Request(BASE+path,data=body,headers=h,method=method),timeout=10) as response:
        return json.load(response)
def wait(f,seconds=25):
    until=time.time()+seconds;last=None
    while time.time()<until:
        try:
            value=f()
            if value:return value
        except Exception as e:last=e
        time.sleep(.3)
    raise AssertionError("Timeout: "+repr(last))
def cli(*args,allow_fail=False):
    cmd=[sys.executable,"-B",str(CLI),"--base-url",BASE,"--no-launch",*map(str,args)]
    p=subprocess.run(cmd,cwd=str(ROOT),env=ENV,capture_output=True,text=True,timeout=45)
    if not allow_fail and p.returncode:raise AssertionError("CLI: "+repr(cmd)+"\n"+p.stderr+"\n"+p.stdout)
    return p
def put_json(name,payload):
    target=TEMP/name
    target.write_text(json.dumps(payload,indent=2,ensure_ascii=False),encoding="utf-8")
    return target
def store(page,table):
    return page.evaluate("""async table=>await new Promise((yes,no)=>{
      const r=indexedDB.open('OmniDesign',3);
      r.onsuccess=()=>{let q=r.result.transaction(table).objectStore(table).getAll();
        q.onsuccess=()=>yes(q.result);q.onerror=()=>no(q.error);};
      r.onerror=()=>no(r.error);
    })""",table)
def online(page,flag):
    page.evaluate("""isOnline=>{
      window.__offline=!isOnline; localStorage.setItem('mockOffline',String(!isOnline));
      window.dispatchEvent(new Event('online'));
    }""",flag)
bridge = r"""(()=>{
 window.__offline=localStorage.getItem('mockOffline')==='true';
 window.__dropId=null;
 window.OmniDesignNative={flushed:()=>{},request:(id,method,route,body,revision,key)=>{
  if(window.__offline){
   window.omniDesignNativeReply?.(id,JSON.stringify({ok:false,status:0,body:'',error:'Network offline (Android bridge fixture)'}));return;
  }
  const headers={'Content-Type':'application/json'};
  if(revision)headers['If-Match']=revision;
  if(key)headers['Idempotency-Key']=key;
  fetch('/'+route,{method,headers,body:body||undefined}).then(async response=>{
   const content=await response.text();
   if(window.__dropId && method!=='GET' && route.endsWith('diagrams')
      && JSON.parse(body).id===window.__dropId){
      window.__dropId=null;
      window.__offline=true;localStorage.setItem('mockOffline','true');
      window.omniDesignNativeReply?.(id,JSON.stringify({ok:false,status:0,body:'',error:'Dropped response after PC commit'}));
      return;
   }
   window.omniDesignNativeReply?.(id,JSON.stringify({ok:response.ok,status:response.status,body:content,etag:response.headers.get('ETag')||''}));
  }).catch(err=>window.omniDesignNativeReply?.(id,JSON.stringify({ok:false,status:0,body:'',error:String(err)})));
 }};
})();"""

try:
    server=launch()
    wait(lambda: req("GET","/api/health").get("service")=="omnidesign",10)
    check("Isolated real SQLite/FastAPI service launched",str(DATA))
    spec={"family":"architecture","title":"MISC-0170 Wartribes survival authority",
          "description":"Agent -> desktop -> mobile offline -> agent, native editable proof.",
          "nodes":[
              {"key":"player","stencil":"client","title":"Player: foraging","x":90,"y":160,"width":215},
              {"key":"hub","stencil":"service","title":"Server: survival","x":405,"y":160,"width":215},
              {"key":"memory","stencil":"database","title":"NPC memory","x":735,"y":160,"width":215}],
          "links":[{"from":"player","to":"hub","label":"request"},{"from":"hub","to":"memory","label":"witnessed"}]}
    spec_file=put_json("architecture-spec.json",spec)
    draft=TEMP/"architecture.omd.json"
    result=cli("draft","--spec",spec_file,"--output",draft)
    doc=json.loads(draft.read_text(encoding="utf-8"))
    docid=doc["id"]
    assert doc["revision"]==0 and len([e for e in doc["elements"] if e["type"]=="edge"])==2
    result=cli("create","--document",draft)
    created=json.loads(result.stdout)
    link=created["link"]
    assert created["id"]==docid and created["revision"]==1
    check("Agent native create + permalink + connected editable graph",{"id":docid,"link":link,"elements":len(doc["elements"])})
    # Two other modeling families through the exact editor pack catalog, same API.
    extras=[]
    for family,template in [("game-system","survival-loop"),("erd","data-model")]:
        f=TEMP/(family+".omd.json")
        cli("draft","--family",family,"--template",template,"--output",f)
        ack=json.loads(cli("create","--document",f).stdout)
        extra=req("GET","/api/v1/diagrams/"+ack["id"])
        assert extra["family"]==family and extra["revision"]==1 and len(extra["elements"])>=3
        extras.append({"family":family,"id":ack["id"],"elements":len(extra["elements"])})
    check("Three diagram families backed by identical native storage",extras)

    with sync_playwright() as p:
        browser=p.chromium.launch(channel="chrome",headless=True)
        pc=browser.new_page(viewport={"width":1440,"height":900},accept_downloads=True)
        pc.goto(BASE+"/?diagram="+docid,wait_until="domcontentloaded")
        wait(lambda:pc.get_by_label("Diagram title").input_value()==spec["title"])
        pc.locator(".canvas-surface [data-element]").first.wait_for()
        assert pc.locator(".canvas-surface [data-element]").count()==len(doc["elements"])
        pc.screenshot(path=str(EVID/"pc-agent-open.png"),full_page=True)
        check("PC editor opens agent-created link and renders all native objects",len(doc["elements"]))

        ctx=browser.new_context(viewport={"width":390,"height":844},device_scale_factor=1,
                               is_mobile=True,has_touch=True,accept_downloads=True)
        ctx.add_init_script(bridge)
        phone=ctx.new_page()
        phone.goto(BASE+"/?diagram="+docid,wait_until="domcontentloaded")
        wait(lambda:phone.get_by_label("Diagram title").input_value()==spec["title"])
        wait(lambda:any(d["id"]==docid for d in store(phone,"diagrams")))
        wait(lambda:any(d["id"]==docid for d in store(phone,"sync")))
        online(phone,False)
        wait(lambda:"offline" in phone.locator(".sync-strip").inner_text().lower(),12)
        # Touch UI: outline selection opens a bottom inspector, editing a real node.
        phone.screenshot(path=str(EVID/"phone-before-edit.png"),full_page=True)
        phone.get_by_title("More diagram actions").click()
        phone.get_by_role("button",name="Canvas / Outline",exact=True).click()
        phone.locator(".outline-item").first.click()
        text_area=phone.locator(".rightpane .inspector-body textarea").first
        text_area.wait_for()
        text_area.fill("Player: cold-weather foraging (phone offline)")
        text_area.blur()
        wait(lambda:any(d["id"]==docid and "cold-weather" in json.dumps(d["elements"])
                        for d in store(phone,"diagrams")))
        edited=next(d for d in store(phone,"diagrams") if d["id"]==docid)
        assert edited["id"]==docid and edited["revision"]==1 and len(edited["elements"])==len(doc["elements"])
        assert "Pending" in phone.locator(".sync-strip").inner_text()
        phone.screenshot(path=str(EVID/"phone-offline-inspector.png"),full_page=True)
        check("Android-shaped touch outline/inspector edits native shape offline",{"stableId":edited["id"],"nodes":3,"edges":2})
        # Test automatic reconnect, no Sync-now click.
        online(phone,True)
        wait(lambda:req("GET","/api/v1/diagrams/"+docid)["revision"]==2,30)
        wait(lambda:next((b for b in store(phone,"sync") if b["id"]==docid),{}).get("revision")==2,30)
        pc.reload(wait_until="domcontentloaded")
        wait(lambda:pc.get_by_label("Diagram title").input_value()==spec["title"])
        assert "cold-weather" in pc.locator(".canvas-surface").inner_text()
        phone.screenshot(path=str(EVID/"phone-after-sync.png"),full_page=True)
        check("Offline Android -> automatic reconnect -> PC revision 2",docid)

        # Read the SAME permanent link through actual Python agent API, patch one
        # native node atomically with CAS and preserve both original connectors.
        existing=TEMP/"agent-reopen.omd.json"
        cli("read",link,"--output",existing)
        base=json.loads(existing.read_text(encoding="utf-8"))
        assert base["id"]==docid and base["revision"]==2
        target=next(e for e in base["elements"] if e["type"]=="node" and "Server" in e["label"]["text"])
        replacement=json.loads(json.dumps(target))
        replacement["label"]["text"]="Server: survival + rescue (agent)"
        if "content" in replacement:replacement["content"]["title"]=replacement["label"]["text"]
        operations=put_json("agent-operations.json",{"operations":[{"op":"replace","element":replacement}]})
        updated=json.loads(cli("patch",link,"--revision",2,"--operations",operations).stdout)
        assert updated["revision"]==3 and len(updated["elements"])==len(doc["elements"])
        assert sum(e["type"]=="edge" for e in updated["elements"])==2
        check("Agent reads exact link and CAS-patches native node, edges intact",{"revision":3,"node":target["id"]})
        online(phone,True)
        wait(lambda:next((b for b in store(phone,"sync") if b["id"]==docid),{}).get("revision")==3,30)
        wait(lambda:any(d["id"]==docid and "rescue (agent)" in json.dumps(d["elements"])
                        for d in store(phone,"diagrams")),20)
        pc.reload(wait_until="domcontentloaded")
        wait(lambda:"rescue (agent)" in pc.locator(".canvas-surface").inner_text())
        phone.screenshot(path=str(EVID/"phone-final-native.png"),full_page=True)
        pc.screenshot(path=str(EVID/"pc-final-native.png"),full_page=True)
        check("Same linked agent revision visible PC + Android, native editability retained")

        # Conflict: offline handset changes node while independent agent advances PC.
        online(phone,False)
        phone.get_by_title("More diagram actions").click()
        phone.get_by_role("button",name="Canvas / Outline",exact=True).click()
        phone.locator(".outline-item").nth(1).click()
        area=phone.locator(".rightpane .inspector-body textarea").first
        area.fill("Phone: emergency aid offline")
        area.blur()
        wait(lambda:any(d["id"]==docid and "emergency aid offline" in json.dumps(d["elements"])
                        for d in store(phone,"diagrams")))
        last=req("GET","/api/v1/diagrams/"+docid)
        node=next(e for e in last["elements"] if e["type"]=="node")
        change=json.loads(json.dumps(node));change["label"]["text"]="Concurrent PC agent"
        if "content" in change:change["content"]["title"]=change["label"]["text"]
        concurrent=put_json("agent-concurrent.json",{"operations":[{"op":"replace","element":change}]})
        cli("patch",link,"--revision",3,"--operations",concurrent)
        stale=cli("patch",link,"--revision",3,"--operations",operations,allow_fail=True)
        assert stale.returncode!=0 and "412" in stale.stderr
        online(phone,True)
        wait(lambda:any(c["id"]==docid for c in store(phone,"conflicts")),30)
        assert "Conflict" in phone.locator(".sync-strip").inner_text()
        phone.screenshot(path=str(EVID/"phone-conflict.png"),full_page=True)
        phone.reload(wait_until="domcontentloaded")
        wait(lambda:any(c["id"]==docid for c in store(phone,"conflicts")))
        phone.get_by_role("button",name="Conflict · Keep both").click()
        wait(lambda:len([d for d in store(phone,"diagrams") if d.get("metadata",{}).get("forkedFrom",{} )
                   and isinstance(d["metadata"]["forkedFrom"],dict) and d["metadata"]["forkedFrom"].get("id")==docid])>0)
        fork=next(d for d in store(phone,"diagrams") if isinstance(d.get("metadata",{}).get("forkedFrom"),dict)
             and d["metadata"]["forkedFrom"].get("id")==docid)
        assert "emergency aid offline" in json.dumps(fork["elements"])
        assert req("GET","/api/v1/diagrams/"+docid)["revision"]==4
        check("Conflict, stale 412, reload and Keep Both preserve offline shape",{"original":docid,"fork":fork["id"]})

        # Lost POST receipt: API accepts a create, Android outbox retries identical ID.
        online(phone,False)
        phone.get_by_title("More diagram actions").click()
        phone.get_by_role("button",name="New blank",exact=True).click()
        phone.get_by_label("Diagram title").fill("MISC-0170 drop-response case")
        wait(lambda:any(d["title"]=="MISC-0170 drop-response case" for d in store(phone,"diagrams")))
        retry=next(d for d in store(phone,"diagrams") if d["title"]=="MISC-0170 drop-response case")
        phone.evaluate("id=>window.__dropId=id",retry["id"])
        online(phone,True)
        wait(lambda:any(x["id"]==retry["id"] for x in req("GET","/api/v1/diagrams")["items"]),30)
        wait(lambda:any(x["id"]==retry["id"] for x in store(phone,"outbox")),20)
        phone.reload(wait_until="domcontentloaded")
        online(phone,True)
        try:
            wait(lambda:next((b for b in store(phone,"sync") if b["id"]==retry["id"]),{}).get("revision")==1,30)
        except Exception:
            print("RETRY-DEBUG",json.dumps({"id":retry["id"],"local":[d for d in store(phone,"diagrams") if d["id"]==retry["id"]], "outbox":[d for d in store(phone,"outbox") if d["id"]==retry["id"]], "baseline":[d for d in store(phone,"sync") if d["id"]==retry["id"]], "conflicts":[d for d in store(phone,"conflicts") if d["id"]==retry["id"]], "server":req("GET","/api/v1/diagrams/"+retry["id"]), "ui":phone.locator(".sync-strip").inner_text()},ensure_ascii=True)[:6000],flush=True)
            phone.screenshot(path=str(EVID/"phone-retry-stuck.png"),full_page=True)
            raise
        assert req("GET","/api/v1/diagrams/"+retry["id"])["revision"]==1
        assert len([x for x in req("GET","/api/v1/diagrams")["items"] if x["id"]==retry["id"]])==1
        check("Dropped POST response then WebView restart/retry: one stable diagram revision 1",retry["id"])

        # Restart the actual Python process, not the PC. Same disk-backed DB survives.
        server.terminate();server.wait(timeout=6)
        offline_cli=cli("read",link,allow_fail=True)
        assert offline_cli.returncode!=0 and ("unreachable" in offline_cli.stderr.lower())
        server=launch()
        wait(lambda:req("GET","/api/health").get("status")=="ok",10)
        after=json.loads(cli("read",link).stdout)
        assert after["id"]==docid and after["revision"]==4
        assert any(e["label"]["text"]=="Concurrent PC agent" for e in after["elements"] if e["type"]=="node")
        pc.reload(wait_until="domcontentloaded")
        wait(lambda:pc.get_by_label("Diagram title").input_value()==spec["title"],15)
        check("Backend process restart + stored native revisions/link survive; outage refuses fake agent read",after["revision"])

        # Actual editor export actions (not merely server JSON).
        pc.locator(".export-menu").hover()
        downloads={}
        for label,ext in [("Native .omd.json",".json"),("Export SVG",".svg"),("Export PNG",".png")]:
            pc.locator(".export-menu").hover()
            with pc.expect_download(timeout=12000) as info:
                pc.get_by_role("button",name=label,exact=True).click()
            download=info.value
            path=EVID/("export"+ext)
            download.save_as(path)
            assert path.stat().st_size>200
            downloads[ext]=path.stat().st_size
        native=json.loads((EVID/"export.json").read_text(encoding="utf-8"))
        assert native["id"]==docid and native["revision"]==4 and len(native["elements"])==5
        assert "<svg" in (EVID/"export.svg").read_text(encoding="utf-8")
        assert (EVID/"export.png").read_bytes()[:8]==b"\x89PNG\r\n\x1a\n"
        check("PC editor exports native JSON + SVG + PNG, native graph unflattened",downloads)
        browser.close()
    log["result"]="PASS"
except Exception as error:
    log["result"]="FAIL"
    log["failure"]=str(error)
    log["traceback"]=traceback.format_exc()
    print(log["traceback"].encode("ascii","backslashreplace").decode(),flush=True)
finally:
    if server is not None and server.poll() is None:
        server.terminate()
        try:server.wait(timeout=5)
        except subprocess.TimeoutExpired:server.kill()
    emit()
    print(json.dumps({"result":log["result"],"checks":len(log["checks"]),"evidence":str(EVID)},indent=2),flush=True)
if log["result"]!="PASS":sys.exit(1)
