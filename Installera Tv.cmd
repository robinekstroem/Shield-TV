@echo off
rem Bygger och installerar Tv for Windows. Dubbelklicka pa filen.
rem Arbetet gors av windows\verktyg\installera.ps1.
title Tv
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\verktyg\installera.ps1"
echo.
pause
