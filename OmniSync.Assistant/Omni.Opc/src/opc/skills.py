import os
from pathlib import Path
from typing import List, Dict, Any

class SkillDiscovery:
    """
    Scans the project for executable scripts and exposes them as potential AI skills.
    """
    def __init__(self, project_root: Path):
        self.project_root = project_root
        self.script_extensions = {".py", ".sh", ".ps1", ".bat"}
        self.ignored_dirs = {".git", "node_modules", "venv", ".venv", "__pycache__", "bin", "obj"}

    def discover_skills(self) -> List[Dict[str, Any]]:
        skills = []
        try:
            for item in self.project_root.iterdir():
                if item.is_file() and item.suffix.lower() in self.script_extensions:
                    skills.append({
                        "name": item.name,
                        "path": str(item.absolute()),
                        "type": "script"
                    })
                elif item.is_dir() and item.name.lower() not in self.ignored_dirs:
                    # Also check one level deep in subdirectories (like TestScripts)
                    for subitem in item.iterdir():
                        if subitem.is_file() and subitem.suffix.lower() in self.script_extensions:
                            skills.append({
                                "name": f"{item.name}/{subitem.name}",
                                "path": str(subitem.absolute()),
                                "type": "script"
                            })
        except Exception:
            pass
        return skills

    def generate_skills_manifest(self) -> str:
        skills = self.discover_skills()
        if not skills:
            return "No automated skills discovered."
            
        lines = ["## Automated Skills"]
        for skill in skills:
            lines.append(f"- **{skill['name']}**: `{skill['path']}`")
        return "\n".join(lines)
