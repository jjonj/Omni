import json
from pathlib import Path
from typing import List, Optional
import time

from opc.state import StateService, FileContext
from opc.engine import ContextEngine
from opc.git_history import GitHistoryService
from opc.skeleton import SkeletonExtractor
from opc.skills import SkillDiscovery

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
        
        lines = []
        lines.append("<system-reminder>")
        lines.append("OMNI PROJECT CONTEXT:")
        lines.append(f"Project: {project_name}")
        lines.append(f"Root: {self.project_root}")

        # [SKILLS] remains to provide tool awareness
        lines.append("\n[SKILLS]")
        lines.append(self.skill_discovery.generate_skills_manifest())

        lines.append("</system-reminder>")
        return "\n".join(lines)

    def handle_session(self):
        summary = self.get_context_summary()
        response = {
            "systemMessage": f"✦ OPC Active: {self.project_root.name} ✦",
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
