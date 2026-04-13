import os
import subprocess
import sys
import tempfile
import json
import re
from html.parser import HTMLParser

class HTMLValidator(HTMLParser):
    VOID_ELEMENTS = {
        'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
        'link', 'meta', 'param', 'source', 'track', 'wbr'
    }

    def __init__(self, filename):
        super().__init__()
        self.filename = filename
        self.errors = []
        self.tags_stack = []
        self.scripts = []
        self.current_script = None

    def handle_starttag(self, tag, attrs):
        if tag not in self.VOID_ELEMENTS:
            self.tags_stack.append((tag, self.getpos()))
        
        if tag == 'script':
            attrs_dict = dict(attrs)
            script_type = attrs_dict.get('type', 'text/javascript')
            if (script_type in ['text/javascript', 'module', None] or 'javascript' in script_type) and not attrs_dict.get('src'):
                self.current_script = {
                    'start_line': self.getpos()[0],
                    'is_module': script_type == 'module',
                    'content': []
                }

    def handle_endtag(self, tag):
        if tag in self.VOID_ELEMENTS:
            return
        if not self.tags_stack:
            self.errors.append(f"{self.filename}:{self.getpos()[0]}: Unexpected closing tag </{tag}>")
            return
        start_tag, pos = self.tags_stack.pop()
        if start_tag != tag:
            self.errors.append(f"{self.filename}:{self.getpos()[0]}: Mismatched tag: expected </{start_tag}> (from line {pos[0]}), found </{tag}>")
        if tag == 'script' and self.current_script:
            self.current_script['content'] = "".join(self.current_script['content'])
            self.scripts.append(self.current_script)
            self.current_script = None

    def handle_data(self, data):
        if self.current_script:
            self.current_script['content'].append(data)

    def validate_structure(self):
        for tag, pos in reversed(self.tags_stack):
            self.errors.append(f"{self.filename}:{pos[0]}: Unclosed tag <{tag}>")

def check_heuristics(content, filename, start_line=1):
    # This is now replaced by ESLint for general checks
    return []

def run_eslint(file_path):
    """Runs ESLint on a file (handles JS and HTML via plugin)."""
    # Use the local eslint binary in OmniSync.Cli/omni-extension
    ext_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    eslint_bin = os.path.join(ext_dir, "node_modules", ".bin", "eslint")
    if not os.path.exists(eslint_bin):
        eslint_bin = "eslint" # Fallback to global
    
    config_path = os.path.join(ext_dir, ".eslintrc.json")
    
    cmd = [eslint_bin, "-c", config_path, file_path, "--format", "unix"]
    result = subprocess.run(cmd, capture_output=True, text=True, shell=os.name == 'nt')
    
    # ESLint returns 1 if it finds errors
    if result.returncode != 0 and result.stdout:
        return result.stdout.strip()
    return None

def run_tsc(file_path):
    """Runs TSC on a JS file to check for type/method existence errors."""
    ext_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    tsc_bin = os.path.join(ext_dir, "node_modules", ".bin", "tsc")
    node_modules = os.path.join(ext_dir, "node_modules")
    if not os.path.exists(tsc_bin):
        tsc_bin = "tsc"
    
    # We want to check JS, allow JS, and no emit. 
    # Use baseUrl to help find modules in the extension's node_modules.
    cmd = [
        tsc_bin, file_path, "--checkJs", "--allowJs", "--noEmit", 
        "--moduleResolution", "node", "--target", "es2020", 
        "--skipLibCheck", "true", "--lib", "es2020,dom",
        "--ignoreDeprecations", "6.0",
        "--baseUrl", node_modules,
        "--noImplicitAny", "false",
        "--strict", "false"
    ]
    
    result = subprocess.run(cmd, capture_output=True, text=True, shell=os.name == 'nt')
    if result.returncode != 0 and result.stdout:
        lines = result.stdout.strip().splitlines()
        # High signal errors:
        # TS2339: Property does not exist (missing method)
        # TS2345: Argument type mismatch
        # TS2551: Property does not exist (with suggestion)
        high_signal = []
        for l in lines:
            if any(code in l for code in ["TS2339", "TS2345", "TS2551"]):
                # Filter out useless DOM casting noise and 'never' inference noise
                if "Property 'value' does not exist on type 'HTMLElement'" in l: continue
                if "does not exist on type 'never'" in l: continue
                high_signal.append(l)
        return "\n".join(high_signal) if high_signal else None
    return None

def check_js_syntax(content, filename, is_module=False, start_line=1):
    suffix = '.mjs' if is_module else '.js'
    with tempfile.NamedTemporaryFile(mode='w', suffix=suffix, delete=False, encoding='utf-8') as tmp:
        tmp.write(content)
        tmp_path = tmp.name

    errors = []
    try:
        # 1. Basic Node syntax check
        cmd = ["node", "--check", tmp_path]
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            error_output = result.stderr.replace(tmp_path, filename)
            errors.append(error_output.strip())

        # 2. Run ESLint
        eslint_err = run_eslint(tmp_path)
        if eslint_err:
            errors.append(eslint_err.replace(tmp_path, filename))
            
        # 3. Run TSC
        tsc_err = run_tsc(tmp_path)
        if tsc_err:
            errors.append(tsc_err.replace(tmp_path, filename))

        return "\n".join(errors) if errors else None
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)

def process_file(file_path):
    print(f"Checking: {file_path}...")
    errors_found = False
    
    # 1. For JS files, run all checks
    if file_path.endswith('.js'):
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        err = check_js_syntax(content, file_path)
        if err:
            print(err)
            errors_found = True
    
    # 2. For HTML files, we use the parser to extract scripts and check them individually
    # because tsc doesn't support HTML. ESLint plugin-html handles it for eslint though.
    elif file_path.endswith('.html'):
        # ESLint for the whole HTML file (handles scripts via plugin)
        eslint_err = run_eslint(file_path)
        if eslint_err:
            print(eslint_err)
            errors_found = True
            
        # Extract and check scripts with TSC/Node
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        parser = HTMLValidator(file_path)
        try:
            parser.feed(content)
            parser.validate_structure()
        except Exception as e:
            print(f"{file_path}: Fatal error during HTML parsing: {e}")
            errors_found = True
            
        for err in parser.errors:
            print(err)
            errors_found = True
            
        for script in parser.scripts:
            err = check_js_syntax(script['content'], file_path, script['is_module'], script['start_line'])
            if err:
                print(err)
                errors_found = True
        
    return errors_found

def main():
    if len(sys.argv) < 2:
        print("Usage: python check_syntax.py <file_or_directory>")
        sys.exit(1)
    target = sys.argv[1]
    any_errors = False
    if os.path.isfile(target):
        any_errors = process_file(target)
    elif os.path.isdir(target):
        for root, _, files in os.walk(target):
            for file in files:
                if file.endswith(('.html', '.js')):
                    full_path = os.path.join(root, file)
                    if process_file(full_path):
                        any_errors = True
    else:
        print(f"Error: {target} not found.")
        sys.exit(1)
    if not any_errors:
        print("\nSuccess: No errors found.")
        sys.exit(0)
    else:
        print("\nFailure: Errors detected.")
        sys.exit(1)

if __name__ == "__main__":
    main()
