@echo off
rem Copies the app's live DB (+ WAL) off the phone. Run from cmd, not PowerShell 5.1 (its > corrupts binary).
setlocal EnableDelayedExpansion
rem %~dp0 must be read before shift changes %0.
set HERE=%~dp0
set PKG=com.jcwear.coach
set DB=jcwear_local_health.db
set OUT=
set FORCE=0
set SERIAL=%ANDROID_SERIAL%

:args
if "%~1"=="" goto argsdone
if "%~1"=="/?" goto usage
if "%~1"=="-h" goto usage
if "%~1"=="-s" (set SERIAL=%~2& shift & shift & goto args)
if "%~1"=="--out" (set OUT=%~2& shift & shift & goto args)
if "%~1"=="--force" (set FORCE=1& shift & goto args)
echo unknown argument %1: see pull-phone-db.bat /? 1>&2
exit /b 1
:argsdone

where adb >nul 2>&1 || (echo adb not found: add platform-tools to PATH 1>&2 & exit /b 1)
set ADB=adb
if not "%SERIAL%"=="" set ADB=adb -s %SERIAL%
%ADB% get-state >nul 2>&1 || (echo no device: connect the phone and accept USB debugging 1>&2 & exit /b 1)

if "%OUT%"=="" (
  for /f %%d in ('powershell -NoProfile -Command Get-Date -Format yyyy-MM-dd') do set OUT=%HERE%ble_verify\phone-%%d.db
)
if exist "%OUT%" if "%FORCE%"=="0" (echo %OUT% exists: pass --force to overwrite 1>&2 & exit /b 1)

set RC=0
rem Stop the app so the DB isn't mid-write; it is relaunched on every exit path below.
%ADB% shell am force-stop %PKG%
%ADB% exec-out run-as %PKG% cat databases/%DB% > "%OUT%"
%ADB% exec-out run-as %PKG% cat databases/%DB%-wal > "%OUT%-wal" 2>nul
for %%f in ("%OUT%-wal") do if %%~zf==0 (del "%OUT%-wal" & echo no -wal file, skipped)
%ADB% exec-out run-as %PKG% cat databases/%DB%-shm > "%OUT%-shm" 2>nul
for %%f in ("%OUT%-shm") do if %%~zf==0 del "%OUT%-shm"

rem run-as errors land in stdout, so the file content is the only reliable check.
python -c "import sqlite3,sys;p=sys.argv[1];h=open(p,'rb').read(16);assert h==b'SQLite format 3\x00','not a SQLite file (run-as refused: install a debug build with gradlew installDebug)';c=sqlite3.connect(p);r=c.execute('PRAGMA integrity_check').fetchone()[0];assert r=='ok','integrity_check: '+r;n,t=c.execute('SELECT count(*),max(device_time) FROM sleep_blocks').fetchone();print('ok: %%s sleep_blocks=%%s newest=%%s'%%(p,n,t))" "%OUT%" || (echo pull failed: %OUT% is not a valid DB 1>&2 & set RC=1)

:relaunch
%ADB% shell am start -n %PKG%/.MainActivity >nul 2>&1 && echo app relaunched || echo RELAUNCH FAILED: run adb shell am start -n %PKG%/.MainActivity
exit /b %RC%

:usage
echo usage: tools\pull-phone-db.bat [-s serial] [--out path] [--force]
echo   Copies %PKG%'s %DB% (+ -wal/-shm) from a debug build on the phone.
echo   Default output: tools\ble_verify\phone-YYYY-MM-DD.db
echo   example: tools\pull-phone-db.bat --force
exit /b 0
