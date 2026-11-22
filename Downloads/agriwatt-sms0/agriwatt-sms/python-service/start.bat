@echo off
cd /d "%~dp0"
call .venv\Scripts\activate.bat
echo Service AgriWatt Python sur http://localhost:8000/docs
uvicorn main:app --port 8000
pause
