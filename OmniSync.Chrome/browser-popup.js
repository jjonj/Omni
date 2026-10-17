const $ = id => document.getElementById(id);
async function send(command, extra = {}) {
  const answer = await chrome.runtime.sendMessage({ command, ...extra });
  if (!answer?.ok) throw Error(answer?.error || "Extension background unavailable");
  return answer.data;
}
function show(message, isError = false) {
  $("msg").textContent = message;
  $("msg").className = isError ? "error" : "";
}
async function refresh() {
  const state = await send("status");
  $("state").textContent = (state.enabled ? "Enabled" : "Disabled") +
    (state.paired ? " · Paired" : " · Not paired") +
    (state.connected ? " · Worker active" : "");
  const list = $("sites");
  list.replaceChildren();
  for (const origin of state.origins) {
    const li = document.createElement("li");
    const code = document.createElement("code");
    code.textContent = origin;
    const button = document.createElement("button");
    button.textContent = "Revoke"; button.className = "secondary";
    button.addEventListener("click", async () => {
      try { await send("revoke", { origin }); await refresh(); }
      catch (error) { show(error.message, true); }
    });
    li.append(code, button); list.append(li);
  }
}
$("enable").addEventListener("click", async () => {
  try {
    await send("enable", { token: $("token").value });
    $("token").value = ""; show("Browser control enabled. Approve sites below.");
    await refresh();
  } catch (error) { show(error.message, true); }
});
$("disable").addEventListener("click", async () => {
  try { await send("disable"); $("token").value = ""; await refresh(); show("Disabled."); }
  catch (error) { show(error.message, true); }
});
let selectedOrigin = null;
chrome.tabs.query({ active: true, currentWindow: true }).then(tabs => {
  try {
    const u = new URL(tabs[0]?.url);
    if (u.protocol === "https:" || (u.protocol === "http:" &&
        ["localhost", "127.0.0.1"].includes(u.hostname))) selectedOrigin = u.origin;
  } catch { /* unsupported browser-internal tab */ }
  $("allow").disabled = !selectedOrigin;
});
$("allow").addEventListener("click", async () => {
  if (!selectedOrigin) return show("Unsupported tab URL.", true);
  try {
    // Chrome requires a direct user gesture. Do not await a background message
    // before requesting the native, origin-scoped host-permission prompt.
    const u = new URL(selectedOrigin);
    const granted = await chrome.permissions.request({ origins: [u.protocol + "//" + u.hostname + "/*"] });
    if (!granted) throw Error("Chrome permission was denied.");
    await send("allowOrigin", { origin: selectedOrigin });
    await refresh(); show("Site approved.");
  } catch (error) { show(error.message, true); }
});
refresh().catch(error => show(error.message, true));