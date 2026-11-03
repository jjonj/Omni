/*
 * Omni Workstack Firefox tab adapter.
 * This is an opt-in integration surface: it never registers tabs by itself.
 * The localhost bridge sends explicit capture/activate commands only.
 * Existing OmniSync muting behavior and Firefox shortcuts are unchanged.
 */
(() => {
  "use strict";
  const ROOT = "http://127.0.0.1:18775/workstack";
  const HEADERS = { "X-Omni-Workstack": "firefox-addon" };
  const sessionId = crypto.randomUUID();
  let stopped = false;

  async function post(route, body) {
    const response = await fetch(ROOT + route, {
      method: "POST",
      headers: { ...HEADERS, "Content-Type": "application/json" },
      body: JSON.stringify(body),
      cache: "no-store",
    });
    if (!response.ok) throw new Error("Workstack HTTP " + response.status);
    return response;
  }

  function metadata(tab) {
    return {
      browserSessionId: sessionId,
      tabId: tab.id,
      windowId: tab.windowId,
      url: tab.url || "",
      title: tab.title || tab.url || "Firefox tab",
    };
  }

  async function focusedTab() {
    const [tab] = await browser.tabs.query({ active: true, lastFocusedWindow: true });
    if (!tab || !Number.isInteger(tab.id) || !Number.isInteger(tab.windowId)) {
      throw new Error("Firefox has no active tab to capture");
    }
    return tab;
  }

  async function activate(request) {
    if (!request || typeof request !== "object") throw new Error("Missing saved tab");
    const tabId = request.tabId;
    let tab = null;

    // Tab IDs are only valid during the same extension/browser session.
    if (request.browserSessionId === sessionId && Number.isInteger(tabId)) {
      try { tab = await browser.tabs.get(tabId); } catch (_) { /* closed */ }
    }

    if (!tab) {
      // Never pick an arbitrary tab with a matching URL: Workstack tracks
      // a *specific* tab, not all tabs of a website.
      const url = request.url;
      if (typeof url !== "string" || !/^https?:\/\//i.test(url) || url.length > 8192) {
        throw new Error("Closed tab cannot be restored: no supported saved URL");
      }
      tab = await browser.tabs.create({ url, active: false });
    }

    await browser.tabs.update(tab.id, { active: true });
    await browser.windows.update(tab.windowId, { focused: true });
    return metadata(await browser.tabs.get(tab.id));
  }

  async function handle(command) {
    switch (command.action) {
      case "capture":
        return metadata(await focusedTab());
      case "activate":
        return await activate(command.args);
      default:
        throw new Error("Unsupported Workstack action");
    }
  }

  async function pollForever() {
    while (!stopped) {
      try {
        const response = await fetch(ROOT + "/poll", {
          method: "GET",
          headers: HEADERS,
          cache: "no-store",
        });
        if (response.status === 204) continue;
        if (!response.ok) throw new Error("Workstack HTTP " + response.status);
        const command = await response.json();
        if (!command || typeof command.id !== "string") continue;
        let result;
        try { result = await handle(command); }
        catch (error) { result = { error: String(error.message || error) }; }
        // Each command has a unique ID. A lost acknowledgement is ambiguous,
        // so the Hub will not silently retry browser mutations.
        await post("/result", { id: command.id, result });
      } catch (error) {
        // No tab enumeration or notification while OmniHub is offline.
        await new Promise(resolve => setTimeout(resolve, 2000));
      }
    }
  }

  browser.tabs.onRemoved.addListener((tabId, removeInfo) => {
    if (!Number.isInteger(tabId)) return;
    void post("/event", {
      type: "closed",
      tab: {
        browserSessionId: sessionId,
        tabId,
        windowId: removeInfo.windowId,
      },
    }).catch(() => {});
  });

  void pollForever();
})();
