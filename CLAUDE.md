# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A JavaFX desktop IDE for the **mType** programming language. mType itself lives at `C:\matan\mType\` and ships three binaries this editor drives as external processes:

- Interpreter: `C:\matan\mType\bin\mType\Release\x64\mType.exe` — invoked as `mType.exe <file.mt>`
- Language server: `C:\matan\mType\bin\mtype-language-server\Release\x64\mtype-language-server.exe` — invoked with `--stdio`, speaks LSP over JSON-RPC
- Package manager: `mtpm.exe` (under `C:\matan\mType\bin\mtpm\Release\x64\`) — not yet wired

Defaults for these paths live in `WorkspaceSettings.Toolchain.defaults()`. An `MTYPE_HOME` env var or `<workspace>/.editor/settings.json` overrides them.

## Build & run

```powershell
mvn clean compile                # build, no tests
mvn javafx:run                   # launch via the javafx-maven-plugin
```

**IntelliJ run config**: main class must be `org.mtype.editor.app.Launcher` — **not** `EditorApp`. The JVM main launcher rejects classes extending `javafx.application.Application` when JavaFX is on the classpath (which is how Maven resolves it), so `Launcher` is a trivial non-`Application` shim that calls `EditorApp.main()`.

## Critical version pin

**JavaFX is pinned to 21.0.5**, not the latest. RichTextFX 0.11.5 overrides `TextFlow.getUnderlineShape(int,int)` which became `final` in JavaFX 24+, so JavaFX ≥ 24 throws `IncompatibleClassChangeError` on first paint of a `CodeArea`. Do not bump `javafx.version` past 23.x without first verifying a RichTextFX release that supports it.

## Architecture

### Service wiring

`EditorApp.start()` builds a single `AppContext` holding the shared services (`Workspace`, `LspBridge`, `RunController`, `EditorTabPane`, `OutputPane`, `StatusBar`, `WorkspaceTreeView`). Every UI component takes `AppContext` in its constructor and calls back through it. `AppContext.openWorkspace(Workspace)` is the single entrypoint that swaps the workspace: stops the previous LSP, closes tabs, swaps the tree, starts the new LSP.

### LSP integration (`org.mtype.editor.lsp`)

`LspBridge` owns the child `Process` plus the lsp4j `Launcher<LanguageServer>`. The lsp4j reader runs on its own thread, so every `MTypeLanguageClient` callback (diagnostics, log, message) MUST `Platform.runLater` before touching UI. Outgoing requests return `CompletableFuture` — chain `.thenAcceptAsync(x, Platform::runLater)` so completion handlers land on the FX thread.

Supported LSP features today: `initialize/initialized`, `textDocument/{didOpen,didChange,didClose,publishDiagnostics,completion,hover,definition,formatting,prepareRename,rename,prepareCallHierarchy}`, `callHierarchy/{incomingCalls,outgoingCalls}`. Document sync is **full content** (not incremental) — simpler and the mType server accepts it.

`Positions.offset(text, line, char)` is the **only** correct LSP↔CodeArea position converter. `LspEdits.applyToCodeArea` / `applyWorkspaceEdit` are the **only** places that mutate documents from server responses; they sort edits descending by offset so earlier positions stay valid. WorkspaceEdits modify open tabs via `CodeArea.replaceText`; closed files are read+rewritten on disk.

### Document model & version drift

Every `EditorTab` owns a monotonically increasing `AtomicInteger version` per the LSP spec. `scheduleDidChange()` debounces 200 ms and calls `version.incrementAndGet()` — versions sent to the server must strictly increase per document or the server will reject the change.

### Syntax highlighting (`org.mtype.editor.syntax`)

`MTypeTokenizer` is one compiled `Pattern` with named groups, evaluated in priority order: comments → strings → annotation → constant/member → keyword/modifier → primitive → function (lowercase ident followed by `(`) → type (capitalized ident) → number → bracket → operator → punct. Style class names live in `MTypeStyles` and the CSS in `src/main/resources/css/mtype-dark.css`.

**CSS specificity matters**: token rules use `.styled-text-area .text.mt-keyword` (specificity 3) so they win over the base `.styled-text-area .text { -fx-fill }` (specificity 2). Lower-specificity rules like `.mt-keyword` alone get clobbered.

### Style/diagnostic composition

`EditorTab` keeps `lastTokenSpans` (tokenizer output) and `lastDiagnosticSpans` (diagnostic underlines) separate, then `applyCombinedStyles()` overlays them via `StyleSpans.overlay`. After any operation that calls `replaceText` (format, rename), `lastDiagnosticSpans` becomes stale (wrong offsets) — code that mutates the document MUST set `lastDiagnosticSpans = null` and call `applyHighlightingNow()` synchronously, otherwise styles flicker or drop entirely. See `formatDocument()` and `renameAtCaret()` for the pattern.

### Threading rules

- **FX thread only**: scene-graph mutation, `setStyleSpans`, `ContextMenu`/`Tooltip` show, tab manipulation, button enable/disable.
- **`BG_EXEC` (static 2-thread scheduled pool in `EditorTab`)**: debounced tokenize, debounced `didChange`, debounced completion request. Each task hops back via `Platform.runLater` before touching UI.
- **`StreamPump`** (daemon threads, one per stdout/stderr): pumps child-process output line-by-line. **Must drain unconditionally** — failing to read either pipe deadlocks the child on a full buffer.
- **Process shutdown order**: stop tokenizer/exec tasks → LSP `shutdown`/`exit` → kill run process → `Platform.exit()`.
- Never `Future.get()` on the FX thread; use `process.onExit()` not `waitFor()`.

### Fonts

`EditorApp.loadBundledFonts()` calls `Font.loadFont` on the four JetBrains Mono TTFs in `src/main/resources/fonts/` at startup. JavaFX can't see system fonts unless they're OS-installed; bundling avoids that requirement. The CSS font stack is `"JetBrains Mono", "Cascadia Code", "Cascadia Mono", "Consolas", monospace`.

### File-tree icons (`IconFactory`)

JavaFX can't render SVG. The VS Code extension's `.svg` icons at `C:\matan\mType\mtype-vscode-extension\icons\` are recreated as JavaFX nodes: folder icons paste the SVG `d=` attribute into `SVGPath`, file icons use `Text("M")` with a `LinearGradient` fill matching the original gradient colors. The tree cell listens to its `TreeItem.expandedProperty` and swaps closed↔open folder icons live.

### Persistent settings

`SettingsStore.load(workspaceRoot)` reads `<root>/.editor/settings.json` via Gson and falls back to `WorkspaceSettings.defaults()` if absent. The file is **never auto-written** — only the explicit `SettingsStore.save()` writes — so the editor doesn't pollute third-party workspaces.

## Things that aren't here yet

Git integration (planned via JGit) and `mtpm` package manager hookup are deferred to v2. The `OutputPane` is intentionally a `TabPane` so a `Packages` tab can drop in next to Run/LSP Log/Call Hierarchy without UI rewiring. Reuse `ProcessRunner` + `StreamPump` for any new external process integration.
