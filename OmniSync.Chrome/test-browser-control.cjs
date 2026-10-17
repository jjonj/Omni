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
    async get(id) { assert.equal(id, tab.id); return tab; }
  },
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
    if (q.includes("form")) return null;
    if (q.includes("input") && this.tagName === "INPUT") return this;
    return null;
  }
  matches() { return false; }
  click() { this.clicked = true; }
  focus() { this.focused = true; }
  dispatchEvent(e) { this.lastEvent = e.type; }
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
const body = new FakeElement("BODY");
const secretInput = new FakeInput({ type: "password", id: "auth_pass" }, "NEVER-DISCLOSE");
const otpInput = new FakeInput({ type: "text", autocomplete: "one-time-code" }, "123456");
const safeInput = new FakeInput({ type: "search", id: "search" }, "");
const link = new FakeElement("A", {}, "Go");
for (const el of [secretInput, otpInput, safeInput, link]) el.parentElement = body;
body.children = [secretInput, otpInput, safeInput, link];
const textParent = new FakeElement("DIV");
const formParent = new FakeElement("DIV");
formParent.closest = () => ({});
const textNodes = [
  { textContent: "Visible heading", parentElement: textParent },
  { textContent: "SECRET-OTP-1234", parentElement: formParent }
];
const document = {
  body, title: "Test page",
  querySelector(s) {
    if (s === "#password") return secretInput;
    if (s === "#otp") return otpInput;
    if (s === "#search") return safeInput;
    if (s === "#go") return link;
    return null;
  },
  querySelectorAll() { return [link, secretInput, otpInput, safeInput]; },
  createTreeWalker() {
    let index = -1;
    return { nextNode() { index++; this.currentNode = textNodes[index]; return index < textNodes.length; } };
  }
};
const context = vm.createContext({
  chrome, URL, setTimeout, clearTimeout, globalThis: null,
  location: { origin: "https://example.test" }, document,
  getComputedStyle: () => ({ visibility: "visible", display: "block" }),
  NodeFilter: { SHOW_TEXT: 4 }, Element: FakeElement,
  HTMLInputElement: FakeInput, HTMLTextAreaElement: FakeArea,
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
  const snapshot = api.inspectOrAct("inspect", "https://example.test");
  assert.match(snapshot.text, /Visible heading/);
  assert.doesNotMatch(JSON.stringify(snapshot), /NEVER-DISCLOSE|SECRET-OTP-1234|123456/);
  assert.ok(snapshot.elements.every(x => x.selector && x.tag));
  reply = await message("revoke", { origin: "https://example.test" });
  assert.equal(reply.ok, true);
  assert.equal((await message("status")).data.origins.length, 0);
  assert.equal(allowedPatterns.size, 0);
  console.log("PASS: default-off, Chrome host consent, deny/revoke, origin races, sensitive fields, safe typing & inspection");
})().catch(err => { console.error(err); process.exitCode = 1; });
