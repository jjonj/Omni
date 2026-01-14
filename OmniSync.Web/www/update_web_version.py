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
            # Only targets local files (doesn't start with http/https)
            # Pattern matches .js or .css followed by an optional existing ?v=...
            # We use a negative lookbehind to ensure we don't match http:// or https://
            def replace_tag(match):
                prefix = match.group(1) # src=" or href="
                path = match.group(2)   # the actual path
                ext = match.group(3)    # .js or .css
                
                # Skip CDN/External links
                if path.startswith('http') or path.startswith('//'):
                    return match.group(0)
                
                return f'{prefix}{path}{ext}?v={timestamp}'

            # Regex breakdown:
            # (src=\"|href=\") : The attribute
            # ([^"']+) : The path (everything until the extension)
            # (\.js|\.css) : The extension
            # (?:\?v=[\d\.]*)? : Optional existing version param
            pattern = r'(src=\"|href=\")([^"\\]+?)(\.js|\.css)(?:\?v=[\d\.]*)?'
            new_content = re.sub(pattern, replace_tag, content)

            # 2. Update Timestamp in title if present (optional but nice)
            # Looks for any version string in title tags
            title_pattern = r'(<title>.*?)(?:\d{4}\.\d{2}\.\d{2}\.\d{2}\.\d{2})(.*?</title>)'
            if re.search(title_pattern, new_content):
                new_content = re.sub(title_pattern, f'\\1{timestamp}\\2', new_content)

            if new_content != content:
                with open(file_path, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                rel_path = os.path.relpath(file_path, web_root)
                print(f"  Updated: {rel_path}")

        except Exception as e:
            print(f"  Error processing {file_path}: {e}")

if __name__ == "__main__":
    update_web_version()
