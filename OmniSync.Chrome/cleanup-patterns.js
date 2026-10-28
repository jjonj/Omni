const $ = id => document.getElementById(id);
async function send(command, pattern) {
  const result = await chrome.runtime.sendMessage({ command, ...(pattern === undefined ? {} : { pattern }) });
  if (!result?.ok) throw Error(result?.error || 'Extension background unavailable');
  return result.data;
}
function status(message, error = false) {
  $('status').textContent = message;
  $('status').className = error ? 'error' : '';
}
function render(patterns) {
  $('patterns').replaceChildren();
  $('empty').hidden = patterns.length > 0;
  for (const pattern of patterns) {
    const row = document.createElement('li');
    const label = document.createElement('span');
    label.className = 'pattern';
    label.textContent = pattern;
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'remove';
    remove.textContent = 'Remove';
    remove.setAttribute('aria-label', `Remove pattern ${pattern}`);
    remove.addEventListener('click', async () => {
      remove.disabled = true;
      try {
        render(await send('patterns:remove', pattern));
        status('Pattern removed.');
      } catch (error) { status(error.message, true); remove.disabled = false; }
    });
    row.append(label, remove);
    $('patterns').append(row);
  }
}
$('add-form').addEventListener('submit', async event => {
  event.preventDefault();
  const pattern = $('pattern').value.trim();
  if (!pattern) return;
  $('add').disabled = true;
  try {
    render(await send('patterns:add', pattern));
    $('pattern').value = '';
    $('pattern').focus();
    status('Pattern saved.');
  } catch (error) { status(error.message, true); }
  finally { $('add').disabled = false; }
});
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.customCleanupPatterns && !$('add').disabled) {
    render(changes.customCleanupPatterns.newValue || []);
  }
});
$('add').disabled = true;
$('status').textContent = 'Loading patterns from OmniHub…';
void send('patterns:list').then(patterns => {
  render(patterns);
  status(`Loaded ${patterns.length} pattern${patterns.length === 1 ? '' : 's'} from OmniHub.`);
  $('add').disabled = false;
}).catch(error => {
  $('empty').hidden = true;
  status(`Cannot load patterns from OmniHub: ${error.message}`, true);
});
