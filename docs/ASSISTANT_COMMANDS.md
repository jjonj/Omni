# Omni assistant commands

This is the first implementation layer toward Jarvis: deterministic commands run before any LLM fallback.

## Source of truth

The product/design source of truth is `OmniNotes/Inbox/Jarvis.md` (the Google Drive / Obsidian Jarvis specification). This document only describes the deterministic command foundation beneath it.

The retired standalone PC voice PoC under `OmniSync.Hub/Voice` and the old "launch Jarvis as a Gemini CLI session" path are not part of the current architecture. The existing Android Jarvis voice/widget naming and `SendJarvisTranscript` remain as input/transport entry points into the shared assistant pipeline.

## Runtime path

`input -> generic modifiers -> deterministic command registry -> executor -> result log`

Current inputs:
- Google Keep voice inbox -> `POST /api/voice-inbox`
- Android Jarvis voice/widget -> local Android commands first, then `SendJarvisTranscript` to the Hub

Generic timing suffixes such as `in one hour` are removed before command matching and stored as execution time metadata.

## Adding a desktop command

Add one class under:

`OmniSync.Hub/src/OmniSync.Hub/Logic/Services/AssistantCommands`

Implement `IAssistantCommand`. Do **not** add the command to a central switch or edit `Program.cs`.

The command assembly is scanned at startup and concrete `IAssistantCommand` implementations register automatically.

Each command declares:
- stable ID
- description
- execution host
- typed argument schema
- queue/staleness policy
- deterministic `TryParse`
- deterministic `ExecuteAsync`

Keep `TryParse` narrow enough that ordinary speech cannot accidentally trigger the command.

Use `AssistantQueuePolicy.SkipBacklog(...)` for commands that should not execute merely because the PC came back online and found an old Keep item.

## Android-local commands

Android reuses the existing Jarvis voice/widget entry point; do not add another voice UI.

Android-local parsing lives in:
- `logic/assistant/AssistantCommandRouter.kt`
- `logic/assistant/AndroidAssistantCommandExecutor.kt`

Only commands that must work while the PC is offline belong here. The first one is `wake.pc`, which uses the existing Wake-on-LAN configuration.

## Current commands

- `media.play_movie(title)` — desktop. Resolves the title through Stremio/Cinemeta, then runs the generalized version of the proven `play_tetris_1080p.ps1` automation: open the movie, click the first visible 1080p source, and enter Stremio fullscreen. It skips startup backlog.
- `wake.pc` — Android. Runs Wake-on-LAN locally, including delayed forms such as `turn on my PC in one hour`.

## Validation

Desktop:
- xUnit: `AssistantCommandFoundationTests`
- running-Hub smoke test: `python TestScripts/System/test_assistant_commands.py`

The smoke test marks the movie request as startup backlog so it validates routing without launching Stremio.

Android:
- `AssistantCommandRouterTest`
- build/deploy with `OmniSync.Android/build_and_deploy.py`
