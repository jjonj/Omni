using System;
using System.Collections.Generic;

namespace OmniSync.Hub.Models
{
    /// <summary>A normalized event from an agent transcript (Codex, Claude, Antigravity).</summary>
    public class AgentEvent
    {
        public string Kind { get; set; } = "output"; // user, assistant, tool, output, command, status
        public string Title { get; set; } = "";
        public string Text { get; set; } = "";
        public string Time { get; set; } = "";
        public string? Result { get; set; }
        public bool ResultError { get; set; }
    }

    /// <summary>Lightweight listing entry for the agent attention inbox.</summary>
    public class AgentSessionSummary
    {
        public string Id { get; set; } = "";
        public string Provider { get; set; } = "";
        public string Title { get; set; } = "";
        public string Modified { get; set; } = "";
        public string Cwd { get; set; } = "";
        public string Model { get; set; } = "";
        public bool Control { get; set; }
    }

    /// <summary>Detail payload for one agent session, newest events last.</summary>
    public class AgentSessionDetail
    {
        public string Id { get; set; } = "";
        public string Provider { get; set; } = "";
        public string Title { get; set; } = "";
        public string SessionId { get; set; } = "";
        public string Cwd { get; set; } = "";
        public string Model { get; set; } = "";
        public string Modified { get; set; } = "";
        public List<AgentEvent> Events { get; set; } = new();
        public int TotalEvents { get; set; }
        public bool HasMoreBefore { get; set; }
        public int NextBefore { get; set; }
        public int AvailableEvents { get; set; }
        public string Status { get; set; } = "Transcript available";
        public bool Control { get; set; }
    }
}
