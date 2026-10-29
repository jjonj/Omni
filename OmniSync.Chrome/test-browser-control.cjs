// Standalone Node regression tests for browser-permission and DOM safety.
// No real tabs, credentials, extension installation or browser automation.
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

const data = {};
let permit = true;
const listeners = {};
const tab = { id: 14, windowId: 7, url: "https://example.test/?access_token=hidden", title: "Example" };
const tabActions = [];
const browserWindow = { id: 7, focused: false, state: "minimized" };
const windowActions = [];
let injectionMode = "normal";
let injectionCalls = 0;
let installType = "development";
let reloaded = 0;
let fakeNow = Date.now();
const chrome = {
  storage: { local: {
    async get(key) { return { [key]: data[key] }; },
    async set(o) { Object.assign(data, o); },
    async setAccessLevel() {}
  }},
  permissions: {
    async contains({ origins }) {
      return permit && origins.every(x => x.startsWith("https://") ||
        x.startsWith("http://127.0.0.1/") || x.startsWith("http://localhost/") || x.startsWith("http://192.168.1.1/"));
    }
  },
  tabs: {
    async query() { return [tab]; },
    async get(id) { assert.equal(id, tab.id); return tab; },
    async update(id, changes) { tabActions.push(["update",id,changes]); return tab; },
    async reload(id) { tabActions.push(["reload",id]); },
    async remove(id) { tabActions.push(["remove",id]); }
  },
  windows: {
    async get(id) { assert.equal(id, browserWindow.id); return {...browserWindow}; },
    async update(id, change) {
      assert.equal(id, browserWindow.id);
      windowActions.push([id, {...change}]);
      Object.assign(browserWindow, change);
      return {...browserWindow};
    }
  },
  scripting: { async executeScript({func,args,target}) {
    assert.equal(target.tabId, tab.id);
    injectionCalls++;
    if (injectionMode === "empty") return [];
    if (injectionMode === "navigated") throw Error("No frame with id 0 in tab 14");
    if (injectionMode === "restricted") throw Error("Cannot access contents of URL: https://example.test/?access_token=DO_NOT_RETURN");
    if (injectionMode === "unexpected") throw Error("Internal injection failure: SECRET-NEVER-RETURN");
    return [{result:func(...args)}];
  } },
  alarms: { onAlarm: { addListener(fn) { listeners.alarm = fn; } }, create() {}, async get() { return null; } },
  management: { async getSelf() { return { installType }; } },
  runtime: { id:"test-extension-id", reload() { reloaded++; }, onMessage: { addListener(fn) { listeners.message = fn; } } }
};
class FakeElement {
  constructor(tag, attrs = {}, text = "") {
    this.tagName = tag;
    this.attrs = attrs;
    this.innerText = text;
    this.parentElement = null;
  }
  getAttribute(k) { return this.attrs[k] ?? null; }
  getClientRects() { return [1]; }
  closest(q) {
    if (q === "a[href]" && this.tagName === "A" && this.attrs.href) return this;
    if (q.includes("form")) return null;
    if (q.includes("input") && this.tagName === "INPUT") return this;
    return null;
  }
  matches() { return false; }
  click() { this.clicked = true; }
  focus() { this.focused = true; }
  dispatchEvent(e) { this.lastEvent = e.type; }
  scrollBy(settings) { this.lastScroll = settings; }
}
class FakeInput extends FakeElement {
  constructor(attrs, value) {
    super("INPUT", attrs);
    this._value = value;
    this.type = attrs.type || "text";
  }
  get value() { return this._value; }
  set value(v) { this._value = v; }
}
class FakeArea extends FakeElement {
  get value() { return this._value; }
  set value(v) { this._value = v; }
}
class FakeSelect extends FakeElement {
  constructor(attrs) { super("SELECT", attrs); this.options = [
    { textContent:"First", selected: true, disabled:false },
    { textContent:"Second", selected: false, disabled:false }
  ]; }
}
const body = new FakeElement("BODY");
const secretInput = new FakeInput({ type: "password", id: "auth_pass" }, "NEVER-DISCLOSE");
const otpInput = new FakeInput({ type: "text", autocomplete: "one-time-code" }, "123456");
const safeInput = new FakeInput({ type: "search", id: "search" }, "");
const link = new FakeElement("A", {}, "Go");
const dropdown = new FakeSelect({ id: "choice" });
for (const el of [secretInput, otpInput, safeInput, link, dropdown]) el.parentElement = body;
body.children = [secretInput, otpInput, safeInput, link, dropdown];
const textParent = new FakeElement("DIV");
textParent.parentElement = body;
body.children.push(textParent);
const formParent = new FakeElement("DIV");
formParent.parentElement = body;
body.children.push(formParent);
formParent.closest = () => ({});
const textNodes = [
  { textContent: "Visible heading", parentElement: textParent },
  { textContent: "SECRET-OTP-1234", parentElement: formParent }
];
// Deeply nested router controls must not get shortened into invalid body paths.
const deepInput = new FakeInput({id:"deep-ssid"}, "");
let deepRoot = deepInput;
for (let i = 0; i < 12; i++) {
  const parent = new FakeElement("DIV");
  parent.children = [deepRoot];
  deepRoot.parentElement = parent;
  deepRoot = parent;
}
deepRoot.parentElement = body;
body.children.push(deepRoot);
const document = {
  body, title: "Test page", readyState:"complete", documentElement:{scrollHeight:2000},
  querySelector(s) {
    if (s === "#password") return secretInput;
    if (s === "#otp") return otpInput;
    if (s === "#search") return safeInput;
    if (s === "#go") return link;
    if (s === "#choice") return dropdown;
    return null;
  },
  querySelectorAll() { return [link, secretInput, otpInput, safeInput, dropdown, deepInput]; },
  createTreeWalker() {
    let index = -1;
    return { nextNode() { index++; this.currentNode = textNodes[index]; return index < textNodes.length; } };
  }
};
const window = {innerWidth:1100,innerHeight:700,scrollX:0,scrollY:80,
  scrollBy(settings){this.lastScroll = settings;}};
