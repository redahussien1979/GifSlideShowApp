@echo off
rem Runs DiagnoseExport on a video without needing java or ffmpeg on PATH.
rem   tools\diagnose.bat portrait-slideshow-3951.mp4
setlocal enabledelayedexpansion

if "%~1"=="" (
  echo usage: tools\diagnose.bat ^<video file^>
  exit /b 1
)
if not exist "%~1" (
  echo No such file: %~1
  exit /b 1
)

rem ---- find a Java launcher -------------------------------------------------
set "JAVA_EXE="
where java >nul 2>nul && set "JAVA_EXE=java"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
rem IntelliJ / JetBrains bundled runtimes
if not defined JAVA_EXE for /d %%D in ("%ProgramFiles%\JetBrains\*") do if exist "%%D\jbr\bin\java.exe" set "JAVA_EXE=%%D\jbr\bin\java.exe"
if not defined JAVA_EXE for /d %%D in ("%ProgramFiles(x86)%\JetBrains\*") do if exist "%%D\jbr\bin\java.exe" set "JAVA_EXE=%%D\jbr\bin\java.exe"
if not defined JAVA_EXE for /d %%D in ("%LOCALAPPDATA%\JetBrains\Toolbox\apps\*\*\*") do if exist "%%D\jbr\bin\java.exe" set "JAVA_EXE=%%D\jbr\bin\java.exe"
if not defined JAVA_EXE for /d %%D in ("%LOCALAPPDATA%\Programs\*") do if exist "%%D\jbr\bin\java.exe" set "JAVA_EXE=%%D\jbr\bin\java.exe"
rem ordinary JDK installs
if not defined JAVA_EXE for /d %%D in ("%ProgramFiles%\Java\*") do if exist "%%D\bin\java.exe" set "JAVA_EXE=%%D\bin\java.exe"
if not defined JAVA_EXE for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\*") do if exist "%%D\bin\java.exe" set "JAVA_EXE=%%D\bin\java.exe"
if not defined JAVA_EXE for /d %%D in ("%ProgramFiles%\Microsoft\jdk*") do if exist "%%D\bin\java.exe" set "JAVA_EXE=%%D\bin\java.exe"

if not defined JAVA_EXE (
  echo Could not find java.exe.
  echo Open tools\DiagnoseExport.java in IntelliJ and run it from there instead,
  echo or set JAVA_HOME to a JDK and run this again.
  exit /b 1
)

rem ---- find ffmpeg ---------------------------------------------------------
set "FFMPEG_EXE=ffmpeg"
where ffmpeg >nul 2>nul
if errorlevel 1 (
  for %%P in ("%ProgramFiles%\ffmpeg\bin\ffmpeg.exe" "C:\ffmpeg\bin\ffmpeg.exe" "%LOCALAPPDATA%\ffmpeg\bin\ffmpeg.exe") do (
    if exist %%P set "FFMPEG_EXE=%%~P"
  )
)

echo Using java   : !JAVA_EXE!
echo Using ffmpeg : !FFMPEG_EXE!
echo.
"!JAVA_EXE!" "%~dp0DiagnoseExport.java" "%~1" "!FFMPEG_EXE!"
endlocal
