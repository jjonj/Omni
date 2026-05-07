import json
import os
from pathlib import Path
from typing import List, Dict, Any, Optional
from dataclasses import dataclass, asdict

@dataclass
class FileContext:
    path: str
    last_modified_ms: int

class StateService:
    def __init__(self, project_root: Path):
        self.project_root = project_root
        self.state_dir = project_root / ".omni" / "projectcontext"
        self.state_file = self.state_dir / "project_files.txt"

    def initialize(self):
        self.state_dir.mkdir(parents=True, exist_ok=True)

    def load_state(self) -> List[FileContext]:
        if not self.state_file.exists():
            return []
        
        try:
            with open(self.state_file, "r", encoding="utf-8") as f:
                text_data = f.read()
                
            return self._parse_compact_text(text_data)
        except Exception:
            return []

    def _parse_compact_text(self, text: str) -> List[FileContext]:
        files = []
        lines = text.splitlines()
        
        # indent, path
        dir_stack = [(-1, "")]
        
        for line in lines:
            if "|" not in line:
                continue
                
            parts = line.split("|", 1)
            ms_str = parts[0]
            content = parts[1]
            
            try:
                ms = int(ms_str)
            except ValueError:
                ms = 0
                
            indent = 0
            while indent < len(content) and content[indent] == ' ':
                indent += 1
            
            label = content[indent:]
            
            if label.startswith("↳\\"):
                dir_name = label[2:]
                while len(dir_stack) > 1 and dir_stack[-1][0] >= indent:
                    dir_stack.pop()
                
                parent_path = dir_stack[-1][1]
                full_path = os.path.join(parent_path, dir_name) if parent_path else dir_name
                dir_stack.append((indent, full_path))
            else:
                # File (no arrow prefix)
                file_name = label
                while len(dir_stack) > 1 and dir_stack[-1][0] >= indent:
                    dir_stack.pop()
                
                dir_path = dir_stack[-1][1]
                files.append(FileContext(
                    path=os.path.join(dir_path, file_name) if dir_path else file_name,
                    last_modified_ms=ms
                ))
                
        return files

    def save_state(self, files: List[FileContext]):
        self.initialize()
        
        compact_text = self._generate_compact_text(files)
        
        with open(self.state_file, "w", encoding="utf-8") as f:
            f.write(compact_text)

    def _generate_compact_text(self, files: List[FileContext]) -> str:
        class Node:
            def __init__(self, name=""):
                self.name = name
                self.files = []
                self.sub_dirs = {}

        root = Node()
        for f in files:
            parts = Path(f.path).parts
            current = root
            for i in range(len(parts) - 1):
                if parts[i] not in current.sub_dirs:
                    current.sub_dirs[parts[i]] = Node(parts[i])
                current = current.sub_dirs[parts[i]]
            current.files.append(f)

        lines = []
        self._write_node(root, "", lines)
        return "\n".join(lines)

    def _write_node(self, node, indent, lines):
        # Files first, sorted
        for f in sorted(node.files, key=lambda x: x.path):
            file_name = Path(f.path).name
            lines.append(f"{f.last_modified_ms}|{indent}{file_name}")

        # Subdirectories, sorted
        for dir_name in sorted(node.sub_dirs.keys()):
            child = node.sub_dirs[dir_name]
            max_ms = self._get_max_ms(child)
            lines.append(f"{max_ms}|{indent}↳\\{dir_name}")
            self._write_node(child, indent + " ", lines)

    def _get_max_ms(self, node) -> int:
        max_ms = max([f.last_modified_ms for f in node.files], default=0)
        for child in node.sub_dirs.values():
            max_ms = max(max_ms, self._get_max_ms(child))
        return max_ms
