// Standalone Node regression tests for browser-permission and DOM safety.
// No real tabs, credentials, extension installation or browser automation.
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

const data = {};
let permit = false;
const requested = [];
const allowedPatterns = new Set();
const listeners = {};
const tab = { id: 14, url: "https://example.test/?access_token=hidden", title: "Example" };
const tabActions = [];
const chrome = {
  storage: { local: {
    async get(key) { return { [key]: data[key] }; },
    async set(o) { Object.assign(data, o); },
    async setAccessLevel() {}
  }},
  permissions: {
    async request({ origins }) {
      requested.push(origins);
      if (permit) origins.forEach(x => allowedPatterns.add(x));
      return permit;
    },
    async contains({ origins }) { return origins.every(x => allowedPatterns.has(x)); },
    async remove({ origins }) { origins.forEach(x => allowedPatterns.delete(x)); return true; }
  },
  tabs: {
    async query() { return [tab]; },
    async get(id) { assert.equal(id, tab.id); return tab; },
    async update(id, changes) { tabActions.push(["update",id,changes]); return tab; },
    async reload(id) { tabActions.push(["reload",id]); },
    async remove(id) { tabActions.push(["remove",id]); }
  },
  scripting: { async executeScript({func,args,target}) { assert.equal(target.tabId,tab.id); return [{result:func(...args)}]; } },
  alarms: { onAlarm: { addListener(fn) { listeners.alarm = fn; } }, create() {} },
  runtime: { onMessage: { addListener(fn) { listeners.message = fn; } } }
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
  querySelectorAll() { return [link, secretInput, otpInput, safeInput, dropdown]; },
  createTreeWalker() {
    let index = -1;
    return { nextNode() { index++; this.currentNode = textNodes[index]; return index < textNodes.length; } };
  }
};
const window = {innerWidth:1100,innerHeight:700,scrollX:0,scrollY:80,
  scrollBy(settings){this.lastScroll = settings;}};
const context = vm.createContext({
  chrome, URL, setTimeout, clearTimeout, globalThis: null, window,
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
assert.equal(api.validOrigin("http://example.test/"), null);
assert.equal(api.validOrigin("chrome://extensions"), null);
assert.equal(api.validOrigin("file:///C:/secrets.txt"), null);
assert.equal(api.validOrigin("https://x.test/#access_token=abc"), null);
assert.equal(api.displayUrl("https://example.test/a?access_token=SECRET#code=SECRET"), "https://example.test/a");
const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, "manifest.json"), "utf8"));
assert.ok(!manifest.host_permissions.includes("<all_urls>"), "Global site access must be optional");
assert.ok(manifest.optional_host_permissions.includes("https://*/*"));

function message(command, args = {}) {
  return new Promise((resolve) => {
    const staysOpen = listeners.message({ command, ...args }, null, resolve);
    assert.equal(staysOpen, true);
  });
}
(async () => {
  let reply = await message("status");
  assert.equal(reply.data.enabled, false);
  assert.equal(reply.data.paired, false);
  reply = await message("allowOrigin", { origin: "https://example.test" });
  assert.equal(reply.ok, false, "Missing Chrome permission must prevent approval");
  assert.equal((await message("status")).data.origins.length, 0);
  permit = true;
  allowedPatterns.add("https://example.test/*"); // Popup host-grant user gesture.
  reply = await message("allowOrigin", { origin: "https://example.test" });
  assert.equal(reply.ok, true);
  assert.equal(reply.data.origins[0], "https://example.test");
  assert.ok(allowedPatterns.has("https://example.test/*"));
  assert.throws(() => api.inspectOrAct("type", "https://example.test", "#password", "x"), /Sensitive/);
  assert.throws(() => api.inspectOrAct("type", "https://example.test", "#otp", "x"), /Sensitive/);
  assert.throws(() => api.inspectOrAct("click", "https://evil.test", "#go", ""), /outside approved origin/);
  const result = api.inspectOrAct("type", "https://example.test", "#search", "safe search");
  assert.equal(result.typed, true);
  assert.equal(result.length, 11);
  assert.equal(safeInput.value, "safe search");
  assert.equal(result.text, undefined, "No typed text may be returned");
  link.attrs.href = "https://another-site.test/";
  assert.throws(() => api.inspectOrAct("click", "https://example.test", "#go", ""), /Cross-origin/);
  link.attrs.href = "https://example.test/next";
  assert.equal(api.inspectOrAct("click", "https://example.test", "#go", "").clicked, true);
  const focused = api.inspectOrAct("focus", "https://example.test", "#search", "");
  assert.equal(focused.focused, true);
  assert.throws(() => api.inspectOrAct("focus", "https://example.test", "#password", ""), /Sensitive/);
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
  assert.ok(snapshot.elements.every(x => x.selector && x.tag));
  data.omniBrowserControl = {enabled:true, token:"x".repeat(48), origins:["https://example.test"]};
  const work = (action, arguments_={}) => ({action, deadline:Date.now()+10000,
    arguments:{tabId:14, ...arguments_}});
  assert.equal((await api.execute(work("page_state"))).viewport.width, 1100);
  assert.equal((await api.execute(work("find",{text:"Visible heading"}))).count, 1);
  assert.equal((await api.execute(work("wait_for",{selector:"#absent",timeoutMs:0}))).timedOut, true);
  assert.equal((await api.execute(work("select",{selector:"#choice",optionText:"First"}))).selected, true);
  assert.equal((await api.execute(work("scroll",{deltaY:145}))).scrolled, true);
  assert.equal((await api.execute(work("focus",{selector:"#search"}))).focused, true);
  assert.equal((await api.execute(work("reload"))).reloading, true);
  assert.equal((await api.execute(work("activate"))).activated, true);
  assert.equal((await api.execute(work("close"))).closed, true);
  assert.equal(tabActions.filter(x=>x[0]==="remove").length, 1);
  tab.url = "https://unapproved.test/";
  await assert.rejects(api.execute(work("close")), /not approved/);
  tab.url = "https://example.test/?access_token=hidden";
  allowedPatterns.delete("https://example.test/*");
  await assert.rejects(api.execute(work("reload")), /not approved/);
  allowedPatterns.add("https://example.test/*");
  data.omniBrowserControl.enabled = false;
  await assert.rejects(api.execute(work("activate")), /disabled/);
  reply = await message("revoke", { origin: "https://example.test" });
  assert.equal(reply.ok, true);
  assert.equal((await message("status")).data.origins.length, 0);
  assert.equal(allowedPatterns.size, 0);
  console.log("PASS: permission gating, tab controls, scroll/focus/find/wait/select/page state, safe DOM and URL redaction");
})().catch(err => { console.error(err); process.exitCode = 1; });
