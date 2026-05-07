import sys
from pathlib import Path

# Add src to sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from athena.opc.skeleton import SkeletonExtractor
from athena.opc.git_history import GitHistoryService

def test_skeleton_csharp():
    extractor = SkeletonExtractor()
    content = """
using System;

namespace MyNamespace {
    public class MyClass {
        public void MyMethod() {
            Console.WriteLine("Hello");
        }
    }
    
    public interface IMyInterface {
        string Name { get; set; }
    }
}
"""
    result = extractor.extract(content, ".cs")
    print(f"CSharp result: {result}")
    assert "namespace MyNamespace" in result
    assert "public class MyClass" in result
    assert "public interface IMyInterface" in result
    assert "MyMethod" not in result
    print("SUCCESS: test_skeleton_csharp")

def test_skeleton_python():
    extractor = SkeletonExtractor()
    content = """
import os

class MyClass:
    def my_method(self):
        pass

class AnotherClass(MyBase):
    pass
"""
    result = extractor.extract(content, ".py")
    print(f"Python result: {result}")
    assert "class MyClass" in result
    assert "class AnotherClass(MyBase)" in result
    assert "my_method" not in result
    print("SUCCESS: test_skeleton_python")

if __name__ == "__main__":
    print("Running OPC Port Tests...")
    test_skeleton_csharp()
    test_skeleton_python()
    # GitHistoryService test would need a real git repo, skipping for now as it's a simple wrapper
