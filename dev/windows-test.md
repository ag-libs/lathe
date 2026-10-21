# Windows test of a Lathe pre-release

A manual check of a pre-release on a real Windows machine: install, build, then use Lathe in VS Code (or Cursor) and Zed.
About 30 minutes.
It is human session 1 of the [Multi-OS design](../docs/planned/lathe-multi-os.md#human-validation).

Write down the Windows version (`winver`) and the pre-release version under test.

It needs the pre-release published: on Maven Central, and its `lathe-<version>.vsix` on the GitHub release.

## 1. Install

- **Java 21:** the Temurin 21 LTS MSI from [adoptium.net](https://adoptium.net/temurin/releases/?os=windows&version=21).
  In the installer, enable **Set JAVA_HOME variable** (off by default); **Add to PATH** is on.
  Check in a new `cmd` window: `java -version` prints 21, and `echo %JAVA_HOME%` prints its folder.
- **VS Code** (or Cursor) and, optionally, **Zed**.
- No Maven: the sample project brings the Maven Wrapper.

## 2. Get the sample project

1. Download the repository as a ZIP (GitHub: **Code → Download ZIP**) and extract it.
2. Copy `dev\windows-sample` to a folder whose path has a space and a non-ASCII letter, e.g. `C:\Users\<you>\Lathe Tést\windows-sample`.
3. In `.mvn\extensions.xml`, set `<version>` to the pre-release version.

## 3. Build

In `cmd`, in the sample folder:

```bat
mvnw.cmd -B verify
```

- [ ] The build succeeds.
- [ ] `.lathe\lathe-launcher.cmd` and `.lathe\lathe-sync.cmd` exist.

## 4. VS Code

1. Download `lathe-<version>.vsix` from the pre-release on the [Releases page](https://github.com/ag-libs/lathe/releases).
2. **Extensions → ⋯ → Install from VSIX…**. If the Red Hat Java extension is installed, disable it for this workspace.
3. **File → Open Folder…** the sample folder, then open `app\src\main\java\com\example\app\Main.java`.

- [ ] **Diagnostics:** add `int x = "a";` in `main`; a red squiggle appears. Undo.
- [ ] **Completion:** after `new Greeter().` the list offers `greet`.
- [ ] **Go to definition:** `F12` on `greet` opens `core\...\Greeter.java`.
- [ ] **References:** `Shift`+`F12` on `greet` lists the call in `Main.java`.
- [ ] **Rename:** `F2` on `greet` → `hello`; both files change. Rename it back with `F2`.
- [ ] **Format, CRLF:** in `Greeter.java` click **LF** in the status bar and pick **CRLF**, indent one line wrongly, then `Shift`+`Alt`+`F`. Only that line changes and the file stays CRLF.
- [ ] **No leftover server:** close VS Code. Task Manager → **Details** shows no `java.exe`.

**Cursor:** install the same `.vsix` the same way, then repeat the diagnostics and go-to-definition checks.

## 5. Zed (optional)

Add `.zed\settings.json` in the sample folder:

```json
{
  "lsp": {
    "jdtls": {
      "binary": {
        "path": "cmd.exe",
        "arguments": ["/d", "/s", "/c", ".lathe\\lathe-launcher.cmd"]
      }
    }
  },
  "languages": {
    "Java": { "language_servers": ["jdtls"] }
  }
}
```

Install the **Java** extension in Zed, open the sample folder, and choose **Trust and Continue**.

- [ ] Hover, go to definition into `core`, references, and rename work.
- [ ] After closing Zed, no `java.exe` is left.

## 6. Sync from PowerShell

In a PowerShell window, in the sample folder:

```powershell
.\.lathe\lathe-sync.cmd
```

- [ ] Maven runs and the sync ends with `BUILD SUCCESS`.

## 7. Server log in the desktop console

In `cmd`, in the sample folder:

```bat
chcp 850
.lathe\lathe-launcher.cmd
```

- [ ] The startup lines show the workspace path with `Tést` spelled correctly.
  Stop the server with `Ctrl`+`C`.

## If a step fails

Note the step, then collect:
- VS Code: **View → Output**, channel **Lathe for Java**.
  For more detail, set `LATHE_DEBUG=1` before starting VS Code from `cmd` (`set LATHE_DEBUG=1` then `code .`).
- The `cmd` or PowerShell output of a failed build or sync.
- `java -version` and `winver`.
