using System;
using System.IO;

namespace OmniProjectContext.Services;

public class StateService
{
    private readonly string _rootPath;
    private readonly string _statePath;

    public StateService(string rootPath)
    {
        _rootPath = rootPath;
        _statePath = Path.Combine(rootPath, ".omni", "projectcontext");
    }

    public void Initialize()
    {
        if (!Directory.Exists(_statePath))
        {
            Directory.CreateDirectory(_statePath);
        }
    }

    public string GetStatePath() => _statePath;
}
