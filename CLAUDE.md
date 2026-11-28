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
mvn test                         # headless JUnit tests (pure logic only: no JavaFX toolkit needed)
mvn javafx:run                   # launch via the javafx-maven-plugin
```

Tests live in `src/test/java` and must not construct JavaFX controls (`MTypeCodeArea` etc. need a running toolkit). Keep testable logic in pure helpers — e.g. `lsp/SourceEdits`, `ui/editor/FoldRegions`, `ui/editor/{CompletionAnchor,CompletionFilter,CompletionEditPlanner,CompletionTriggerPolicy,LexicalContext,SnippetSession,SnippetStack}`. CI runs `mvn -B test` before the `-DskipTests` dist build. `LspBridge` is tested against a fake `LanguageServer` through its package-private `LspBridge(LanguageServer, Executor, Consumer<String>)` constructor (`LspBridgeTest`, `ManualExecutor`, `FakeLanguageServer`); `LspBridgeWireTest` checks the real lsp4j messages, `$/cancelRequest` included.

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

**Outgoing messages (MYT-422).** Each LSP session has one `Outbox` with a single outgoing thread (`mtype-lsp-out`). Every notification (`post`) and request (`call`) is queued there and written in the order the public methods were called; callers never write to the pipe, the FX thread included. Parameters and text are captured when the call is made.
- **Versions:** the outbox remembers the last version sent per URI (`DocumentVersions`) and drops a didChange that is not newer, with a `[lsp] dropped stale didChange` log line. didOpen RESETS the baseline (a reopened tab restarts at 1; `DiffTab` opens with its own counter) and didClose forgets it.
- **Request futures always complete:** with the mapped response; with the method's fallback when the LSP isn't ready, the session is closing, the request can't be queued or sent, or the server errors; or cancelled. Never leave one hanging: `save()` writes to disk only in its format callback.
- **Cancelling** a returned future before the request is written means it is never sent; after, the lsp4j future is cancelled on the outgoing thread, which writes `$/cancelRequest` (the mType server ignores it today). Cancel the future `LspBridge` returned, not a `thenAcceptAsync` stage (that doesn't reach the source), and check `LatestRequest.isCurrent` before applying a response.
- **Lifecycle:** `initialize` is sent on the outgoing thread; its response is handled on the FX thread (`onInitialized`), which queues `initialized` before publishing the outbox, so every didOpen follows it. `stop()` marks the outbox closing (queued requests complete with their fallback, unsent), queues `shutdown`/`exit` behind the queued notifications (so exit-time didCloses are still delivered), waits up to 2 s, then destroys the process BEFORE closing its streams (a write blocked on a full pipe holds the stream lock).

LSP features wired today: `initialize/initialized`, `textDocument/{didOpen,didChange,didClose,publishDiagnostics,completion,hover,definition,references,formatting,codeAction,codeLens,prepareRename,rename,prepareCallHierarchy}`, `callHierarchy/{incomingCalls,outgoingCalls}`, `workspace/executeCommand`. Document sync is **full content** (not incremental) — simpler and the mType server accepts it.

`LspBridge` tracks a `session` long that's incremented on every start/stop. Async handlers (initialize callback, completion responses, etc.) check `startSession == session` before mutating state — this prevents responses from a previously-stopped LSP process from leaking into the current state when the user switches workspaces.

`Positions.offset(text, line, char)` is the **only** correct LSP↔CodeArea position converter. `LspEdits.applyToCodeArea` / `applyWorkspaceEdit` are the **only** places that mutate documents from server responses; they sort edits descending by offset so earlier positions stay valid. WorkspaceEdits modify open tabs via `CodeArea.replaceText`; closed files are read+rewritten on disk.

Edits are first shrunk by `SourceEdits.minimize`, which trims each edit to the spans that change and splits it per line when the line structure is unchanged. The mType formatter answers with ONE whole-document edit; applied verbatim, it would reset every paragraph style (folds, code-lens padding, execution line). `applyToCodeArea` returns the number of edits applied; 0 means nothing changed, so skip re-highlighting and report "No formatting changes".

`replaceText` always moves the caret to the end of what it inserted (`GenericStyledArea.replace` calls `selectRange`). So `applyToCodeArea` maps the caret through the applied edits (`SourceEdits.mapOffset`) and moves it back to the same code.

### Diagnostics flow (one bus, many subscribers)

`DiagnosticsBus` on `AppContext` is the single source of truth. `MTypeLanguageClient.publishDiagnostics` forwards each push to the bus, then the bus broadcasts to all subscribers on the FX thread. Subscribers:
- `DiagnosticsRenderer` → builds overlay `StyleSpans` for squiggles and a `Map<line, severity>` for the gutter
- `ProblemsPane` → adds/removes rows in the bottom Problems tab
- `OutputPane.problemsTab` label → live count "Problems (n)"

**Windows path mismatch fallback**: the mType server sometimes normalizes URIs to lowercase drive letters, and `Path.equals()` is case-sensitive even on case-insensitive Windows FS. If `tabPane.findByPath(path)` misses, the renderer falls back to `tabPane.openTabs()` and matches case-insensitively on the absolute path. Without that fallback the squiggle silently disappears.

### Document model & version drift

Every `EditorTab` owns a monotonically increasing `AtomicInteger version` per the LSP spec.
- **Versions:** `scheduleDidChange()` bumps it on every edit, so a response can be checked against `getVersion()` to detect that the text changed meanwhile (e.g. `DocumentSymbolController`). Versions sent to the server always increase.
- **didChange timing:** the debounced didChange (200 ms) hops to the FX thread and goes through `flushPendingDidChange()`, which reads the text at **send** time.
- **Ordering:** `LspBridge` writes everything from one ordered outgoing thread (see "Outgoing messages"), so a didChange queued before a request reaches the server before it, and the server answers the request on that text. Both `LspBridge` and the mType server (`DocumentManager::updateDocument` returns `UpdateResult::Stale`, MYT-422) drop a didChange whose version is not newer. Keep queuing didChange from the FX thread: `flushPendingDidChange()` reads FX-only state and the code area's text.
- **`flushPendingDidChange()`** (package-private) queues the pending change now. If this tab isn't open in the current LSP session, it queues didOpen instead. It does nothing when nothing is pending or the LSP isn't ready.
- **Who flushes first:** completion, signature help, Save / Format, Rename, definition, references (caret and code lens), call hierarchy and Quick Fix call it before their request; the controllers get it as a `Runnable flushDidChange` constructor argument. Rename flushes **all** open tabs, because its WorkspaceEdit rewrites them too. Format/Rename/Quick Fix then drop the response if the source text changed while it was in flight. Save then writes the current text unformatted.
- **Completion / signature help requests** are tracked by `LatestRequest`: a newer request cancels the older one. Signature help: `)` and closing the tab cancel both its debounce timer and its request in flight.
- **Completion is "honest" (MYT-423).** Enter only ever accepts an item that matches what was typed, on the word it was requested for.
  - **Session.** A session is the completion of one word, keyed by a `CompletionAnchor` (source line + word-start column). Its 180 ms debounce timer, request in flight and shown list are dropped together by `CompletionController.cancel()`. The triggers: ESC (menu or not), any mouse press (first thing in the MOUSE_PRESSED filter, so Ctrl+Click too), focus loss, the popup auto-hiding itself, Ctrl+., a non-word character that isn't a trigger here, a multi-character edit (paste, undo), an external reload, accepting, and closing the tab.
  - **Leaving the word.** The caret leaving the word, or a selection appearing, cancels too. This is checked in a coalesced `Platform.runLater`, because RichTextFX moves the caret before or after the text listener depending on the edit's shape. Everything that opens or accepts re-checks the anchor itself.
  - **Replies.** A reply is applied only while its `LatestRequest` token is current AND the caret is still in its anchor word, so a late reply never reopens the popup.
  - **Ranking.** `CompletionFilter` re-ranks the session's items on every keystroke: exact > prefix > subsequence, case-insensitive, on `filterText` else `label`, ties by `sortText` then server order. No match hides the popup (the session stays, so Backspace can bring it back); it never falls back to the whole list. The server's typo-tolerant (Levenshtein) items are therefore dropped.
  - **Triggers (MYT-424).** `CompletionController` subscribes to `codeArea.multiPlainChanges()` (not the text listener), so it sees each exact edit, including typing over a selection. It derives the caret from the change, and skips its own accept (`applying`) and `EditorTab.isInternalEditActive()`. RichTextFX applies every change of a multi-change before notifying, with each position relative to the text after the changes before it. A multi-change by the user (undo/redo of an accept) cancels. Every single-change user edit goes through `CompletionTriggerPolicy` (pure):
    - Word chars continue a session, or open one only in code or an import path.
    - Other characters fire only if the server advertised them in `completionProvider.triggerCharacters`. `LspBridge` reads them in `onInitialized` (single characters only) into `getCompletionTriggerCharacters()`, which is empty before init. They fire only where they mean something: `:` only as `::` in code; `"` and `/` only inside an import path (a closing quote lands in code); `@` in code and import paths; `.` in code, including `$"{…}` holes.
    - Backspace inside the word re-ranks.
    - Ctrl+Space (`invoke()`) is not an edit and works everywhere, comments and strings included.
  - **Lexical context.** `LexicalContext` (pure) scans the SOURCE text from 0 to the caret on demand, only when a rule needs it. It mirrors the server's `SourceSegmenter`: strings span lines, backslash escapes, block comments don't nest, there are no single-quoted literals, and an interpolation hole is code. An import path is a `"` right after `from`, or after `import lib`. It deliberately does NOT use the highlighter's token spans: those are debounced (120 ms, stale at the keystroke), in display coordinates, and the regex misses unterminated `"` / `/*` (exactly what is being typed) and `$"{…}` holes.
  - **Trigger kind.** Each request reports the edit that armed it (`Session.nextTrigger`): TriggerCharacter + the char for a trigger just typed, Invoked for identifier typing, backspace, re-requests and Ctrl+Space. The client advertises `contextSupport`. TriggerForIncompleteCompletions isn't used, since the server always returns plain arrays.
  - **Accept** never calls `completionItem/resolve`; the client advertises `resolveSupport: [documentation]` only. `CompletionEditPlanner` (pure, source offsets) plans the edits:
    - The main edit replaces anchor word start → caret. The server's range start is ignored: it can be narrower than the prefix, because the server counts UTF-8 bytes. Its end reaches past the caret only as far as it reached past the server's request column, and never past the word.
    - An `additionalTextEdit` overlapping the main edit is dropped. The changes come in descending order, so an import at the word's own offset lands above it.
    - The controller applies all changes as ONE `createMultiChange()` with `replaceTextAbsolutely` (display offsets from the pre-commit text; `sourceToDisplayBefore` keeps boundary hints out of the replaced range), wrapped in `undoManager.preventMerge()` on both sides. That makes one undo step that doesn't merge with nearby typing.
    - A commit doesn't place the caret, so the controller selects the caret or first tab stop explicitly.
  - **Snippets (MYT-424).** Completion is not blocked during a snippet, and accepting doesn't end it.
    - `SnippetSession` keeps its tab stops in DISPLAY offsets and maps every change from `multiPlainChanges` through `onEdit`: hint mutations, our own multi-change commit (its changes arrive in apply order), undo, typing.
    - Only the current placeholder grows at its edges, and never from a hint-only edit. An edit across a remaining placeholder's boundary ends the session, as does a caret outside it on Tab.
    - A snippet item accepted inside a snippet is pushed on `SnippetStack`. The inner one owns Tab until its final caret; then it is popped and the next Tab continues the outer one.
    - Tab with the popup open still accepts first (`EditorTab.handleKey`).
  - **View seam.** The popup is behind `CompletionView`; `ContextMenuCompletionView` is today's implementation, and MYT-436 swaps it.
- `openedLspSession` on the tab tracks which LSP session opened it; `onLspReady()` re-sends `didOpen` only if the session changed (e.g., after an LSP restart).

### Code folding (visual-only)

Folding NEVER edits the text. It hides paragraphs with RichTextFX's built-in paragraph folding (protected on `GenericStyledArea`, wrapped by `MTypeCodeArea.foldLines` / `unfoldRunAfter`). Folded lines carry the paragraph style class `collapse`, which RichTextFX's bundled `styled-text-area.css` hides.

The language server, the disk, undo, line numbers, breakpoints and diagnostics therefore always see the real source, and paragraph index == source line. MYT-444 was the text-edit version of folding sending its `" … }"` placeholder to the LSP and writing it to disk.

- **Model.** A fold is a visible header paragraph followed by a maximal run of `collapse` paragraphs; the state moves with the text.
  - Regions come from documentSymbol via `FoldRegions.compute`. The header is the first line containing `{`.
  - VS Code style: a closing line starting with `}` stays visible.
  - The folded look lives in the gutter (`mt-gutter-fold-arrow-folded`, `mt-lineno-folded`), not on the header text.
- **Paragraph style classes are shared state** (`collapse`, `mt-code-lens-paragraph`, `mt-execution-line`). Use `MTypeCodeArea.updateParagraphClasses`; never `setParagraphStyle` with a fresh collection.
- **Inserted text gets the INITIAL (empty) paragraph style** (`setUseInitialStyleForInsertion(true)`), so an insert at column 0 of a hidden line un-hides it. Inlay hints therefore go through `insertHint`, which keeps the paragraph style.
- **`CodeFoldingController` keeps folds honest:**
  - **M1:** an edit touching a hidden line, or adding/removing a line break right above one, opens that fold.
  - **M2:** the caret never rests in hidden text.
  - Both run deferred via `Platform.runLater`. Never change paragraph styles inside a `richChanges()` handler.
- **M3: navigate with `EditorTab.revealPosition` / `revealLine`.** They unfold first; a raw `moveTo` into a fold is pushed out of it by RichTextFX.
- **Undo is plain-text** (`UndoUtils.plainTextUndoManager`). The default rich manager would record every highlighting / fold style change as an undo step.
- **Gutter.** `GutterFactory` returns `null` for hidden rows: zero-height rows aren't clipped, so their graphics would overlap. Call `refreshGutter` after fold/unfold so hidden rows re-query the factory.

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
- **Quick Fix context.diagnostics**: per LSP spec it should be diagnostics that intersect the action's range. We send every diagnostic on the caret line (`QuickFixes.containingLine`), as the original lsp4j objects, so the server's opaque `data` (e.g. `missingName` for "Add import") round-trips. Diagnostic-driven fixes need a squiggle on the line; the server also offers diagnostic-agnostic refactors (accessors, constructor, implement interface) anywhere inside a class, and merges duplicate actions.
- **Quick Fix request** (`EditorCommands.fetchQuickFixes`, shared by right-click and **Ctrl+.**): flushes the pending didChange first (like format), drops superseded responses via `quickFixRequestSerial`, and retries once when the line's diagnostics changed in flight (typing a line and pressing Ctrl+. right away). Applying an edit is skipped if the text changed since the request.
- **Ctrl+T** (`WorkspaceSymbolPicker`): opens at once with the server's empty-query `workspace/symbol` list (every symbol, sorted, capped at 256), then re-queries the typed text after 150 ms, generation-guarded like `FindInFilesWindow`. It shows `WorkspaceSymbols.merge(latest, base)`, so a server prefix miss never hides a base entry that `QuickPickDialog`'s substring filter matches. `QuickPickDialog.setItems` / `setOnQueryChanged` / `setPlaceholder` exist for this.
- **Ctrl+.** opens the same fixes in a `ContextMenu` at `getCaretBounds()` (Code menu → Quick Fix...). Like the completion menu, it doesn't take focus from the code area, so `EditorTab.handleKey` routes ESC / ENTER to it. No actions → status bar "No quick fixes at caret".

### Threading rules

- **FX thread only**: scene-graph mutation, `setStyleSpans`, `ContextMenu`/`Tooltip` show, tab manipulation, button enable/disable.
- **`BG_EXEC` (static 2-thread scheduled pool in `EditorTab`)**: debounced tokenize, debounced `didChange`, debounced completion request, debounced code-lens refresh. Each task hops back via `Platform.runLater` before touching UI. The `didChange` task hops back too and is *queued* from the FX thread, in order with the requests queued there (see "Document model & version drift").
- **`mtype-lsp-out`** (one single-thread executor per LSP session, in `LspBridge`): the only thread that writes LSP notifications and requests, `$/cancelRequest` included. lsp4j still writes its replies to server→client requests from its own threads (serialized by its output lock).
- **`StreamPump`** (daemon threads, one per stdout/stderr): pumps child-process output line-by-line. **Must drain unconditionally** — failing to read either pipe deadlocks the child on a full buffer.
- **Process shutdown order**: stop tokenizer/exec tasks → LSP `shutdown`/`exit` (queued on `mtype-lsp-out` behind pending notifications) → destroy the LSP process, then close its streams → kill run process → `Platform.exit()`.
- Never `Future.get()` on the FX thread; use `process.onExit()` not `waitFor()`. The one exception is `LspBridge.stop()`, which waits a bounded time (≤ 2 s for shutdown/exit, ≤ 2 s for the process to die).

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
