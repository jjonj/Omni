using Xunit;
using OmniProjectContext.Services;
using System.IO;
using System;

namespace OmniProjectContext.Tests;

public class SkeletonExtractorTests
{
    [Fact]
    public void Extract_CSharp_ShouldReturnSignatures()
    {
        string code = @"
using System;
namespace Test;
public class MyClass {
    public void MyMethod(int x) {
        // body
    }
}";
        var extractor = new SkeletonExtractor();
        var result = extractor.Extract(code, ".cs");
        
        Assert.Contains("public class MyClass", result);
        Assert.Contains("public void MyMethod(int x)", result);
        Assert.DoesNotContain("// body", result);
    }

    [Fact]
    public void Extract_Python_ShouldReturnSignatures()
    {
        string code = @"
class MyClass:
    def my_method(self, x):
        # body
        pass";
        var extractor = new SkeletonExtractor();
        var result = extractor.Extract(code, ".py");
        
        Assert.Contains("class MyClass:", result);
        Assert.Contains("def my_method(self, x):", result);
        Assert.DoesNotContain("# body", result);
    }
}
