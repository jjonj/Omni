import subprocess
from typing import List, Optional
from pathlib import Path

class GitHistoryService:
    """
    Python implementation of the C# GitHistoryService.
    Retrieves recent commit messages.
    """
    
    def __init__(self, working_directory: Optional[str] = None):
        self.working_directory = working_directory

    def get_recent_commits(self, count: int) -> List[str]:
        commits = []
        try:
            # git log -n count --format="%B%n---"
            cmd = ["git", "log", "-n", str(count), "--format=%B%n---"]
            
            result = subprocess.run(
                cmd, 
                capture_output=True, 
                text=True, 
                cwd=self.working_directory,
                encoding='utf-8',
                errors='ignore'
            )
            
            if result.returncode == 0 and result.stdout:
                # Split by the "---" separator we added
                parts = result.stdout.split("\n---\n")
                for part in parts:
                    trimmed = part.strip()
                    if trimmed:
                        commits.append(trimmed)
                        
        except Exception:
            # Silently fail as per C# original
            pass
            
        return commits
