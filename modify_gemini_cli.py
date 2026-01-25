import os
import re

GEMINI_CLI_DIR = r"D:\\SSDProjects\\Tools\\gemini-cli"

def modify_file(rel_path, search_pattern, replacement, use_re=False):
    full_path = os.path.join(GEMINI_CLI_DIR, rel_path)
    if not os.path.exists(full_path):
        print(f"Error: File not found {full_path}")
        return
    with open(full_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    if use_re:
        new_content = re.sub(search_pattern, replacement, content, flags=re.MULTILINE)
    else:
        new_content = content.replace(search_pattern, replacement)

    if new_content == content:
        print(f"Warning: No changes made to {rel_path}")
    else:
        with open(full_path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f"Successfully modified {rel_path}")

# Final attempt at the call insertion
search_str = '''        appEvents.emit(
          AppEvent.RemoteResponse,
          '[HISTORY_START]' '''

replacement_str = '''        performTruncation();
        appEvents.emit(
          AppEvent.RemoteResponse,
          '[HISTORY_START]' '''

modify_file(
    r"packages\\cli\\src\\ui\\AppContainer.tsx",
    search_str,
    replacement_str
)