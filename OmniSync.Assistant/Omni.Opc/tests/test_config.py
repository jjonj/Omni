import os
import sys
from pathlib import Path
import shutil
import tempfile

# Add src to sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

import athena.core.config

def test_get_project_root_discover_athena():
    # We use a temp directory that is NOT a child of any existing .athena or pyproject.toml
    # tempfile.mkdtemp() usually does this.
    tmp_dir = Path(tempfile.mkdtemp()).resolve()
    try:
        project_dir = tmp_dir / "project"
        project_dir.mkdir()
        athena_dir = project_dir / ".athena"
        athena_dir.mkdir()
        
        # To truly isolate, we must ensure we don't accidentally find the real root
        old_cwd = os.getcwd()
        os.chdir(project_dir)
        try:
            athena.core.config._PROJECT_ROOT_CACHE = None
            # Mock __file__ to be inside the temp dir to prevent parent traversal to real root
            # But get_project_root uses Path(__file__) which is athena/core/config.py
            # So it will always find the repo root if it traverses high enough.
            # We want to test that it finds .athena in the CURRENT directory first.
            
            # Actually get_project_root in config.py prioritizes parents of config.py for pyproject.toml
            # but it should probably prioritize .athena_root or .athena in the parent hierarchy of CWD too?
            
            root = athena.core.config.get_project_root()
            print(f"Testing .athena discovery: Expected {project_dir}, got {root}")
            assert root.resolve() == project_dir.resolve()
            print("SUCCESS: .athena discovery works.")
        finally:
            os.chdir(old_cwd)
    finally:
        shutil.rmtree(tmp_dir)

def test_get_project_root_discover_omni():
    tmp_dir = Path(tempfile.mkdtemp()).resolve()
    try:
        project_dir = tmp_dir / "omni_project"
        project_dir.mkdir()
        omni_dir = project_dir / ".omni"
        omni_dir.mkdir()
        
        old_cwd = os.getcwd()
        os.chdir(project_dir)
        try:
            athena.core.config._PROJECT_ROOT_CACHE = None
            root = athena.core.config.get_project_root()
            print(f"Testing .omni discovery: Expected {project_dir}, got {root}")
            assert root.resolve() == project_dir.resolve()
            print("SUCCESS: .omni discovery works.")
        finally:
            os.chdir(old_cwd)
    finally:
        shutil.rmtree(tmp_dir)

if __name__ == "__main__":
    print("Running tests...")
    # NOTE: These will fail because the current get_project_root starts searching from Path(__file__)
    # which is inside the submodule, so it will always find the submodule root.
    # We want to change the logic to also check parents of CWD.
    
    try:
        test_get_project_root_discover_athena()
    except Exception as e:
        print(f"FAILED: test_get_project_root_discover_athena: {e}")
    
    try:
        test_get_project_root_discover_omni()
    except Exception as e:
        print(f"FAILED: test_get_project_root_discover_omni: {e}")
