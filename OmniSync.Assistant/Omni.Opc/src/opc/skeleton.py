import re
from typing import List, Set

class SkeletonExtractor:
    """
    Python implementation of the C# SkeletonExtractor service.
    Extracts structural signatures from source files, ignoring common libraries.
    """
    
    # These are directly ported from C# version, to ensure consistency
    _IGNORED_CS_NAMESPACES: Set[str] = {
        "System", "System.Collections.Generic", "System.Linq", "System.Text", "System.Threading", "System.IO", "System.Diagnostics",
        "System.Windows", "System.Windows.Input", "System.Windows.Data", "System.Windows.Controls", "System.Windows.Threading",
        "System.Collections.ObjectModel", "System.ComponentModel", "System.Runtime.CompilerServices", "System.Runtime.InteropServices",
        "System.Collections.Concurrent", "System.Globalization", "System.Net.Http", "System.Security",
        "Microsoft.Extensions.Logging", "Microsoft.Extensions.Configuration", "Microsoft.Extensions.Hosting",
        "Microsoft.Extensions.DependencyInjection", "Microsoft.AspNetCore.Mvc", "Microsoft.AspNetCore.Builder",
        "Microsoft.AspNetCore.SignalR", "Microsoft.AspNetCore.StaticFiles", "Xunit", "Moq", "Newtonsoft.Json"
    }

    _IGNORED_PY_MODULES: Set[str] = {
        "os", "sys", "time", "subprocess", "shutil", "json", "argparse", "asyncio", "logging", "math", "re", 
        "pathlib", "ctypes", "threading", "queue", "traceback", "uuid", "socket", "datetime", "struct", "pickle", "glob"
    }

    def extract(self, content: str, extension: str) -> str:
        if not content or not content.strip():
            return ""
            
        ext = extension.lower()
        if ext == ".cs":
            return self._extract_csharp(content)
        elif ext == ".py":
            return self._extract_python(content)
        return ""

    def _extract_csharp(self, content: str) -> str:
        signatures = []
        lines = content.splitlines()
        
        # Structure keywords: namespace, class, interface, enum, struct
        structure_keywords = ["namespace ", "class ", "interface ", "enum ", "struct "]
        
        for line in lines:
            trimmed = line.strip()
            if not trimmed or trimmed.startswith("//"):
                continue
                
            is_structural = any(trimmed.startswith(kw) or f" {kw}" in trimmed for kw in structure_keywords)
            
            if is_structural:
                # Exclude methods, properties, assignments, lambda arrows
                if "(" not in trimmed and ")" not in trimmed and \
                   "{ get;" not in trimmed and " = " not in trimmed and \
                   "return " not in trimmed and "=>" not in trimmed:
                    
                    # Clean up trailing structural characters
                    clean_line = trimmed.rstrip(" {;")
                    if clean_line:
                        signatures.append(clean_line)
                        
        return "¶".join(signatures)

    def _extract_python(self, content: str) -> str:
        signatures = []
        lines = content.splitlines()
        
        for line in lines:
            trimmed = line.strip()
            # Only include class declarations
            if trimmed.startswith("class ") and trimmed.endswith(":"):
                signatures.append(trimmed.rstrip(":"))
                
        return "¶".join(signatures)

    def get_imports(self, content: str, extension: str) -> List[str]:
        if not content or not content.strip():
            return []
        
        ext = extension.lower()
        if ext == ".cs":
            return self._get_csharp_imports(content)
        elif ext == ".py":
            return self._get_python_imports(content)
        return []

    def _get_csharp_imports(self, content: str) -> List[str]:
        # Using directives
        imports = re.findall(r"^\s*using\s+([A-Za-z0-9_.]+);", content, re.MULTILINE)
        
        # Filter out ignored namespaces
        filtered_imports = [imp for imp in imports if imp not in self._IGNORED_CS_NAMESPACES]
        return list(set(filtered_imports))

    def _get_python_imports(self, content: str) -> List[str]:
        imports = []
        # from ... import ...
        imports.extend(re.findall(r"^\s*from\s+([A-Za-z0-9_.]+)\s+import", content, re.MULTILINE))
        # import ...
        imports.extend(re.findall(r"^\s*import\s+([A-Za-z0-9_.]+)", content, re.MULTILINE))
        
        # Filter out ignored modules
        filtered_imports = [imp for imp in imports if imp not in self._IGNORED_PY_MODULES]
        return list(set(filtered_imports))
