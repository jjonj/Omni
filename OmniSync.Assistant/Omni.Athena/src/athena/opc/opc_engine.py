import json
from pathlib import Path
from typing import List, Optional
import time

from athena.opc.state import StateService, FileContext
from athena.opc.engine import ContextEngine
from athena.opc.git_history import GitHistoryService
from athena.opc.skeleton import SkeletonExtractor
from athena.opc.skills import SkillDiscovery

class OpcOrchestrator:
    def __init__(self, project_root: Optional[Path] = None):
        self.project_root = project_root or Path.cwd()
        self.state_service = StateService(self.project_root)
        self.git_service = GitHistoryService(str(self.project_root))
        self.engine = ContextEngine(self.project_root)
        self.skill_discovery = SkillDiscovery(self.project_root)

    def handle_sync(self):
        self.state_service.initialize()
        files = self.engine.generate_file_tree()
        new_list = []
        
        for rel_path in files:
            full_path = self.project_root / rel_path
            mtime = int(full_path.stat().st_mtime * 1000)
            new_list.append(FileContext(path=rel_path, last_modified_ms=mtime))
            
        self.state_service.save_state(new_list)
        return f"Sync complete. Indexed {len(new_list)} files."

    def get_context_summary(self) -> str:
        project_name = self.project_root.name
        files = self.state_service.load_state()
        
        lines = []
        lines.append("<system-reminder>")
        lines.append("PROJECT CONTEXT:")
        lines.append(f"Project: {project_name}")
        lines.append(f"Root: {self.project_root}")
        
        commits = self.git_service.get_recent_commits(5)
        if commits:
            lines.append("\n[NARRATIVE]")
            for commit in commits:
                # Clean up commit message for single-line display
                clean_commit = commit.replace("\n", " ").replace("\r", "")
                lines.append(f"- {clean_commit}")
                
        if files:
            lines.append("\n[STRUCTURE]")
            # Group by directory
            groups = {}
            for f in files:
                p = Path(f.path)
                parent = str(p.parent) if str(p.parent) != "." else ""
                if parent not in groups:
                    groups[parent] = []
                groups[parent].append(p.name)
            
            id_counter = 1
            for parent in sorted(groups.keys()):
                if parent:
                    lines.append(f"{parent}\\")
                
                indent = "  " if parent else ""
                for filename in sorted(groups[parent]):
                    lines.append(f"{indent}{filename}#{id_counter}")
                    id_counter += 1
        else:
            lines.append("\n[STRUCTURE]\n(No index found. Run 'opc sync' to generate context.)")
            
        # Add automated skills
        lines.append("\n[SKILLS]")
        lines.append(self.skill_discovery.generate_skills_manifest())
            
        lines.append("</system-reminder>")
        return "\n".join(lines)

    def handle_session(self):
        summary = self.get_context_summary()
        response = {
            "systemMessage": f"✦ OPC Synced: {self.project_root.name} ✦",
            "hookSpecificOutput": {
                "hookEventName": "SessionStart",
                "additionalContext": summary
            }
        }
        return json.dumps(response)

    def handle_context(self):
        summary = self.get_context_summary()
        response = {
            "hookSpecificOutput": {
                "hookEventName": "BeforeAgent",
                "additionalContext": summary
            }
        }
        return json.dumps(response)
