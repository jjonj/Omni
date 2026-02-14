import re
from typing import List

class SkeletonExtractor:
    """
    Python implementation of the C# SkeletonExtractor service.
    Extracts structural signatures from source files.
    """
    
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
        # Simplified regex-based check as in the original C# code
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
