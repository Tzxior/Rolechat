@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
 echo Virtual environment missing. Run: py -3.13 -m venv .venv
 echo Then: .venv\Scripts\activate and pip install -r requirements.txt
 pause
 exit /b 1
)
".venv\Scripts\python.exe" game_voice_studio.py
