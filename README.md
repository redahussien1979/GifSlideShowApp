# GifSlideShowApp

A Java/Swing desktop tool for building animated GIF slideshows.

## Making a Windows .exe

With a JDK 17+ installed, run:

```bat
build.bat
```

You get `dist\GifSlideShowApp\GifSlideShowApp.exe`, with the fonts and a private
Java runtime alongside it. Copy that whole folder to any Windows PC and
double-click the `.exe` — no Java needed there.

Details, installer builds and troubleshooting: **[BUILD.md](BUILD.md)**.

## Running from source

```bat
javac -d build\classes src\*.java
java -cp build\classes GifSlideShowApp
```
