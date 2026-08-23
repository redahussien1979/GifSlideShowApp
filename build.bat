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

set APP_NAME=GifSlideShowApp
set APP_VERSION=1.0
set MAIN_CLASS=GifSlideShowApp

REM --- Locate the JDK -------------------------------------------------------
if defined JAVA_HOME (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAR=%JAVA_HOME%\bin\jar.exe"
    set "JPACKAGE=%JAVA_HOME%\bin\jpackage.exe"
) else (
    set "JAVAC=javac"
    set "JAR=jar"
    set "JPACKAGE=jpackage"
)

"%JAVAC%" -version >nul 2>&1
if errorlevel 1 (
    echo(
    echo ERROR: No JDK found. Install a JDK 17+ ^(for example Temurin 21^)
    echo        from https://adoptium.net and either add its bin folder to PATH
    echo        or set JAVA_HOME to the JDK folder, then run this script again.
    echo(
    exit /b 1
)

"%JPACKAGE%" --version >nul 2>&1
if errorlevel 1 (
    echo(
    echo ERROR: jpackage was not found next to javac. You are probably using a
    echo        JRE or a JDK older than 14. Install a JDK 17+ from
    echo        https://adoptium.net and try again.
    echo(
    exit /b 1
)

REM --- Clean ----------------------------------------------------------------
echo [1/5] Cleaning...
if exist build rmdir /s /q build
if exist dist  rmdir /s /q dist
mkdir build\classes
mkdir build\jar

REM --- Compile --------------------------------------------------------------
echo [2/5] Compiling...
"%JAVAC%" -encoding UTF-8 -nowarn -d build\classes src\*.java
if errorlevel 1 exit /b 1

REM --- Jar ------------------------------------------------------------------
echo [3/5] Building %APP_NAME%.jar ...
"%JAR%" --create --file build\jar\%APP_NAME%.jar --main-class %MAIN_CLASS% -C build\classes .
if errorlevel 1 exit /b 1

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
if errorlevel 1 exit /b 1

REM --- Fonts and samples, right next to the .exe -----------------------------
echo [5/5] Copying fonts next to the .exe ...
copy /y *.ttf dist\%APP_NAME%\ >nul 2>&1
copy /y *.otf dist\%APP_NAME%\ >nul 2>&1
copy /y *-sample.csv dist\%APP_NAME%\ >nul 2>&1

REM --- Optional installer, built from the folder above ----------------------
if /i "%~1"=="installer" (
    echo Building installer ^(this needs WiX 3.14^)...
    "%JPACKAGE%" --type exe ^
        --name %APP_NAME% ^
        --app-version %APP_VERSION% ^
        --app-image dist\%APP_NAME% ^
        --dest dist ^
        --win-shortcut --win-menu --win-dir-chooser
    if errorlevel 1 exit /b 1
    echo(
    echo Done: dist\%APP_NAME%-%APP_VERSION%.exe
    endlocal
    exit /b 0
)

echo(
echo Done. Give your friend the whole folder:  dist\%APP_NAME%
echo They run:  %APP_NAME%.exe   ^(no Java needed on their PC^)
endlocal
