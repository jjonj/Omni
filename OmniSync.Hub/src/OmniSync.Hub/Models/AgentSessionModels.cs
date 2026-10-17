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

    /// <summary>What Omni can currently do with a session. Clients render controls from this, never from provider names.</summary>
    public class AgentCapabilities
    {
        public bool CanSend { get; set; }
        /// <summary>Messages sent while a turn is running are queued and delivered when it ends.</summary>
        public bool CanQueue { get; set; }
        /// <summary>Another client is mid-turn and a parallel headless turn can be started anyway.</summary>
        public bool CanSendParallel { get; set; }
        public bool CanInterrupt { get; set; }
        public bool CanAnswerQuestions { get; set; }
        public bool CanApproveTools { get; set; }
        public bool CanChangeModel { get; set; }
        public bool CanObserveLiveEvents { get; set; }
        public bool CanOpenTerminal { get; set; }
        /// <summary>Control backend in use, e.g. "Headless CLI resume".</summary>
        public string Backend { get; set; } = "";
        /// <summary>Human-readable limits of the backend (turn mode, why sending is unavailable, ...).</summary>
        public string Note { get; set; } = "";
    }

    /// <summary>A structured question the agent is waiting on.</summary>
    public class AgentQuestion
    {
        public string Text { get; set; } = "";
        public List<string> Options { get; set; } = new();
    }

    /// <summary>A message the Hub will send when the session's running turn ends.</summary>
    public class AgentQueuedMessage
    {
        public string Id { get; set; } = "";
        public string Text { get; set; } = "";
        public string At { get; set; } = "";
        /// <summary>"after-turn": the Hub sends it when the turn ends. "pc-pickup": waiting for the open PC client's hook.</summary>
        public string Delivery { get; set; } = "after-turn";
    }

    /// <summary>One way a message can be delivered right now (the first option is the default).</summary>
    public record AgentSendOption(string Mode, string Label, string Hint);

    /// <summary>Attention state shared by summaries and details (see docs/AGENT_SCREEN_DEVELOPMENT_PLAN.md).</summary>
    public abstract class AgentSessionStateBase
    {
        public string Id { get; set; } = "";
        public string Provider { get; set; } = "";
        public string Title { get; set; } = "";
        public string SessionId { get; set; } = "";
        public string Cwd { get; set; } = "";
        public string Model { get; set; } = "";
        public string Modified { get; set; } = "";
        /// <summary>working, question, permission, error, limited, idle, starting</summary>
        public string State { get; set; } = "idle";
        public string StateSince { get; set; } = "";
        public string Activity { get; set; } = "";
        public bool LiveElsewhere { get; set; }
        public bool OmniTurn { get; set; }
        public string? Error { get; set; }
        public AgentQuestion? Question { get; set; }
        public List<AgentQueuedMessage> Queued { get; set; } = new();
        /// <summary>Where the session is open on the PC right now ("Claude app", "terminal"), empty if nowhere known.</summary>
        public string OpenIn { get; set; } = "";
        public List<AgentSendOption> SendOptions { get; set; } = new();
        public AgentCapabilities Capabilities { get; set; } = new();
        public string TerminalCommand { get; set; } = "";
        public bool Control { get; set; }
    }

    /// <summary>Lightweight listing entry for the agent attention inbox.</summary>
    public class AgentSessionSummary : AgentSessionStateBase
    {
    }

    /// <summary>Detail payload for one agent session, newest events last.</summary>
    public class AgentSessionDetail : AgentSessionStateBase
    {
        public List<AgentEvent> Events { get; set; } = new();
        public int TotalEvents { get; set; }
        public bool HasMoreBefore { get; set; }
        public int NextBefore { get; set; }
        public int AvailableEvents { get; set; }
        public string Status { get; set; } = "Transcript available";
    }
}
