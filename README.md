# mType Editor

mType Editor is a desktop code editor for the mType language. It is built with JavaFX and RichTextFX, and integrates directly with the mType interpreter, language server, debugger, build commands, package tooling, and Git.

## Features

- Workspace explorer with file filtering and tabbed editing.
- mType syntax highlighting, diagnostics, hover information, completions, formatting, rename, go to definition, call hierarchy, references, code lens reference counts, and inlay hints through the mType language server.
- Run the active `.mt` file through the configured mType interpreter.
- Build `.mtproj` or `.mtworkspace` folders as normal builds, libraries, executables, or GUI executables.
- Dependency commands for project trees and "why is this file needed" output.
- Debug panel with breakpoints, start/continue, stop, step over, step into, step out, stack frames, variables, watches, and debug console output.
- Git panel for status, staging, unstaging, discard, commit, push, pull, fetch, branch switching, merge, stash, and history/diff views.
- Bottom panels for Problems, Run, Debug Console, Compile, LSP Log, Git, Call Hierarchy, and References.
- Find in Files with case-sensitive, whole-word, and regex modes.
- Dark mType theme with bundled JetBrains Mono fonts.

## Requirements

- JDK 25
- Maven
- mType toolchain


The default executable paths are:

```text
mType\bin\mType\Release\x64\mType.exe
mType\bin\mtype-language-server\Release\x64\mtype-language-server.exe
mType\bin\mtpm\Release\x64\mtpm.exe
```

You can override the base location with `MTYPE_HOME`, or change paths from `File -> Settings...`.

Settings are stored at:

```text
%USERPROFILE%\.mtype-editor\settings.json
```

## Run From Source

From the repository root:

```powershell
mvn javafx:run
```

To compile without launching the UI:

```powershell
mvn -q -DskipTests compile
```

## Basic Usage

1. Start the editor.
2. Open a folder with `File -> Open Folder...` or `Ctrl+Shift+O`.
3. Open a `.mt` file from the explorer.
4. Use `Run` to run the active file.
5. Use `Build` or `Ctrl+B` when the workspace contains a `.mtproj` or `.mtworkspace`.

## Keyboard Shortcuts

| Shortcut | Action |
| --- | --- |
| `Ctrl+S` | Save active file |
| `Ctrl+Shift+O` | Open folder |
| `Ctrl+,` | Open settings |
| `Shift+Alt+F` | Format document |
| `F12` | Go to definition |
| `Shift+F12` | Find all references |
| `F2` | Rename symbol |
| `Ctrl+Alt+H` | Show call hierarchy |
| `Ctrl+Shift+F` | Find in files |
| `Ctrl+B` | Build |
| `Ctrl+Alt+D` | Show dependencies for current file |
| `Ctrl+Shift+E` | Filter files in explorer |
| `Ctrl+J` | Toggle bottom panel |
| `F5` | Start or continue debugging |
| `Shift+F5` | Stop debugging |
| `F9` | Toggle breakpoint |
| `F10` | Step over |
| `F11` | Step into |
| `Shift+F11` | Step out |

## Project Structure

```text
src/main/java/org/mtype/editor/app        Application startup and layout
src/main/java/org/mtype/editor/ui         Editor, panels, dialogs, Git, debug, search UI
src/main/java/org/mtype/editor/lsp        mType language server bridge
src/main/java/org/mtype/editor/process    Run and build controllers
src/main/java/org/mtype/editor/debug      Debug protocol bridge and state
src/main/java/org/mtype/editor/git        Git integration
src/main/java/org/mtype/editor/workspace  Workspace and settings storage
src/main/resources/css                    Dark theme
```

## Notes

This project is currently focused on Windows development paths, but the JavaFX dependencies include Windows, Linux, and macOS JavaFX graphics classifiers. The mType toolchain paths must still be configured for the machine running the editor.
