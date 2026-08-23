# Making GifSlideShowApp a Windows .exe

The app is plain Java (Swing, no third-party libraries), so the whole build is
one script. `jpackage` — part of every JDK since version 14 — bundles the code
**and a private copy of Java** into a Windows program, so the PC you run it on
does **not** need Java installed.

No app source code was changed for any of this; the build just puts the files
where the app already expects them.

---

## Build it

**1. Install a JDK 17 or newer** (JDK 21 is the safe pick):
<https://adoptium.net> → *Temurin 21 (LTS)* → the `.msi` installer. During
setup, turn on **"Set JAVA_HOME variable"** and **"Add to PATH"**.

**2. Open Command Prompt in the project folder** and run:

```bat
build.bat
```

That produces:

```
dist\GifSlideShowApp\
    GifSlideShowApp.exe      <- double-click this
    DancingScript.ttf        <- fonts, copied here automatically
    EBGaramond.ttf
    ...
    quiz-import-sample.csv   <- the sample CSVs
    app\                     <- the program itself
    runtime\                 <- the bundled Java
```

---

## Giving it to your friend

**Copy the whole `dist\GifSlideShowApp` folder** to their PC (zip it, USB stick,
Drive — whatever). They open the folder and double-click `GifSlideShowApp.exe`.

That's it. They don't install Java, they don't install anything.

Two things to keep in mind:

- **Copy the folder, not just the .exe.** The `.exe` on its own won't start —
  the bundled Java lives in the `runtime` folder beside it. About 60–80 MB
  zipped.
- **Fonts must sit next to the .exe**, in that same folder. `build.bat` already
  puts the six `.ttf` files there, and you can drop more `.ttf`/`.otf` files in
  later — they show up in the font list next time the app starts.
- **Have them run it from a normal folder** — Desktop, Documents, Downloads.
  The app writes its `presets` folder next to the `.exe`, so a locked-down
  location like `C:\Program Files` would stop presets from saving.

**First launch shows a blue "Windows protected your PC" box.** That's normal for
any unsigned program: *More info* → *Run anyway*. Only once, and only on their
PC. (Silencing it permanently needs a paid code-signing certificate.)

---

## Optional: a real installer

If you'd rather hand over a setup program (Start-menu shortcut, desktop icon,
entry in "Add or remove programs"):

```bat
build.bat installer
```

This needs the **WiX Toolset 3.14** installed first
(<https://github.com/wixtoolset/wix3/releases>). It produces
`dist\GifSlideShowApp-1.0.exe`.

For just sending the app to a friend, the plain folder above is simpler and has
no font/permission surprises — an install into `C:\Program Files` can't write
its presets folder.

---

## Optional: let GitHub build it for you

If you don't want to install a JDK at all:

1. Repo → **Actions** tab → **Build Windows exe** → **Run workflow**.
2. When it finishes, download the **GifSlideShowApp-windows-portable**
   artifact from the bottom of the run page.
3. Unzip and run `GifSlideShowApp.exe` — fonts are already inside.

It also runs automatically on a `v*` tag (`git tag v1.0 && git push origin v1.0`).

---

## Adding an icon

Put a Windows icon file named **`app.ico`** in the project root before running
`build.bat` — the script picks it up and the `.exe` gets that icon. Without it
Windows shows the default Java icon. It has to be a real `.ico`, not a renamed
`.png`; any online PNG→ICO converter works.

To change the version number, edit `set APP_VERSION=1.0` at the top of
`build.bat`.

---

## Running without packaging

On your own machine, unchanged from before:

```bat
javac -d build\classes src\*.java
java -cp build\classes GifSlideShowApp
```

`build.sh` (macOS/Linux) builds a plain jar — run it from the project folder so
it finds the fonts: `java -jar build/jar/GifSlideShowApp.jar`.

---

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `'javac' is not recognized` | The JDK isn't on PATH. Reinstall Temurin with "Add to PATH" ticked, or set `JAVA_HOME` and reopen Command Prompt. |
| `jpackage was not found` | You have a JRE, or a JDK older than 14. Install JDK 17+. |
| The .exe won't start on the other PC | They copied only the `.exe`. Copy the whole `GifSlideShowApp` folder. |
| Font list is empty / missing fonts | The `.ttf` files must be in the same folder as `GifSlideShowApp.exe`. |
| Presets won't save | The app folder is read-only. Move the folder to Desktop or Documents. |
| Installer build fails, folder build works | WiX 3.14 isn't installed. Use the folder — it works the same. |
