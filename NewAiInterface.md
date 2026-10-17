Worked for 34s

I’m GPT-5.6 Sol.

I’d make Omni an agent-control client, with terminal mirroring only as an escape hatch. The nice part is that all three ecosystems are now exposing structured programmatic interfaces: Antigravity has persistent bidirectional stream-json; Claude Code exposes stream-json, resumable/named sessions and structured events; Codex has an especially rich app-server JSON-RPC interface with threads, turns, streaming deltas, approvals, interruption, forking, token usage, etc. 

The architecture I'd explore is:

1. Normalize everything into an Omni “Agent Session” protocol. On the PC, OmniHub runs a provider adapter:

CodexAdapter
ClaudeAdapter
AntigravityAdapter
TerminalAdapter  // fallback

Internally they produce the same events:

SessionStarted
UserMessage
AssistantTextDelta
ReasoningStatus
ToolStarted
ToolOutput
ToolCompleted
FileChanged
ApprovalRequested
QuestionAsked
UsageUpdated
TurnCompleted
Error

Android doesn't know or care whether the underlying process is Codex, Claude, or Antigravity.


2. Make the main screen a conversation/activity timeline, not a terminal.

Something like:

┌─────────────────────────────┐
│ WARTRIBES · Codex       ●   │
│ gpt-5.6-sol · D:\Wartribes  │
├─────────────────────────────┤
│ YOU                         │
│ Fix the tree generation bug │
│                             │
│ CODEX                       │
│ I'll inspect the generator. │
│                             │
│ ▾ Read  TreeGenerator.cpp   │
│ ✓ Search  "GenerateBranch"  │
│                             │
│ ▾ Edited 3 files            │
│   +42  -17                  │
│   [View diff]               │
│                             │
│ ⚠ Wants to run              │
│   UnrealEditor-Cmd ...      │
│                             │
│ [ Deny ]     [ Allow ]      │
├─────────────────────────────┤
│ Message agent...        ➤   │
└─────────────────────────────┘

Most tool chatter should collapse automatically. What matters on a phone is what it's doing, what changed, and whether it needs you.


3. Build an “Attention Inbox.” This might actually be the strongest reason for the feature to exist.

Home screen:

AGENTS

🔴 WARTRIBES / Codex
   Needs approval
   Run Unreal automation tests?
                                   2m

🟡 OmniHub / Claude
   Working
   Refactoring reconnect logic
                                   7m

🟢 Website / Antigravity
   Finished
   Implemented login flow
                                  14m

Then push notifications only for meaningful state transitions:

needs approval

agent asks you a question

task finishes

agent errors

optionally "has been working for 20 min"


This is much better than receiving thousands of ANSI characters.


4. approval/question UI.

approvals should be default be auto approved by the agent itself but in the future we may want an override layer to handle Annoying agents
 Instead of emulating:

Do you want to run this command?
> 1. Yes
  2. Yes, always
  3. No

Omni shows native Android buttons:

Run:
npm test

Working directory:
C:\src\OmniHub

[Deny] [Allow once] [Always allow]

Codex's app-server explicitly exposes approval interactions rather than requiring terminal scraping, which is ideal for this. 


5. Treat command execution as cards. Instead of dumping output into the chat:

✓ dotnet test                       8.3s
  134 passed · 2 failed

  [View output]

Expand it and you get the raw stdout/stderr.

Same for searches:

✓ Searched repository
  "OnCharacterDeath"
  14 matches in 8 files

And edits:

✓ Modified
  TreeGenerator.cpp       +27 -8
  TreeGenerator.h          +4 -1

  [Diff]


6. Still keep “Terminal View.” One button:

⋮ → Raw terminal

Then you see exactly what you're currently considering building.

That solves all the weird cases:

new CLI feature Omni doesn't understand

ncurses/TUI screens

slash commands

authentication

weird third-party tools

provider protocol changed


So you're not giving up terminal streaming. You're demoting it to the compatibility layer.


7. Make OmniHub own the process whenever possible. This is important.

If you start:

codex

in some completely unrelated Windows terminal, Omni can't necessarily magically become another frontend to that exact live interactive process.

But if sessions are launched through OmniHub:

omni codex
omni claude
omni agy

then OmniHub owns stdin/stdout/process lifecycle from the beginning.

You could make tiny aliases so typing codex normally actually invokes your wrapper.

Then:

Windows terminal ─┐
                  ├─ OmniHub AgentSession
Android Omni ─────┘

Both become views/controllers of the same underlying session.


8. Allow seamless PC ↔ phone continuation. You should be able to type a prompt on PC, walk away, see it working on your phone, approve a command there, send another instruction, then return to the PC.

Importantly, the session lives in OmniHub, not in either UI.

Android disconnecting therefore does nothing to it.


9. Expose provider-specific features through capabilities rather than contaminating the common API.

For example:

AgentCapabilities
{
    CanInterrupt,
    CanApproveTools,
    CanFork,
    CanResume,
    CanShowTokenUsage,
    CanChangeModel,
    CanChangePermissionMode,
    CanEnumerateSubagents
}

Omni shows the controls only when supported.

Codex, for example, already exposes thread/start, thread/resume, thread/fork, turn/start, turn/interrupt, streamed item/* events and token usage through app-server. 


10. Eventually add multi-agent orchestration UI. Once you have the abstraction, your phone could show:



WARTRIBES
├── Codex           Working
│   └ Fix foliage crash
│
├── Claude          Waiting
│   └ Review Codex changes
│
└── Antigravity
    ├ Research shader issue       Done
    └ Check UE docs               Working

Antigravity already has explicit subagent lifecycle concepts, so this increasingly maps onto how the underlying systems themselves work. 

The implementation direction I'd choose

Your existing topology actually fits this extremely well:

┌─ Codex app-server
                 │
Android          ├─ Claude stream-json
Omni App         │
    │            ├─ Antigravity stream-json
 SignalR         │
    │            └─ arbitrary PTY
    ▼
 OmniHub
    │
 Session Manager
    │
 Normalized Event Log

The Normalized Event Log is important. Don't send Android ephemeral terminal updates and forget them. Have OmniHub keep something like:

AgentSession
AgentTurn
AgentEvent[]

Then Android can disconnect for 40 minutes, reconnect, request events after sequence 1842, and instantly reconstruct the session.

That also meshes very naturally with the offline/reconnection work you've already been doing in Omni.

One important provider distinction

I'd probably integrate them in this order from an API-design perspective:

Codex: use codex app-server, not terminal parsing. OpenAI describes it as the interface powering rich Codex interfaces such as its VS Code extension. It's essentially made for what you're building. 

Antigravity: its headless interface is unusually convenient here because it supports a long-lived process where you write structured user events to stdin and receive structured events continuously on stdout. 

Claude Code: use its stream-json programmatic mode and session IDs rather than interpreting its TUI. Claude also has its own remote-control feature now, but that's for Anthropic's frontend; you can still build your own normalized interface using its structured CLI mode. 

For Codex specifically, I'd avoid building too tightly against codex exec --json if you're willing to integrate app-server. There have already been reports of the exec --json event schema changing and omitting some internal events, while app-server exposes the richer underlying interaction model. 

The end result I'd aim for is basically “WhatsApp for coding agents, with an activity inspector underneath.” On mobile you mainly communicate, approve, inspect changes, interrupt, and switch between sessions. The actual terminal is there when you need it, but you almost never need to look at it.