const context = vm.createContext({
  chrome, URL, Date: class extends Date { static now() { return fakeNow; } },
  setTimeout, clearTimeout, globalThis: null, window,
  location: { origin: "https://example.test", href: "https://example.test/" }, document,
  getComputedStyle: () => ({ visibility: "visible", display: "block" }),
  NodeFilter: { SHOW_TEXT: 4 }, Element: FakeElement,
  HTMLInputElement: FakeInput, HTMLTextAreaElement: FakeArea, HTMLSelectElement:FakeSelect,
  Event: class { constructor(type) { this.type = type; } },
  fetch: async () => { throw Error("Test must never contact network"); }
});
context.globalThis = context;
const source = fs.readFileSync(path.join(__dirname, "browser-control.js"), "utf8");
vm.runInContext(source, context, { filename: "browser-control.js" });
const api = context.BrowserControl;
assert.equal(api.validOrigin("https://example.test/path"), "https://example.test");
assert.equal(api.validOrigin("http://example.test/"), "http://example.test");
assert.equal(api.validOrigin("http://192.168.1.1/login?secret=redacted"), "http://192.168.1.1");
assert.equal(api.validOrigin("http://192.168.1.2/"), "http://192.168.1.2");
assert.equal(api.validOrigin("http://192.168.1.1/#access_token=secret"), "http://192.168.1.1");
assert.equal(api.validOrigin("chrome://extensions"), null);
assert.equal(api.validOrigin("file:///C:/secrets.txt"), null);
assert.equal(api.validOrigin("https://x.test/#access_token=abc"), "https://x.test");
assert.equal(api.displayUrl("https://example.test/a?access_token=SECRET#code=SECRET"), "https://example.test/a");
const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, "manifest.json"), "utf8"));
assert.ok(manifest.host_permissions.includes("https://*/*"), "HTTPS sites should work without per-origin prompts");
assert.ok(manifest.host_permissions.includes("http://*/*"), "Permit HTTP on all hosts");
assert.ok(!manifest.host_permissions.includes("http://192.168.1.1/*"), "No site-specific router rule");

