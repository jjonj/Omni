import shutil
import os

build_dir = r"B:\GDrive\ProjectsG\Omni\OmniSync.Android\app\build"

if os.path.exists(build_dir):
    try:
        shutil.rmtree(build_dir)
        print(f"Successfully deleted: {build_dir}")
    except Exception as e:
        print(f"Error deleting {build_dir}: {e}")
else:
    print(f"Directory not found: {build_dir}")
