#!/usr/bin/env sh
# Builds build/jar/GifSlideShowApp.jar (runs anywhere with a JRE 17+).
# On Windows use build.bat instead - that one produces the .exe.
#
# Run the jar from the project folder, so the app finds the .ttf fonts:
#   java -jar build/jar/GifSlideShowApp.jar
set -e
cd "$(dirname "$0")"

APP_NAME=GifSlideShowApp
MAIN_CLASS=GifSlideShowApp

rm -rf build
mkdir -p build/classes build/jar

echo "Compiling..."
javac -encoding UTF-8 -nowarn -d build/classes src/*.java

echo "Building $APP_NAME.jar ..."
jar --create --file "build/jar/$APP_NAME.jar" --main-class "$MAIN_CLASS" -C build/classes .

echo
echo "Done: build/jar/$APP_NAME.jar"
echo "Run it from this folder: java -jar build/jar/$APP_NAME.jar"
