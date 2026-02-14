You are on windows 11

Always read design.txt first

Use these scripts instead of manual commands:
build_run_omnihub.py (this will kill existing instance, rebuild and then run)
OmniSync.Android/build_and_deploy.py (read for arguments)

Whenever changing hub non-ui code, write a script inside TestScripts modeled on the existing tests to validate your functionality
Whenever making android app changes run build_and_deploy.py and ask the user to validate your changes
If you get compile errors that are not related to the changes you have made, that is because user is working on the project at the same time. in this case just inform the user with aispeak and stop until they say fixed. 
When committing, there may be files in status not related to your changes as the user is working on other things. Try to only commit the files relevant to your task, if a file is relevant for both then do commit it.

Keep the WPF Hub UI and Web Settings UI in sync for features like Hotkeys, but prioritize the WPF version as primary.
When asked to add logging, use the activity log on android and hub, not logcat.

### AI Dialog Auto-Responses

If the user asks you to add a new gemini cli automatic dialog response:
1.  Open `OmniSync.Hub/src/OmniSync.Hub/Logic/Services/HubEventSender.cs`.
2.  Locate the `OnAiCliDialogReceived` method.
3.  Add a check for the `e.Type` (from the CLI) or `e.Prompt` content.
4.  Use `await _aiCliService.SendDialogResponseAsync("response_text", e.Pid);` to respond.
5.  Always include a `Task.Delay` (e.g., 5000ms) if the response is a retry to avoid rapid loops.
6.  You can branch logic based on `e.Prompt.Contains("keyword")` to handle different variants of the same dialog type.

### Junctions

Junction folders are used a couple places:
D:\SSDProjects\Omni\OmniSync.Web\www\worktrees contains subfolders that are junctions used for temporary webpages created elsewhere, folder should be gitignored.
D:\SSDProjects\Omni\OmniSync.Cli\omni-extension is junctioned to C:\Users\crovea\.gemini\extensions\omni to automatically apply updates to the gemini extension when changed in omni.