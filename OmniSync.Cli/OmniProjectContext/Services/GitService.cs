using System;
using System.Diagnostics;
using System.Text;

namespace OmniProjectContext.Services;

public class GitService
{
    private readonly string _workingDirectory;

    public GitService(string workingDirectory = null)
    {
        _workingDirectory = workingDirectory;
    }

    // Git notes requirement removed. This service can be expanded for other base git operations if needed.
}