function message(command, args = {}) {
  return new Promise((resolve) => {
    const staysOpen = listeners.message({ command, ...args }, {id:chrome.runtime.id}, resolve);
    assert.equal(staysOpen, true);
  });
}
(async () => {
  let reply = await message("status");
  assert.equal(reply.data.enabled, true);
  assert.equal(reply.data.mode, "local");
  assert.equal(reply.data.paired, undefined);
  assert.equal(reply.data.origins, undefined);
  data.omniBrowserControl = {enabled:false, token:"old-pairing", origins:[]};
  reply = await message("status");
  assert.equal(reply.data.enabled, true, "Old pairing settings migrate to local mode.");
  assert.equal(api.inspectOrAct("type", "https://example.test", "#password", "x").typed, true);
  assert.equal(api.inspectOrAct("type", "https://example.test", "#otp", "x").typed, true);
  assert.equal(secretInput.value, "x");
  assert.equal(otpInput.value, "x");
  assert.throws(() => api.inspectOrAct("click", "https://evil.test", "#go", ""), /outside approved origin/);
  const result = api.inspectOrAct("type", "https://example.test", "#search", "safe search");
  assert.equal(result.typed, true);
  assert.equal(result.length, 11);
  assert.equal(safeInput.value, "safe search");
  assert.equal(result.text, undefined, "No typed text may be returned");
  link.attrs.href = "https://another-site.test/";
  assert.equal(api.inspectOrAct("click", "https://example.test", "#go", "").clicked, true);
  link.attrs.href = "https://example.test/next";
  assert.equal(api.inspectOrAct("click", "https://example.test", "#go", "").clicked, true);
  link.attrs.href = "http://192.168.1.1/";
  assert.equal(api.inspectOrAct("click", "https://example.test", "#go", "").clicked, true);
  link.attrs.href = "http://192.168.1.2/";
  assert.equal(api.inspectOrAct("click", "https://example.test", "#go", "").clicked, true);
  const focused = api.inspectOrAct("focus", "https://example.test", "#search", "");
  assert.equal(focused.focused, true);
  assert.equal(api.inspectOrAct("focus", "https://example.test", "#password", "").focused, true);
  assert.equal(api.inspectOrAct("wait_for", "https://example.test", "#go", "").found, true);
  assert.equal(api.inspectOrAct("wait_for", "https://example.test", "#absent", "").found, false);
  assert.equal(api.inspectOrAct("scroll", "https://example.test", "", "", {deltaY:560}).scrolled, true);
  assert.equal(window.lastScroll.top, 560);
  assert.equal(api.inspectOrAct("scroll", "https://example.test", "#search", "", {deltaY:-120}).scrolled, true);
  assert.equal(safeInput.lastScroll.top, -120);
  assert.throws(() => api.inspectOrAct("scroll", "https://example.test", "", "", {deltaY:9000}), /Invalid/);
  const page = api.inspectOrAct("page_state", "https://example.test", "", "");
  assert.equal(page.scroll.y, 80);
  assert.equal(page.viewport.height, 700);
  const found = api.inspectOrAct("find", "https://example.test", "", "", {text:"Visible heading"});
  assert.equal(found.count, 1);
  assert.equal(api.inspectOrAct("find", "https://example.test", "", "", {text:"SECRET-OTP-1234"}).count, 0);
  const selected = api.inspectOrAct("select", "https://example.test", "#choice", "", {optionText:"Second"});
  assert.equal(selected.selected, true);
  assert.equal(dropdown.options[1].selected, true);
  const snapshot = api.inspectOrAct("inspect", "https://example.test");
  assert.match(snapshot.text, /Visible heading/);
  assert.doesNotMatch(JSON.stringify(snapshot), /NEVER-DISCLOSE|SECRET-OTP-1234|123456/);
  assert.ok(snapshot.elements.some(el => el.selector), "Form controls should be discoverable");
  const ssidPath = snapshot.elements.find(el => el.selector.includes("div:nth-of-type(1) > div:nth-of-type(1) > div:nth-of-type(1)") && el.tag === "input");
  assert.ok(ssidPath?.selector.split(" > ").length >= 14, "Deep controls need complete body-anchored selectors");
  assert.ok(snapshot.elements.every(x => x.selector && x.tag));
  data.omniBrowserControl = {mode:"local",enabled:true};
  tab.url = "http://192.168.1.1/?session=REDACT_ME";
  assert.equal((await api.execute({action:"tabs",deadline:Date.now()+10000})).tabs[0].url, "http://192.168.1.1/");
  tab.url = "https://example.test/?access_token=hidden";
  const work = (action, arguments_={}) => ({action, deadline:Date.now()+10000,
    arguments:{tabId:14, ...arguments_}});
  // No result after a click can mean successful click followed by navigation.
  // Never resend a potentially mutating action automatically.
  injectionMode = "empty";
  let callsBefore = injectionCalls;
  let failure = await api.execute(work("click",{selector:"#go"}));
  assert.equal(failure.code, "FRAME_RESULT_UNAVAILABLE");
  assert.equal(failure.outcome, "unknown");
  assert.equal(failure.retrySafe, false);
  assert.equal(injectionCalls, callsBefore + 1, "Click must not retry after lost frame result");
  assert.match(failure.guidance, /Inspect tabs/);
  callsBefore = injectionCalls;
  failure = await api.execute(work("inspect"));
  assert.equal(failure.retrySafe, true);
  assert.equal(injectionCalls, callsBefore + 1);
  injectionMode = "navigated";
  callsBefore = injectionCalls;
  failure = await api.execute(work("click",{selector:"#go"}));
  assert.equal(failure.code, "FRAME_NAVIGATED");
  assert.equal(failure.retrySafe, false);
  assert.equal(injectionCalls, callsBefore + 1);
  injectionMode = "restricted";
  failure = await api.execute(work("click",{selector:"#go"}));
  assert.equal(failure.code, "FRAME_ACCESS_RESTRICTED");
  assert.equal(failure.retrySafe, false);
  assert.match(failure.guidance, /manual interaction/);
  assert.doesNotMatch(JSON.stringify(failure), /DO_NOT_RETURN|access_token=/);
  injectionMode = "unexpected";
  failure = await api.execute(work("click",{selector:"#go"}));
  assert.equal(failure.code, "FRAME_INJECTION_FAILED");
  assert.doesNotMatch(JSON.stringify(failure), /SECRET-NEVER-RETURN/);
  injectionMode = "normal";
  assert.equal(api.injectionFailure("click", Error("Sensitive form target is prohibited.")).code,
    "SENSITIVE_ACTION_RESTRICTED");
  assert.equal(api.injectionFailure("click", Error("Form submission or script-link activation must be performed manually.")).retrySafe, false);
  assert.equal((await api.execute(work("page_state"))).viewport.width, 1100);
  assert.equal((await api.execute(work("find",{text:"Visible heading"}))).count, 1);
  assert.equal((await api.execute(work("wait_for",{selector:"#absent",timeoutMs:0}))).timedOut, true);
  assert.equal((await api.execute(work("select",{selector:"#choice",optionText:"First"}))).selected, true);
  assert.equal((await api.execute(work("scroll",{deltaY:145}))).scrolled, true);
  assert.equal((await api.execute(work("focus",{selector:"#search"}))).focused, true);
  assert.equal((await api.execute(work("reload"))).reloading, true);
  assert.equal((await api.execute(work("activate"))).activated, true);
  assert.equal(windowActions.length, 0, "activate must not steal focus");
  const foreground = await api.execute(work("bring_to_front"));
  assert.equal(foreground.activated, true);
  assert.equal(foreground.focused, true);
  assert.equal(foreground.restored, true);
  assert.equal(foreground.windowId, 7);
  assert.deepEqual(windowActions, [[7, {state:"normal"}], [7, {focused:true}]]);
  browserWindow.focused = false;
  const again = await api.execute(work("bring_to_front"));
  assert.equal(again.restored, false, "Do not alter a non-minimized window's state");
  assert.equal(again.focused, true);
  assert.equal(windowActions.length, 3);
  assert.deepEqual(windowActions[2], [7, {focused:true}]);
  assert.equal((await api.execute(work("close"))).closed, true);
  assert.equal(tabActions.filter(x=>x[0]==="remove").length, 1);
  tab.url = "chrome://extensions/";
  assert.equal((await api.execute(work("bring_to_front"))).activated, true);
  assert.equal((await api.execute(work("close"))).closed, true);
  tab.url = "https://another-site.test/";
  assert.equal((await api.execute(work("reload"))).reloading, true);
  tab.url = "https://example.test/?access_token=hidden";
  permit = false;
  assert.equal((await api.execute(work("reload"))).reloading, true);
  permit = true;
  reply = await message("disable");
  assert.equal(reply.data.enabled, false);
  await assert.rejects(api.execute(work("activate")), /disabled/);
  await assert.rejects(api.execute(work("bring_to_front")), /disabled/);
  reply = await message("enable");
  assert.equal(reply.data.enabled, true);
  // Auto-refresh only unpacked development builds, never packaged extensions or recent activity.
  assert.equal(await api.maybeReloadDevelopmentExtension(), false);
  fakeNow += 3 * 60 * 1000;
  installType = "normal";
  assert.equal(await api.maybeReloadDevelopmentExtension(), false);
  installType = "development";
  assert.equal(await api.maybeReloadDevelopmentExtension(), true);
  assert.equal(reloaded, 1);
  assert.equal(data.browserControlAutoReloadCount, 1);
  await message("disable"); // Stop background poll loop before test exit.
  console.log("PASS: unrestricted HTTP and form actions, redacted inspection, safe click outcomes, idle unpacked reload");
})().catch(err => { console.error(err); process.exitCode = 1; });
