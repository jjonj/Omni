const $ = id => document.getElementById(id);
async function send(command) {
  const answer = await chrome.runtime.sendMessage({ command });
  if (!answer?.ok) throw Error(answer?.error || "Extension background unavailable");
  return answer.data;
}
function show(message, isError = false) {
  $("msg").textContent = message;
  $("msg").className = isError ? "error" : "";
}
async function refresh() {
  const state = await send("status");
  $("state").textContent = (state.enabled ? "Enabled" : "Paused") +
    (state.connected ? " · Local bridge worker active" : " · Waiting for local bridge");
}
$("enable").addEventListener("click", async () => {
  try { await send("enable"); await refresh(); show("Enabled."); }
  catch (error) { show(error.message, true); }
});
$("disable").addEventListener("click", async () => {
  try { await send("disable"); await refresh(); show("Paused."); }
  catch (error) { show(error.message, true); }
});
refresh().catch(error => show(error.message, true));
