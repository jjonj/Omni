@echo off
title OMNI_GEMINI_INTERACTIVE
cd /d "D:\SSDProjects"
node "D:\\SSDProjects\\Tools\\gemini-cli\\bundle\\gemini.js" --workspace "D:\SSDProjects"
if %errorlevel% neq 0 pause
