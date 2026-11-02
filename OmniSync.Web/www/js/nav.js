/* Shared OmniHub navigation for legacy pages. */
document.addEventListener('DOMContentLoaded', () => {
  const target = document.querySelector('.nav-menu');
  if (!target) return;
  const path = location.pathname.toLowerCase();
  const active = (url) => path === url.toLowerCase() ? ' active' : '';
  target.innerHTML = `<div class="nav-brand">OmniHub</div>
    <a class="nav-item${active('/gateway/')}" href="/gateway/">Gateway</a>
    <a class="nav-item${active('/presentations/')}" href="/presentations/">Presentations</a>
    <details class="omni-more"><summary class="nav-item">More ▾</summary>
      <div class="omni-more-menu">
        <a href="/Scheduler.html">Scheduler <small>Archived</small></a>
        <a href="/TFT.html">TFT</a>
        <a href="/Settings.html">Settings</a>
      </div>
    </details>`;
  if (['/scheduler.html','/tft.html','/settings.html'].includes(path)) {
    target.querySelector('.omni-more > summary').classList.add('active');
  }
});
