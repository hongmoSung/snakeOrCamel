# 1.2.1 verification — 2026-11-03

The Reworked Terminal adapter now uses the public startup-options Deferred as
the connection identity consistently for connection checks, process probes,
and screen snapshots. The IDEA 2026.2.4 implementation retains that handle for
the lifetime of its terminal view, as it does the former internal session handle.
The existing internal-API verification failure rule remains enabled.

## Automated results

- `JAVA_HOME`: `~/.gradle/jdks/snakeorcamel-temurin-25/Contents/Home`
  (Temurin 25.0.5).
- `./gradlew clean build verifyPlugin --console=plain`: passed.
- 121 tests in 10 suites: zero failures, errors, or skipped tests.
- Plugin Verifier: compatible with IDEA 2026.2.4 (`IU-262.11879.100`);
  no internal API usages reported. Existing deprecated and experimental API
  usage notices remain.
- Distribution: `build/distributions/snakeOrCamel-1.2.1.zip`.
- Inspected the packaged descriptor and all 47 plugin classes: version 1.2.1,
  Java 25 class major version 69, and no `getSessionDeferred` references.

## Interactive results

`./gradlew runIde` launched a separate sandbox Java process. The UI automation
tool listed it as `Main` / `com.jetbrains.jbr.java`, but selecting either name
returned `Invalid app`. The sandbox process was stopped after this attempt.

Classic and Reworked terminal smoke tests in `development.md` remain pending,
including closed-tab startup, existing-agent reuse, repeated shortcuts, and
cancellation when the target tab closes or changes. Automated success does not
establish that these interactive behaviors passed.

## API warning cleanup — 2026-11-04

The shared profile chooser now uses `DialogWrapper` and
`ComboBox<AgentProfile>` in both Run Agent and default-agent selection. It keeps
profile order, defaults to the first item, and returns the actual selected object
even when names are duplicated. Cancellation does not apply a default or launch
an agent. The existing public startup-options handle remains the connection
identity; no internal API was reintroduced.

### Automated results

- Java: Temurin 25.0.5, using the same `JAVA_HOME` recorded above.
- `./gradlew build --offline --console=plain`: passed after correcting the new
  test fixture; 124 tests in 11 suites, no failures, errors or skipped tests.
- `./gradlew buildPlugin --offline --console=plain`: passed.
- `./gradlew verifyPlugin --info --console=plain` (with 15-second Gradle HTTP
  connection/socket timeouts for the initial download): passed. Initial RC setup
  downloaded the IDE and additional verifier dependencies; total verification
  command time was 8m 13s. Existing six failure categories remain enabled.
- The RC IDE's product metadata identifies `IU`, version `2026.3`, build
  `263.7307.112`; this is the exact notification target.

| Plugin | IDE build | Verdict | Internal | Deprecated | Experimental |
| --- | --- | --- | --- | --- | --- |
| 1.2.1 | IU-262.11879.100 | Compatible | 0 | 2 | 85 |
| 1.2.1 | IU-263.7307.112 | Compatible | 0 | 2 | 85 |

Reports: `build/reports/pluginVerifier/<IDE build>/report.html` and
`plugins/io.snakeOrCamel/1.2.1/` beneath each IDE report directory. The deprecated
and experimental report entries are identical between these IDEs, not merely
the totals. Both deprecated `Messages.showChooseDialog` call sites are absent.

The 48 packaged plugin classes all target Java 25 (major 69). Inspection of the
actual distribution ZIP found no `getSessionDeferred` or `showChooseDialog`
references. Distribution: `build/distributions/snakeOrCamel-1.2.1.zip`.

### Remaining warning inventory

| API | Caller | Reason retained |
| --- | --- | --- |
| `TerminalToolWindowManager.createShellWidget(String, String, boolean, boolean)` | `TerminalAdapter.create` | Keeps Classic tab creation; matching `createNewSession` overload is internal in the compile SDK. |
| `JediTermWidget.getTerminalStarter()` | `ClassicTerminalSession.send` | Callback alternative is protected; retains terminal input ordering and reference insertion without Enter. |

All 85 experimental entries are in `ModernTerminalSession`, covering tab lookup
and creation, view/session state and startup options, process probes, output
models/snapshots/offsets/grid, working directory, shell integration/blocks, and
text sending. These are public Reworked Terminal APIs that remain experimental
upstream. Full API-and-caller entries are retained in each
`experimental-api-usages.txt`; no suppressions were added.

Static inspection of RC `TerminalViewImpl.getStartupOptionsDeferred()` confirms
that it returns the stored startup-options field. Connection checks, probe
acceptance and screen snapshots consistently use this handle in the plugin.
Existing fixture tests cover connection replacement and stale-request rejection;
they use fake sessions and do not establish real Reworked reconnect behavior.

### Interactive verification remains incomplete

The current UI automation inventory failed before any app could be selected:
`Sky Computer Use native pipe startup failed` and `The browser access host is
unavailable`. No current sandbox UI checks were performed. The following remain
pending on both pinned IDEs, separately for Classic and Reworked:

- Closed/fresh Terminal startup, exactly one tab/launch, repeated shortcuts and
  reuse of an existing agent.
- File/selected-line reference insertion exactly once without Enter.
- Cancellation on tab close/move/recreation and no stale process probe reuse.
- Shell integration unavailable/disabled, readiness timeout and recovery.
- Profile confirmation, duplicate names, Cancel/Escape/window close, and default
  persistence only after confirmation.
- Case conversion with multi-caret Undo/Redo and JSON formatting/folding/copy.

