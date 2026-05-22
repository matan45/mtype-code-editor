# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A JavaFX desktop IDE for the **mType** programming language. mType itself lives at `C:\matan\mType\` and ships three binaries this editor drives as external processes:

- Interpreter: `C:\matan\mType\bin\mType\Release\x64\mType.exe` — invoked as `mType.exe <file.mt>`
- Language server: `C:\matan\mType\bin\mtype-language-server\Release\x64\mtype-language-server.exe` — invoked with `--stdio`, speaks LSP over JSON-RPC
- Package manager: `mtpm.exe` (under `C:\matan\mType\bin\mtpm\Release\x64\`) — not yet wired

Defaults for these paths live in `WorkspaceSettings.Toolchain.defaults()`. An `MTYPE_HOME` env var or the global settings file overrides them.

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

`EditorApp.start()` builds a single `AppContext` holding the shared services (`Workspace`, `LspBridge`, `RunController`, `EditorTabPane`, `OutputPane`, `StatusBar`, `WorkspaceTreeView`, `GitChangesView`, `DiagnosticsBus`, `WorkspaceSettings`). Every UI component takes `AppContext` in its constructor and calls back through it. `AppContext.openWorkspace(Workspace)` is the single entrypoint that swaps the workspace: stops the previous LSP, closes tabs, swaps the tree, refreshes git status, starts the new LSP.

### Settings live in AppContext, not Workspace

Settings are **user-global** at `%USERPROFILE%\.mtype-editor\settings.json` (not per-workspace). Loaded once by `SettingsStore.load()` in `EditorApp.start()` **before** any of `LspBridge` / `RunController` / `EditorTab` is constructed, and stored on `AppContext`. Callers read `ctx.getSettings().toolchain.languageServer` etc. `Workspace` is now just a path holder — it no longer carries settings.

Most fields apply only on app restart (font family / font size / theme / LSP path). Two exceptions read fresh at use time:
- `editor.formatOnSave` — checked in `EditorTab.save()` each save
- `toolchain.interpreter` — read per Run in `RunController.run(Path)`

### LSP integration (`org.mtype.editor.lsp`)

`LspBridge` owns the child `Process` plus the lsp4j `Launcher<LanguageServer>`. The lsp4j reader runs on its own thread, so every `MTypeLanguageClient` callback (diagnostics, log, message) MUST `Platform.runLater` before touching UI. Outgoing requests return `CompletableFuture` — chain `.thenAcceptAsync(x, Platform::runLater)` so completion handlers land on the FX thread.

LSP features wired today: `initialize/initialized`, `textDocument/{didOpen,didChange,didClose,publishDiagnostics,completion,hover,definition,references,formatting,codeAction,codeLens,prepareRename,rename,prepareCallHierarchy}`, `callHierarchy/{incomingCalls,outgoingCalls}`, `workspace/executeCommand`. Document sync is **full content** (not incremental) — simpler and the mType server accepts it.

`LspBridge` tracks a `session` long that's incremented on every start/stop. Async handlers (initialize callback, completion responses, etc.) check `startSession == session` before mutating state — this prevents responses from a previously-stopped LSP process from leaking into the current state when the user switches workspaces.

`Positions.offset(text, line, char)` is the **only** correct LSP↔CodeArea position converter. `LspEdits.applyToCodeArea` / `applyWorkspaceEdit` are the **only** places that mutate documents from server responses; they sort edits descending by offset so earlier positions stay valid. WorkspaceEdits modify open tabs via `CodeArea.replaceText`; closed files are read+rewritten on disk.

### Diagnostics flow (one bus, many subscribers)

`DiagnosticsBus` on `AppContext` is the single source of truth. `MTypeLanguageClient.publishDiagnostics` forwards each push to the bus, then the bus broadcasts to all subscribers on the FX thread. Subscribers:
- `DiagnosticsRenderer` → builds overlay `StyleSpans` for squiggles and a `Map<line, severity>` for the gutter
- `ProblemsPane` → adds/removes rows in the bottom Problems tab
- `OutputPane.problemsTab` label → live count "Problems (n)"

**Windows path mismatch fallback**: the mType server sometimes normalizes URIs to lowercase drive letters, and `Path.equals()` is case-sensitive even on case-insensitive Windows FS. If `tabPane.findByPath(path)` misses, the renderer falls back to `tabPane.openTabs()` and matches case-insensitively on the absolute path. Without that fallback the squiggle silently disappears.

### Document model & version drift

Every `EditorTab` owns a monotonically increasing `AtomicInteger version` per the LSP spec. `scheduleDidChange()` debounces 200 ms and calls `version.incrementAndGet()` — versions sent to the server must strictly increase per document or the server will reject the change. `openedLspSession` on the tab tracks which LSP session opened it; `onLspReady()` re-sends `didOpen` only if the session changed (e.g., after an LSP restart).

### Syntax highlighting (`org.mtype.editor.syntax`)

`MTypeTokenizer` is one compiled `Pattern` with named groups, evaluated in priority order: comments → strings → annotation → constant/member → keyword/modifier → primitive → function (lowercase ident followed by `(`) → type (capitalized ident) → number → bracket → operator → punct. Style class names live in `MTypeStyles` and the CSS in `src/main/resources/css/mtype-dark.css`.

**CSS specificity matters**: token rules use `.styled-text-area .text.mt-keyword` (specificity 3) so they win over the base `.styled-text-area .text { -fx-fill }` (specificity 2). Lower-specificity rules like `.mt-keyword` alone get clobbered.

### Style / diagnostic composition

`EditorTab` keeps `lastTokenSpans` (tokenizer output) and `lastDiagnosticSpans` (diagnostic underlines) separate, then `applyCombinedStyles()` overlays them via `StyleSpans.overlay`. After any operation that calls `replaceText` (format, rename, code action), `lastDiagnosticSpans` becomes stale (wrong offsets) — code that mutates the document MUST set `lastDiagnosticSpans = null` and call `applyHighlightingNow()` synchronously, otherwise styles flicker or drop entirely. See `formatDocument()`, `renameAtCaret()`, and the code-action apply path for the pattern.

`DiagnosticsRenderer` also seeds an empty span of length `max(textLen, 1)` when clearing diagnostics — `StyleSpansBuilder.create()` throws `"No spans have been added"` on a totally-empty builder.

### Gutter graphics (`EditorTab.paragraphGraphic`)

Each paragraph's gutter is composed by `paragraphGraphic(int paragraphIndex)`:
1. **Line number** (`Label.lineno`)
2. **Diagnostic marker** — colored bar before the line number, driven by `diagnosticsByLine: Map<Integer, DiagnosticSeverity>`. `DiagnosticsRenderer.apply()` computes the worst severity per source line and calls `EditorTab.applyDiagnosticLines(...)`.
3. **Code-lens row** (when present) — sits ABOVE the line number in a VBox; paragraph also gets the `mt-code-lens-paragraph` style class which adds `-fx-padding: 19 0 0 0` so the line text drops below the lens row.

Changing `diagnosticsByLine` or `codeLensByParagraph` calls `codeArea.setParagraphGraphicFactory(this::paragraphGraphic)` again — that's how RichTextFX gets told to re-render affected gutters.

### Code Lens / References / Code Actions UX gotchas

- **Quick Fix submenu**: trigger via the `MOUSE_PRESSED` handler on right-click (before the context menu shows) — **do not** use `setOnShowing` on a `Menu` submenu, it re-fires on every hover and clobbers freshly-fetched actions back to "Loading...". When the async LSP response arrives, if the submenu is currently shown, do `hide()` + `Platform.runLater(show)` — JavaFX `Menu` won't re-layout a visible popup when items change.
- **Right-click caret positioning**: in `MOUSE_PRESSED` for `SECONDARY`, call `codeArea.hit(x, y).getInsertionIndex()` and `moveTo()` so the rename / go-to-def / code-action targets the symbol under the cursor, not the previously-selected one.
- **Quick Fix context.diagnostics**: per LSP spec it should be diagnostics that intersect the action's range. We currently filter via `diagnosticsContainingLine(caretLine)`. Server's `CodeActionHandler` only generates fixes for diagnostics in the context — clicking somewhere without a squiggle returns 0 actions.

### Threading rules

- **FX thread only**: scene-graph mutation, `setStyleSpans`, `ContextMenu`/`Tooltip` show, tab manipulation, button enable/disable.
- **`BG_EXEC` (static 2-thread scheduled pool in `EditorTab`)**: debounced tokenize, debounced `didChange`, debounced completion request, debounced code-lens refresh. Each task hops back via `Platform.runLater` before touching UI.
- **`StreamPump`** (daemon threads, one per stdout/stderr): pumps child-process output line-by-line. **Must drain unconditionally** — failing to read either pipe deadlocks the child on a full buffer.
- **Process shutdown order**: stop tokenizer/exec tasks → LSP `shutdown`/`exit` → kill run process → `Platform.exit()`.
- Never `Future.get()` on the FX thread; use `process.onExit()` not `waitFor()`.

### Fonts

`EditorApp.loadBundledFonts()` calls `Font.loadFont` on the four JetBrains Mono TTFs in `src/main/resources/fonts/` at startup. JavaFX can't see system fonts unless they're OS-installed; bundling avoids that requirement. The CSS font stack is `"JetBrains Mono", "Cascadia Code", "Cascadia Mono", "Consolas", monospace`.

`EditorTab.applyFontFromSettings()` reads `settings.editor.fontFamily/fontSize` and sets `codeArea.setStyle("-fx-font-family: ...; -fx-font-size: ...;")` per tab. Inline styles override CSS class rules and inherit to child `Text` nodes, so font changes propagate to every tokenized span. **Already-open tabs do not pick up font changes** — only newly-opened ones.

The Settings dialog's font picker filters `Font.getFamilies()` to monospaced families by rendering `"MMMMM"` and `"iiiii"` at 14 pt and comparing widths (`looksMonospaced` in `SettingsDialog`).

### File-tree icons (`IconFactory`)

JavaFX can't render SVG. The VS Code extension's `.svg` icons at `C:\matan\mType\mtype-vscode-extension\icons\` are recreated as JavaFX nodes: folder icons paste the SVG `d=` attribute into `SVGPath`, file icons use `Text("M")` with a `LinearGradient` fill matching the original gradient colors. The tree cell listens to its `TreeItem.expandedProperty` and swaps closed↔open folder icons live.

Filename matching is **lowercase + suffix-or-exact**: `.mt`, `.mtc`, `.mtproj`, `.mtworkspace` via `endsWith`, but the lockfile is `name.equals("mtproj.lock") || name.endsWith(".mtproj.lock")` because it's an exact filename without a leading dot.

### Persistent settings (Gson)

`SettingsStore.load()` reads `%USERPROFILE%\.mtype-editor\settings.json` via Gson and falls back to `WorkspaceSettings.defaults()` if absent or unparseable. The file is **never auto-written** — only the explicit `SettingsStore.save(...)` writes — so deleting the file restores defaults. `Toolchain.defaults()` honors `MTYPE_HOME` env var.

## Things that aren't here yet

`mtpm` package manager hookup, signature help, inlay hints, document symbols (outline panel) are deferred. The `OutputPane` is intentionally a `TabPane` so new panels can drop in alongside Problems / Run / LSP Log / Call Hierarchy without UI rewiring. Reuse `StreamPump` for any new external process integration.
