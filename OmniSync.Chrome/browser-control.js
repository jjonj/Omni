/* OmniMCP loopback browser bridge (MV3 service worker).
 * Auto-starts locally. No pairing prompts or per-site approvals required.
 * Host permissions come from the installed extension manifest. */
(() => {
  "use strict";
  const BRIDGE = "http://127.0.0.1:18773/browser";
  const KEY = "omniBrowserControl";
  let current = { enabled: true };
  let polling = false;
  const AUTO_RELOAD_ALARM = "browser-control-development-reload";
  const AUTO_RELOAD_MINUTES = 5;
  const QUIET_BEFORE_RELOAD_MS = 2 * 60 * 1000;
  let lastCommandAt = Date.now();
  const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

  // Unpacked development extensions read the source folder again on runtime.reload().
  // Only reload after a quiet period; never attempt to install a different directory.
  async function maybeReloadDevelopmentExtension() {
    if (Date.now() - lastCommandAt < QUIET_BEFORE_RELOAD_MS) return false;
    const info = await chrome.management.getSelf();
    if (info.installType !== "development") return false;
    chrome.runtime.reload(); // Extension only; does not restart Vivaldi or its tabs.
    return true;
  }

  const validOrigin = url => {
    try {
      const u = new URL(url);
      if (u.username || u.password || u.hash && /token|password|secret|key=/i.test(u.hash)) return null;
      if (u.protocol !== "https:" && !(u.protocol === "http:" &&
          (u.hostname === "localhost" || u.hostname === "127.0.0.1" ||
           u.hostname === "192.168.1.1"))) return null;
      return u.origin;
    } catch { return null; }
  };
  // Chrome match patterns contain hosts, never ports, even on localhost.
  const sitePattern = origin => { const u = new URL(origin); return u.protocol + "//" + u.hostname + "/*"; };
  // URLs often carry OAuth codes, password reset links or session tokens.
  // Never return query strings, fragments or embedded credentials to MCP.
  const displayUrl = raw => { try { const u = new URL(raw); return u.origin + u.pathname; } catch { return ""; } };
  async function allowed(url) {
    const origin = validOrigin(url);
    return !!origin && !!(await chrome.permissions.contains({ origins: [sitePattern(origin)] }));
  }
  async function load() {
    const stored = (await chrome.storage.local.get(KEY))[KEY] || {};
    // Migrate old pairing-based settings to easy local mode on first reload.
    current = { enabled: stored.mode === "local" ? stored.enabled !== false : true };
    if (stored.mode !== "local") {
      // Discard the obsolete stored pairing token and per-site grant list.
      await chrome.storage.local.set({ [KEY]: { mode: "local", enabled: true } });
    }
    return current;
  }
  async function save() {
    await chrome.storage.local.set({ [KEY]: current });
  }
  async function status() {
    await load();
    return { enabled: current.enabled, mode: "local", connected: polling };
  }

  async function setEnabled(enabled) {
    await load();
    current.enabled = !!enabled;
    current.mode = "local";
    await save();
    if (current.enabled) void start();
    return status();
  }
  async function getApprovedTab(tabId) {
    const tab = await chrome.tabs.get(tabId);
    if (!await allowed(tab.url || "")) throw new Error("Tab URL is not accessible by this extension.");
    return tab;
  }

  // Runs in Chrome's isolated world, not the page's JS world.
  // Never read any input.value, textarea.value, password, payment or OTP field.
  function inspectOrAct(action, expectedOrigin, selector, typed, options = {}) {
    if (location.origin !== expectedOrigin) throw Error("Tab navigated outside approved origin.");
    const sensitive = /(passw|passwd|secret|token|auth|otp|one.?time|2fa|pin|cvv|cvc|card.?num|credit|debit|payment|social.?security|iban|bank|recovery|api.?key|session)/i;
    const risky = element => {
      if (!element || !(element instanceof Element)) return true;
      const input = element.closest("input, textarea, select, [contenteditable]");
      if (!input) return false;
      const typ = (input.getAttribute("type") || "").toLowerCase();
      if (["password", "hidden", "email", "tel", "number", "date", "file"].includes(typ))
        return true;
      const attrs = ["id", "name", "autocomplete", "placeholder", "aria-label", "data-testid"];
      if (attrs.some(k => sensitive.test(input.getAttribute(k) || ""))) return true;
      const form = input.closest("form");
      return !!form && !!form.querySelector('input[type="password"],input[autocomplete*="cc-"],input[autocomplete="one-time-code"]');
    };
    const visible = el => {
      if (!el || !el.getClientRects().length) return false;
      const css = getComputedStyle(el);
      return css.visibility !== "hidden" && css.display !== "none";
    };
    const label = el => String(el.getAttribute("aria-label") || el.innerText ||
      el.getAttribute("placeholder") || "").replace(/\s+/g, " ").slice(0, 90);
    const path = el => {
      const parts = [];
      for (let cur = el; cur && cur !== document.body && parts.length < 8; cur = cur.parentElement) {
        const siblings = [...(cur.parentElement?.children || [])]
          .filter(child => child.tagName === cur.tagName);
        const tag = cur.tagName.toLowerCase();
        parts.unshift(tag + ":nth-of-type(" + (siblings.indexOf(cur) + 1) + ")");
      }
      return "body > " + parts.join(" > ");
    };
    if (action === "page_state") {
      return {
        origin: location.origin, title: document.title.slice(0, 160),
        readyState: document.readyState,
        viewport: { width: window.innerWidth, height: window.innerHeight },
        scroll: { x: Math.round(window.scrollX), y: Math.round(window.scrollY) },
        pageHeight: Math.round(document.documentElement.scrollHeight)
      };
    }
    if (action === "find") {
      const needle = String(options.text || "").trim().toLocaleLowerCase();
      if (!needle || needle.length > 120) throw Error("Invalid visible-text query.");
      const maxResults = Number.isInteger(options.maxResults) ? options.maxResults : 12;
      if (maxResults < 1 || maxResults > 20) throw Error("Invalid result limit.");
      const matches = [];
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      let nodesVisited = 0, count = 0;
      while (walker.nextNode() && nodesVisited++ < 3000 && count < 200) {
        const node = walker.currentNode, parent = node.parentElement;
        if (!parent || !visible(parent) ||
            parent.closest("form, input, textarea, select, [contenteditable], script, style, noscript, [data-private]") ||
            sensitive.test(parent.getAttribute("autocomplete") || "")) continue;
        const text = (node.textContent || "").replace(/\s+/g, " ").trim();
        if (!text || !text.toLocaleLowerCase().includes(needle)) continue;
        count++;
        if (matches.length < maxResults) matches.push({
          selector: path(parent),
          snippet: text.slice(0, 180)
        });
      }
      return { matches, count, truncated: count >= 200 || nodesVisited > 3000 };
    }
    if (action === "scroll" && !selector) {
      const dx = options.deltaX ?? 0, dy = options.deltaY ?? 0;
      if (!Number.isInteger(dx) || !Number.isInteger(dy) ||
          Math.abs(dx) > 2000 || Math.abs(dy) > 2000) throw Error("Invalid scroll offset.");
      window.scrollBy({ left: dx, top: dy, behavior: "instant" });
      return { scrolled: true };
    }
    if (action === "inspect") {
      const chunks = [];
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      while (walker.nextNode() && chunks.join(" ").length < 5500) {
        const node = walker.currentNode, parent = node.parentElement;
        if (!parent || !visible(parent) ||
            parent.closest("form, input, textarea, select, [contenteditable], script, style, noscript, [data-private]") ||
            sensitive.test(parent.getAttribute("autocomplete") || "")) continue;
        const text = (node.textContent || "").replace(/\s+/g, " ").trim();
        if (text && text.length < 1000) chunks.push(text);
      }
      const elements = [...document.querySelectorAll("a, button, input, textarea, [role=button], select")].slice(0, 400)
        .filter(el => visible(el) && !risky(el) &&
            !el.closest('form:has(input[type="password"],input[autocomplete*="cc-"])'))
        .slice(0, 60).map(el => ({
          tag: el.tagName.toLowerCase(), role: el.getAttribute("role") || "",
          label: label(el), selector: path(el)
        }));
      return { origin: location.origin, title: document.title.slice(0, 160),
        text: chunks.join(" ").slice(0, 5500), elements };
    }
    if (typeof selector !== "string" || selector.length > 512)
      throw Error("Invalid selector.");
    const element = document.querySelector(selector);
    if (action === "wait_for" && (!element || !visible(element)))
      return { found: false };
    if (!element || !visible(element)) throw Error("Visible target not found.");
    if (risky(element) || element.closest('form:has(input[type="password"],input[autocomplete*="cc-"])'))
      throw Error("Sensitive form target is prohibited.");
    if (action === "wait_for") return { found: true, selector };
    if (action === "focus") {
      if (typeof element.focus !== "function") throw Error("Element cannot be focused.");
      element.focus({ preventScroll: true });
      return { focused: true, selector };
    }
    if (action === "scroll") {
      const dx = options.deltaX ?? 0, dy = options.deltaY ?? 0;
      if (!Number.isInteger(dx) || !Number.isInteger(dy) ||
          Math.abs(dx) > 2000 || Math.abs(dy) > 2000) throw Error("Invalid scroll offset.");
      if (typeof element.scrollBy !== "function") throw Error("Element cannot scroll.");
      element.scrollBy({ left: dx, top: dy, behavior: "instant" });
      return { scrolled: true, selector };
    }
    if (action === "select") {
      if (!(element instanceof HTMLSelectElement)) throw Error("Target is not a select dropdown.");
      const choice = String(options.optionText || "");
      if (choice.length < 1 || choice.length > 120) throw Error("Invalid option text.");
      const selected = [...element.options].find(opt => opt.textContent.trim() === choice && !opt.disabled);
      if (!selected) throw Error("Option not found or disabled.");
      selected.selected = true;
      element.dispatchEvent(new Event("input", { bubbles: true }));
      element.dispatchEvent(new Event("change", { bubbles: true }));
      return { selected: true, selector };
    }
    if (action === "click") {
      const anchor = element.closest("a[href]");
      if (anchor) {
        const destination = new URL(anchor.getAttribute("href") || "", location.href);
        if ((destination.protocol !== "https:" &&
            !(destination.protocol === "http:" && ["localhost", "127.0.0.1", "192.168.1.1"].includes(destination.hostname))) ||
            destination.username || destination.password)
          throw Error("Only HTTPS, localhost HTTP, or router HTTP links can be opened.");
      }
      // Block implicit credential/payment form submissions.
      if (element.matches('input[type="submit"], input[type="image"], button[form], input[form], a[href^="javascript:"]') ||
          (element.tagName === "BUTTON" && element.closest("form") &&
           (element.getAttribute("type") || "submit").toLowerCase() !== "button"))
        throw Error("Form submission or script-link activation must be performed manually.");
      element.click();
      return { clicked: true, selector };
    }
    if (action === "type") {
      if (!(element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement))
        throw Error("Only standard text controls can receive typing.");
      if (element instanceof HTMLInputElement && !["text", "search", ""].includes(element.type))
        throw Error("This input type is not permitted.");
      if (typeof typed !== "string" || typed.length > 4000)
        throw Error("Text exceeds safety limit.");
      element.focus();
      const proto = element instanceof HTMLTextAreaElement
        ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, "value").set.call(element, typed);
      element.dispatchEvent(new Event("input", { bubbles: true }));
      element.dispatchEvent(new Event("change", { bubbles: true }));
      return { typed: true, selector, length: typed.length };
    }
    throw Error("Unknown DOM operation.");
  }

  // Chrome can discard the frame/result when a click navigates the page.
  // Treat missing script results as an UNKNOWN outcome, never a failed click
  // that can safely be automatically retried. No raw Chrome error, URL query,
  // OAuth token, or form content is returned to the MCP caller.
  function injectionFailure(action, rawError) {
    const message = String(rawError?.message || "");
    const mutating = ["click", "type", "select", "focus", "scroll"].includes(action);
    const accessRestricted = /Cannot access contents|Cannot access a chrome|extensions gallery cannot be scripted|missing host permission|cannot inject into|not allowed to access|Sensitive form target|Form submission or script-link activation/i.test(message);
    const sensitive = /Sensitive form target|Form submission or script-link activation/i.test(message);
    const navigated = /frame .* (removed|unavailable)|No frame with|frame .*not found|document was unloaded|Cannot find context with specified id/i.test(message);
    const tabGone = /No tab with id|tab was closed|No tab with given id/i.test(message);
    const code = sensitive ? "SENSITIVE_ACTION_RESTRICTED" :
      accessRestricted ? "FRAME_ACCESS_RESTRICTED" :
      navigated ? "FRAME_NAVIGATED" :
      tabGone ? "TAB_UNAVAILABLE" :
      rawError ? "FRAME_INJECTION_FAILED" : "FRAME_RESULT_UNAVAILABLE";
    const guidance = accessRestricted
      ? "The browser/extension rejected this target. Do not switch to desktop input to bypass OAuth, credential, payment, form-submission, or platform protections; use manual interaction for protected steps."
      : "Inspect tabs and the current page state before any retry; navigation may have happened even when the result was lost. Physical desktop control is separate and only appropriate for independently authorized, non-sensitive actions on a verified visible window, never after a platform safety rejection.";
    return {
      error: code === "FRAME_RESULT_UNAVAILABLE"
        ? "Browser frame returned no result; action outcome is unknown."
        : "Browser frame action could not be confirmed.",
      code, outcome: mutating ? "unknown" : "unavailable",
      retrySafe: !mutating && !accessRestricted,
      guidance
    };
  }

  async function execute(work) {
    lastCommandAt = Date.now();
    await load(); // Recheck opt-in before handling any queued command.
    if (!current.enabled) throw new Error("Extension browser control disabled.");
    if (Date.now() > work.deadline) throw new Error("Command expired.");
    const a = work.arguments || {};
    if (work.action === "tabs") {
      const tabs = await chrome.tabs.query({});
      const approved = [];
      for (const tab of tabs) {
        if (await allowed(tab.url || "")) approved.push({
          tabId: tab.id, origin: validOrigin(tab.url), title: (tab.title || "").slice(0, 160),
          url: displayUrl(tab.url || "").slice(0, 2048), active: !!tab.active
        });
      }
      return { tabs: approved.slice(0, 100) };
    }
    if (work.action === "navigate") {
      if (!await allowed(a.url)) throw new Error("Destination URL not accessible by extension.");
      if (a.newTab === true) {
        const tab = await chrome.tabs.create({ url: a.url, active: false });
        return { tabId: tab.id, navigated: true };
      }
      if (!Number.isInteger(a.tabId)) throw new Error("Use an approved tabId or newTab=true.");
      await getApprovedTab(a.tabId);
      await chrome.tabs.update(a.tabId, { url: a.url });
      return { tabId: a.tabId, navigated: true };
    }
    if (!Number.isInteger(a.tabId) || a.tabId <= 0) throw new Error("Invalid tab ID.");
    const tab = await getApprovedTab(a.tabId);
    if (work.action === "activate") {
      await chrome.tabs.update(a.tabId, { active: true });
      return { activated: true, tabId: a.tabId };
    }
    if (work.action === "bring_to_front") {
      // Unlike activate, also ask Chrome to foreground the containing window.
      // Do not create a new window, launch a browser, or use OS mouse/shell input.
      if (!Number.isInteger(tab.windowId) || tab.windowId < 0)
        throw new Error("Tab does not belong to a valid browser window.");
      const browserWindow = await chrome.windows.get(tab.windowId);
      if (browserWindow.state === "minimized")
        await chrome.windows.update(tab.windowId, { state: "normal" });
      await chrome.tabs.update(a.tabId, { active: true });
      await chrome.windows.update(tab.windowId, { focused: true });
      const focusedWindow = await chrome.windows.get(tab.windowId);
      return {
        tabId: a.tabId, windowId: tab.windowId, activated: true,
        restored: browserWindow.state === "minimized",
        focused: focusedWindow.focused === true
      };
    }
    if (work.action === "close") {
      await chrome.tabs.remove(a.tabId);
      return { closed: true, tabId: a.tabId };
    }
    if (work.action === "reload") {
      await chrome.tabs.reload(a.tabId);
      return { reloading: true, tabId: a.tabId };
    }
    const runDom = async () => {
      const approvedTab = await getApprovedTab(a.tabId);
      const origin = validOrigin(approvedTab.url);
      try {
        const result = await chrome.scripting.executeScript({
          target: { tabId: a.tabId }, world: "ISOLATED",
          func: inspectOrAct, args: [work.action, origin, a.selector || "", a.text || "", a]
        });
        return result?.[0]?.result ?? injectionFailure(work.action, null);
      } catch (error) {
        return injectionFailure(work.action, error);
      }
    };
    if (work.action === "wait_for") {
      const timeoutMs = Number.isInteger(a.timeoutMs) ? a.timeoutMs : 4000;
      if (timeoutMs < 0 || timeoutMs > 8000) throw new Error("Invalid wait timeout.");
      const limit = Math.min(work.deadline, Date.now() + timeoutMs);
      do {
        await load();
        if (!current.enabled) throw new Error("Browser control disabled.");
        const result = await runDom();
        if (result.found || result.error) return result;
        if (Date.now() >= limit) break;
        await delay(Math.min(250, Math.max(0, limit - Date.now())));
      } while (Date.now() <= limit);
      return { found: false, timedOut: true };
    }
    return runDom();
  }

  async function request(endpoint, payload) {
    const response = await fetch(BRIDGE + endpoint, {
      method: payload ? "POST" : "GET",
      headers: payload ? { "Content-Type": "application/json" } : {},
      ...(payload ? { body: JSON.stringify(payload) } : {}),
      cache: "no-store"
    });
    if (!response.ok && response.status !== 204) throw new Error("Local bridge unavailable.");
    if (response.status === 204) return null;
    return response.json();
  }
  async function start() {
    if (polling) return;
    await load();
    if (!current.enabled) return;
    polling = true;
    try {
      while (current.enabled) {
        try {
          const work = await request("/poll");
          if (!work) continue;
          let result;
          try { result = await execute(work); }
          catch (err) { result = { error: String(err.message || err).slice(0, 240) }; }
          await request("/result", { id: work.id, result });
        } catch { await delay(3000); }
        await load();
      }
    } finally { polling = false; }
  }
  chrome.runtime.onMessage.addListener((msg, sender, reply) => {
    if (!msg || !["status", "enable", "disable"].includes(msg.command) || sender.id !== chrome.runtime.id) return false;
    (async () => {
      switch (msg.command) {
        case "status": return status();
        case "enable": return setEnabled(true);
        case "disable": return setEnabled(false);
      }
    })().then(data => reply({ ok: true, data }))
       .catch(error => reply({ ok: false, error: String(error.message || error) }));
    return true;
  });
  chrome.alarms.onAlarm.addListener(alarm => {
    if (alarm.name === "browser-control-wake") void start();
    if (alarm.name === AUTO_RELOAD_ALARM) {
      void maybeReloadDevelopmentExtension().catch(error => console.warn("Extension auto-reload unavailable:", error));
    }
  });
  chrome.alarms.create("browser-control-wake", { periodInMinutes: 1 });
  // Do not move the reload deadline every time MV3 wakes the service worker.
  void chrome.alarms.get(AUTO_RELOAD_ALARM).then(existing => {
    if (!existing) chrome.alarms.create(AUTO_RELOAD_ALARM, { periodInMinutes: AUTO_RELOAD_MINUTES });
  }).catch(error => console.warn("Extension auto-reload alarm unavailable:", error));
  chrome.storage.local.setAccessLevel?.({ accessLevel: "TRUSTED_CONTEXTS" }).catch(() => {});
  globalThis.BrowserControl = { start, validOrigin, displayUrl, inspectOrAct, injectionFailure, execute, maybeReloadDevelopmentExtension };
})();