Binary compatibility passed; interactive completion must be recorded before
claiming that the complete smoke-test checklist passed. No Marketplace upload
was performed.

### Follow-up sandbox startup attempt — 2026-11-04

Retried the UI inventory, reset its session, and retried again after starting the
sandbox. All attempts failed with the same native-pipe/browser-host errors.

`./gradlew runIde --offline --console=plain` launched the IDEA 2026.2.4 sandbox.
The current log records `Loaded custom plugins: SnakeOrCamel (1.2.1)` at
15:39:16 KST. This confirms startup and plugin discovery, not successful action,
tool-window or terminal interaction. The sandbox also logged a platform
`LoadingState` error (`COMPONENTS_LOADED` expected while
`CONFIGURATION_STORE_INITIALIZED`); its displayed stack involves Registry,
progress and Fleet initialization, with no plugin frames. This was not diagnosed
or treated as a clean UI pass.

Log: `.intellijPlatform/sandbox/snakeOrCamel/IU-2026.2.4/log_runIde/idea.log`.
The sandbox process launched for this attempt was then terminated. No RC UI
startup or interactive checks were performed. The remaining smoke-test matrix
still requires a working computer-control connection or manual execution;
there is no additional binary-verification failure to fix at this point.

## Release version correction — 1.2.2

The additional profile-dialog changes belong to 1.2.2, following the existing
1.2.1 internal-API fix. Updated the build version, split the change notes, and
updated the development guide's current distribution filename. Historical
1.2.1 results above are retained as records of the earlier verification.

`./gradlew buildPlugin verifyPlugin --offline --console=plain` passed.
The latest verifier run replaces the report directory; current reports are
under `plugins/io.snakeOrCamel/1.2.2/` beneath each IDE build.

- IU-262.11879.100: Compatible. 2 usages of deprecated API. 85 usages of experimental API; no internal API usage reported.
- IU-263.7307.112: Compatible. 2 usages of deprecated API. 85 usages of experimental API; no internal API usage reported.

Distribution: `build/distributions/snakeOrCamel-1.2.2.zip`. Packaged descriptor
version verified as 1.2.2; all 48 plugin classes target Java 25, with no
`getSessionDeferred` or `showChooseDialog` references. The earlier 124-test
pass covers the unchanged Java source; tests were not repeated for this
version/change-notes-only adjustment. Interactive smoke tests remain pending.

## JSON Prettier EDT fix — 1.2.2, 2026-11-04

Manual testing reported `PsiDocumentManager.commitDocument` being called from
a Swing Format button event on EDT without write intent. The document write
command acquired/released its lock, but the following PSI commit ran outside
that scope.

Format and folding operations now run through `Application.invokeLater` with
the panel's modality state and an expired-project/editor condition. This provides
both write intent and a write-safe execution context; the first regression run
confirmed that acquiring write intent alone does not fix a write-unsafe Swing
invocation. Text replacement remains an undoable `WriteCommandAction`.

`JsonPrettierPanelPlatformTest` runs outside the fixture's EDT/write-action
wrapper and clicks the actual Swing buttons on EDT, asserting that the incoming
event has no write-intent lock. Three tests passed: valid array formatting and
PSI commit/status/fold expansion, invalid-input preservation and error status,
and clearing an old error for blank input.

`./gradlew build buildPlugin verifyPlugin --offline --console=plain` passed:
127 tests in 12 suites, zero failures/errors/skips. Both IU-262.11879.100 and
IU-263.7307.112 report Compatible, with zero internal API usages and the same
2 deprecated / 85 experimental usages. The regenerated 1.2.2 ZIP contains
48 Java 25 plugin classes and the expected descriptor version.

ZIP SHA-256:
`a54f6271f99e32d69a10b71ae5a3b087d28959818e87309e81c1054405273a63`.

The raw-event tests are automated platform fixtures, not a sandbox UI pass.
Reinstall the regenerated `build/distributions/snakeOrCamel-1.2.2.zip`, restart
the IDE, then repeat Format, Collapse All and Expand All in JSON Prettier.
The rest of the previously pending manual checklist remains pending.

## UI regression coverage expansion — 2026-11-04

Expanded `JsonPrettierPanelPlatformTest` from three to nine tests. The suite
continues to disable the fixture's EDT/write-action wrappers and checks that
actual button listener invocations begin on EDT without write intent or a write
lock. Additional coverage:

- Formatting is reverted by one Undo and restored by Redo.
- Copy includes the complete formatted document despite an active selection;
  the prior clipboard contents are restored after the test.
- Formatting an object after an array clears the stale array count.
- Repeated Format does not duplicate folding regions.
- Fold buttons before Format preserve input.
- Format/Collapse/Expand queued in one event are cancelled when the panel is
  disposed before the callbacks run; the document stays unchanged.

The focused nine-test suite passed. `./gradlew build --offline --console=plain`
also passed: 133 tests in 12 suites, zero failures, errors or skipped tests.
This follow-up changes tests, CI artifact retention and documentation only, so
the prior production ZIP and binary compatibility results remain applicable.
No new sandbox UI results are claimed.

CI's normal test job already includes the suite and now retains JUnit XML
alongside the HTML report. AGENTS.md records the unlocked-Swing-event test
requirement for model-touching callbacks, asynchronous disposal, error input
preservation and Undo/Redo. The entry-point audit and focused test command are
in development.md. Tool-window creation, actual mouse/keyboard routing and
Classic/Reworked shell integration still require the pending manual smoke tests.
