You are on windows 11

Always read design.txt first

Use these scripts instead of manual commands:
run_omnihub.py (this will kill existing instance, rebuild and then run)
OmniSync.Android/build_and_deploy.py (read for arguments)

Whenever changing hub non-ui code, write a script inside TestScripts modeled on the existing tests to validate your functionality
Whenever making android app changes run build_and_deploy.py and ask the user to validate your changes
If you get compile errors that are not related to the changes you have made, that is because user is working on the project at the same time. in this case just inform the user with aispeak and stop until they say fixed. 
When committing, there may be files in status not related to your changes as the user is working on other things. Try to only commit the files relevant to your task, if a file is relevant for both then do commit it.

If told to do a megasession then read Tasks.txt