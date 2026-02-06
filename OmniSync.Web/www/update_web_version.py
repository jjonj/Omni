import re
import os
import glob
from datetime import datetime

def update_web_version():
    timestamp = datetime.now().strftime("%Y.%m.%d.%H.%M")
    web_root = os.path.dirname(os.path.abspath(__file__))
    
    # Find all html files in www and subfolders
    html_files = glob.glob(os.path.join(web_root, "**/*.html"), recursive=True)
    
    print(f"Syncing Web Version: {timestamp}")
    
    for file_path in html_files:
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                content = f.read()

            # 1. Update Version in Script and Link tags
            def replace_tag(match):
                prefix = match.group(1) # src=" or href="
                path = match.group(2)   # the actual path
                ext = match.group(3)    # .js or .css
                
                if path.startswith('http') or path.startswith('//'):
                    return match.group(0)
                
                return f'{prefix}{path}{ext}?v={timestamp}'

            pattern = r'(src="|href=")([^"\\]+?)(\.js|\.css)(?:\?v=[\d\.]*)?'
            new_content = re.sub(pattern, replace_tag, content)

            # 2. Update Version in Navigation Bar
            # Look for class="nav-version" and update its text content
            # Regex: Group 1 is everything before the version, Group 2 is the current version, Group 3 is the closing tag
            nav_version_pattern = r'(<span[^>]*class="nav-version"[^>]*>)(.*?)(</span>)'
            if re.search(nav_version_pattern, new_content):
                new_content = re.sub(nav_version_pattern, r'\g<1>' + timestamp + r'\g<3>', new_content)

            if new_content != content:
                with open(file_path, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                rel_path = os.path.relpath(file_path, web_root)
                print(f"  Updated: {rel_path}")

        except Exception as e:
            print(f"  Error processing {file_path}: {e}")

if __name__ == "__main__":
    update_web_version()
