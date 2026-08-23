@echo off
REM ---------------------------------------------------------------------------
REM  Builds GifSlideShowApp into a Windows .exe.
REM
REM    build.bat              -> dist\GifSlideShowApp\GifSlideShowApp.exe
REM                              (portable folder - copy it anywhere and run)
REM    build.bat installer    -> dist\GifSlideShowApp-1.0.exe
REM                              (double-click installer; needs WiX 3.14)
REM
REM  Requires a JDK 17 or newer (JDK 21 recommended) on PATH, or JAVA_HOME set.
REM  Nothing else - the app has no third-party libraries.
REM
REM  The .ttf fonts and the sample CSVs are copied next to the .exe, which is
REM  the folder the app scans at startup. Drop more fonts in there any time.
REM ---------------------------------------------------------------------------
setlocal

REM Work in the folder this script lives in, so double-clicking it always
REM builds the right project no matter what folder Windows starts us in.
cd /d "%~dp0"

set APP_NAME=GifSlideShowApp
set APP_VERSION=1.0
set MAIN_CLASS=GifSlideShowApp

echo(
echo === Building %APP_NAME% ===
echo Folder: %CD%
echo(

REM --- Locate the JDK -------------------------------------------------------
if defined JAVA_HOME (
    echo Using JAVA_HOME: %JAVA_HOME%
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAR=%JAVA_HOME%\bin\jar.exe"
    set "JPACKAGE=%JAVA_HOME%\bin\jpackage.exe"
) else (
    echo JAVA_HOME is not set - looking for javac on PATH.
    set "JAVAC=javac"
    set "JAR=jar"
    set "JPACKAGE=jpackage"
)

"%JAVAC%" -version >nul 2>&1
if errorlevel 1 goto :no_jdk

echo Compiler found:
"%JAVAC%" -version
echo(

"%JPACKAGE%" --version >nul 2>&1
if errorlevel 1 goto :no_jpackage

REM --- Clean ----------------------------------------------------------------
echo [1/5] Cleaning...
if exist build rmdir /s /q build
if exist dist  rmdir /s /q dist
mkdir build\classes
mkdir build\jar

REM --- Compile --------------------------------------------------------------
echo [2/5] Compiling ^(this takes a minute^)...
"%JAVAC%" -encoding UTF-8 -nowarn -d build\classes src\*.java
if errorlevel 1 goto :compile_failed

REM --- Jar ------------------------------------------------------------------
echo [3/5] Building %APP_NAME%.jar ...
"%JAR%" --create --file build\jar\%APP_NAME%.jar --main-class %MAIN_CLASS% -C build\classes .
if errorlevel 1 goto :failed

REM --- Package into a self-contained app folder ------------------------------
echo [4/5] Building %APP_NAME%.exe ...
set ICON_OPT=
if exist app.ico set ICON_OPT=--icon app.ico

"%JPACKAGE%" --type app-image ^
    --name %APP_NAME% ^
    --app-version %APP_VERSION% ^
    --input build\jar ^
    --main-jar %APP_NAME%.jar ^
    --main-class %MAIN_CLASS% ^
    --dest dist ^
    %ICON_OPT%
if errorlevel 1 goto :failed

REM --- Fonts and samples, right next to the .exe -----------------------------
echo [5/5] Copying fonts next to the .exe ...
copy /y *.ttf dist\%APP_NAME%\ >nul 2>&1
copy /y *.otf dist\%APP_NAME%\ >nul 2>&1
copy /y *-sample.csv dist\%APP_NAME%\ >nul 2>&1

REM --- Optional installer, built from the folder above ----------------------
if /i "%~1"=="installer" goto :installer

echo(
echo ===========================================================
echo  Done.
echo(
echo  Give your friend the whole folder:  dist\%APP_NAME%
echo  They run:  %APP_NAME%.exe   ^(no Java needed on their PC^)
echo ===========================================================
goto :end

:installer
echo(
echo Building installer ^(this needs WiX 3.14^)...
"%JPACKAGE%" --type exe ^
    --name %APP_NAME% ^
    --app-version %APP_VERSION% ^
    --app-image dist\%APP_NAME% ^
    --dest dist ^
    --win-shortcut --win-menu --win-dir-chooser
if errorlevel 1 goto :failed
echo(
echo ===========================================================
echo  Done:  dist\%APP_NAME%-%APP_VERSION%.exe
echo ===========================================================
goto :end

REM --- Failure messages -----------------------------------------------------
:no_jdk
echo(
echo ***********************************************************
echo  PROBLEM: No Java compiler (javac) found on this PC.
echo(
echo  Fix it like this:
echo    1. Go to  https://adoptium.net
echo    2. Download "Temurin 21 (LTS)" for Windows x64 - the .msi
echo    3. Run the installer. On the "Custom Setup" screen, click
echo       the two greyed-out items and choose "Will be installed
echo       on local hard drive":
echo          - Add to PATH
echo          - Set JAVA_HOME variable
echo    4. Close this window, open a NEW one, run build.bat again.
echo(
echo  Note: having "Java" already installed is not enough - that is
echo  usually the JRE, which cannot compile. You need the JDK.
echo ***********************************************************
goto :end_fail

:no_jpackage
echo(
echo ***********************************************************
echo  PROBLEM: javac works, but jpackage is missing.
echo(
echo  That means your Java is older than version 14 (jpackage was
echo  added in 14). Install "Temurin 21 (LTS)" from
echo  https://adoptium.net and run build.bat again.
echo(
echo  Your current version is printed above.
echo ***********************************************************
goto :end_fail

:compile_failed
echo(
echo ***********************************************************
echo  PROBLEM: the Java code did not compile.
echo  The compiler errors are printed above this message.
echo ***********************************************************
goto :end_fail

:failed
echo(
echo ***********************************************************
echo  PROBLEM: the build failed. Details are printed above.
echo ***********************************************************
goto :end_fail

:end
call :wait_for_key
endlocal
exit /b 0

:end_fail
call :wait_for_key
endlocal
exit /b 1

REM Hold the window open so a double-click doesn't close it before the
REM message above can be read. Skipped on a build server (CI is set there),
REM where there is no keyboard and pausing would hang the build.
:wait_for_key
if defined CI exit /b 0
if defined GITHUB_ACTIONS exit /b 0
echo(
echo Press any key to close this window.
pause >nul
exit /b 0
