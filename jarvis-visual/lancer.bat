@echo off
cd /d "%~dp0"
set PY=py -3
%PY% --version >nul 2>&1 || set PY=python
%PY% -c "import PySide6" >nul 2>&1 || (
    echo Installation de PySide6 ^(une seule fois, quelques minutes^)...
    %PY% -m pip install --user PySide6
)
rem pythonw = sans fenetre noire. Pour voir les messages d'erreur, remplace "pythonw" par "python".
start "" pythonw jarvis_overlay.py %*
