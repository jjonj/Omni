import os
import json
from pathlib import Path
from typing import List, Set

class ContextEngine:
    def __init__(self, root_path: Path):
        self.root_path = root_path
        self.config_path = root_path / ".omni" / "omni_config.json"
        
        self.explicitly_ignored = {
            "conductor", "docs", "build", "bin", "obj", "assets", "resources", "gradle", "saved"
        }
        self.auto_ignored = {
            "node_modules", "dist", "out", "target", "vendor", "vendor", "venv", ".venv", "__pycache__",
            ".gradle", ".idea", ".vs", ".vscode", "debug", "release", "temp", "tmp", "logs",
            "test-results", "coverage", "publish", "screenshots", "videos", "archives",
            "packages", "extern"
        }
        self.high_value_extensions = {
            ".cs", ".py", ".js", ".ts", ".html", ".css", ".md", ".json", 
            ".xml", ".xaml", ".kt", ".kts", ".java", ".sh", ".ps1", ".bat", ".csproj", ".sln"
        }
        
        self._load_config()

    def _load_config(self):
        if not self.config_path.exists():
            return
            
        try:
            with open(self.config_path, "r", encoding="utf-8") as f:
                config = json.load(f)
                
            indexing = config.get("indexing", {})
            
            # Replace defaults if provided in config
            if "ignored_folders" in indexing:
                self.explicitly_ignored = set(indexing["ignored_folders"])
            if "auto_ignored_folders" in indexing:
                self.auto_ignored = set(indexing["auto_ignored_folders"])
            if "high_value_extensions" in indexing:
                self.high_value_extensions = set(indexing["high_value_extensions"])
                
            # Add additional ones
            if "additional_ignored_folders" in indexing:
                self.explicitly_ignored.update(indexing["additional_ignored_folders"])
            if "additional_extensions" in indexing:
                self.high_value_extensions.update(indexing["additional_extensions"])
                
        except Exception as e:
            print(f"Error loading omni_config.json: {e}")

    def generate_file_tree(self) -> List[str]:
        files = []
        self._traverse_directory(self.root_path, files)
        return files

    def _should_ignore_folder(self, folder_name: str) -> bool:
        lower_name = folder_name.lower()
        if lower_name == ".omni":
            return False
            
        if folder_name.startswith(".") and len(folder_name) > 1:
            return True
            
        if lower_name in self.explicitly_ignored or lower_name in self.auto_ignored:
            return True
            
        if lower_name.startswith("gradle-"):
            return True
            
        return False

    def _is_high_value_file(self, file_path: Path) -> bool:
        ext = file_path.suffix.lower()
        
        if ext == ".txt":
            # Root-only .txt files
            return file_path.parent == self.root_path

        return ext in self.high_value_extensions

    def _traverse_directory(self, current_path: Path, files: List[str]):
        try:
            for item in current_path.iterdir():
                if item.is_file():
                    if self._is_high_value_file(item):
                        files.append(str(item.relative_to(self.root_path)))
                elif item.is_dir():
                    if not self._should_ignore_folder(item.name):
                        self._traverse_directory(item, files)
        except PermissionError:
            pass
