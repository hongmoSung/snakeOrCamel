# Development and compatibility verification

## JDK setup

Install JDK 25. Gradle, compilation, and all tests use Java 25 (class major version 69).

Set `JAVA_HOME` to JDK 25 before running the wrapper. On macOS, if JDK 25 is registered:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --version
./gradlew javaToolchains
```

The version output must show Gradle 9.8.2 and a Java 25 JVM. If Gradle does not discover a JDK, add its absolute home path to `org.gradle.java.installations.paths` in your user-level `~/.gradle/gradle.properties` (comma-separated for multiple JDKs).

## IDE dependency import

Use the project Gradle wrapper and JDK 25 in IntelliJ's Gradle settings. The Gradle user home is a cache location, not the Gradle distribution; it can differ from the terminal default.

With IntelliJ Platform Gradle Plugin 2.19.0, querying `compileClasspath` before `intellijPlatformDependency` can fail to locate the bundled Terminal JAR (`file:/terminal`). The plugin registers its local Ivy artifact path rule only after the platform configuration resolves. Normal compilation can succeed because other build setup resolves the platform first, while IDE import leaves `snakeOrCamel.main` without library dependencies.

`build.gradle.kts` resolves the platform lazily before either compile classpath is resolved. This preserves the wrapper version, Java 25 target, and compile-only dependency scope. After updating the build script, reload the Gradle project and check that `snakeOrCamel.main` has IntelliJ Platform and Terminal dependencies. No manually attached SDK JARs are needed.

Upstream implementation: [LocalIvyArtifactPathComponentMetadataRule in 2.19.0](https://github.com/JetBrains/intellij-platform-gradle-plugin/blob/2.19.0/src/main/kotlin/org/jetbrains/intellij/platform/gradle/artifacts/LocalIvyArtifactPathComponentMetadataRule.kt).

## Build configuration audit (2026-11-02)

Compared isolated copies of the current build with and without the `beforeResolve` block, using the same Gradle and IntelliJ metadata caches. Each fresh invocation resolved either `compileClasspath` or `testCompileClasspath` first, with configuration cache disabled:

| First configuration resolved | Without the hook | With the hook |
| --- | --- | --- |
| `compileClasspath` | Terminal/JSON artifacts searched at `file:/terminal`, `file:/intellij.json`, etc.; failed | 282 files resolved |
| `testCompileClasspath` | Same bundled-plugin path failure | 304 files resolved |

This verifies the dependency-resolution failure on IDEA 2026.2.4, not a full GUI import test. Before removing the workaround after a Gradle plugin upgrade, repeat both classpath-first checks and reload the project in IntelliJ. A successful `compileJava` alone does not exercise the failing ordering.

The 2.19.0 defaults were also inspected without project overrides: `test` uses `JUnitOptions`, so `useJUnitPlatform()` is required for Jupiter and Vintage; verifier defaults are compatibility, internal API, and override-only failures. An attempted `addAll()` of the extra categories replaced the property convention rather than retaining it (confirmed by querying the effective value). The build therefore keeps all six categories explicitly. `ides.current()` avoids duplicating the compile IDE version.

Retained dependencies and settings:
- Java toolchain 25, Java and IntelliJ Platform plugins: compilation and plugin packaging.
- Maven Central and IntelliJ default repositories: test dependencies and IDE artifacts.
- Terminal and JSON bundled plugins: directly imported Terminal APIs and `JsonFileType`.
- Platform test framework and JUnit 4: `BasePlatformTestCase` fixtures.
- JUnit Jupiter (including parameterized tests), Vintage, and platform launcher: run the two test styles through JUnit Platform. The BOM aligns JUnit 5 artifacts.
- `pluginVerifier()`: supplies the verification CLI used by CI.
- Minimum IDE build, release notes, signing, and publishing: compatibility metadata and existing release workflow. Signing/publishing are not required by local compilation, but removing them would remove that configured workflow.

Defaults such as bundled verifier runtime, instrumentation, and searchable-options generation are not repeated. Tests and Plugin Verifier were not executed during this audit.

## Build and verify

```bash
./gradlew clean build verifyPlugin
```

- `test` runs all utility and IntelliJ platform fixture tests on Java 25.
- `verifyPlugin` checks IntelliJ IDEA 2026.2.4 (`262.11879.100`) and the pinned 2026.3 RC (`263.7307.112`) using each IDE's bundled JBR. Compatibility problems, missing dependencies, invalid plugins, invalid plugin structure, internal API use, and incorrect override-only API use fail verification.
- Test reports: `build/reports/tests/test/`.
- Verifier reports: `build/reports/pluginVerifier/`.
- Distribution: `build/distributions/snakeOrCamel-1.2.2.zip`.

`TestFrameworkType.Platform` provides editor fixtures. JUnit Vintage is retained because `BasePlatformTestCase` uses JUnit 3; it runs alongside JUnit 5 in the same test task. Tests cover multiple selections, grouped Undo/Redo, and Agent Terminal request handling.

CI uses one JDK 25 job for build and verification and uploads both reports.

Compilation and the default sandbox target IntelliJ IDEA 2026.2.4; binary verification additionally targets IDEA 2026.3 RC (`263.7307.112`). The descriptor requires build `262.11879.100` or newer and has no upper bound. Other IDE builds and products still need separate verification. A failed download of the pinned RC is an incomplete verification, not a compatibility pass.

## Inspect the distributed bytecode

Run after building. This inspects the actual JARs inside the distribution ZIP and fails unless all plugin classes have major version 69:

```bash
python3 - <<'PY'
import io
import struct
import zipfile

count = 0
with zipfile.ZipFile('build/distributions/snakeOrCamel-1.2.2.zip') as distribution:
    for name in distribution.namelist():
        if not name.endswith('.jar'):
            continue
        with zipfile.ZipFile(io.BytesIO(distribution.read(name))) as jar:
            for entry in jar.namelist():
                if entry.startswith('io/snakeorcamel/') and entry.endswith('.class'):
                    major = struct.unpack('>H', jar.read(entry)[6:8])[0]
                    assert major == 69, (name, entry, major)
                    count += 1
assert count > 0, 'No plugin classes found'
print(f'Verified {count} plugin classes: major version 69 (Java 25)')
PY
```

## Sandbox smoke tests

```bash
./gradlew runIde        # IntelliJ IDEA 2026.2.4
```

The sandbox uses the IDE's bundled runtime. Close it to end the Gradle task.
Perform the following checks, and verify Agent Terminal with both Classic and Reworked engines:

- Select `my_variable_name` and invoke **Snake or Camel**: expect `myVariableName`.
- Select `my-variable-name` and invoke the action: expect `myVariableName`.
- Select `myVariableName` and invoke the action: expect `my_variable_name`.
- Select multiple names with multiple carets and invoke the action: every selection converts. Undo once: all replacements revert together.
- Open **JSON Prettier**, enter `[{"name":"first","values":[1,2]},{"name":"second"}]`, and click **Format**: expect indented JSON and **Array: 2 items**.
- Click **Collapse All**, then **Expand All**: nested JSON folds close and reopen.
- Click **Copy** and paste into a scratch file: expect the entire formatted JSON.
- Enter `{"name":` and click **Format**: expect an invalid JSON status message.

Binary verification and unit tests do not replace these interactive checks. Record actual results in `docs/version-upgrade-verification.md`.

## Closed Terminal reference shortcut regression checks

Run these checks in IDEA 2026.2.4 separately with Classic and Reworked. These exercise
`TerminalAdapter.show` (the public ToolWindow completion callback) and the actual
Classic `createShellWidget` / Reworked `createTabBuilder` creation paths, which the
fake-terminal orchestration tests do not exercise. Results are pending user execution;
compilation and ZIP creation are not evidence of successful UI behavior.

- Enable Agent Terminal and select a default profile. Close every Terminal tab and
  hide the tool window. Select lines in a disk file and press the reference shortcut
  once: show Terminal, obtain exactly one tab, launch once, insert the captured
  reference once without Enter after startup.
- Repeat in a newly opened project before ever opening Terminal. If IDE initialization
  supplies a tab, reuse it; do not create a second tab.
- Hide a Terminal with an agent already running in the first tab. The shortcut must
  reuse that tab and process, including when another tab had focus.
- Delay shell startup and press the shortcut repeatedly. Expect one tab, one launch,
  and one copy of the same reference, within the original 30-second deadline.
- While showing/connecting, close or replace the target tab, disable the feature,
  or close the project. No delayed launch or insertion may follow. An already exited
  first tab must not be replaced automatically.
- Type a shell command before the pending request reaches an empty shell. Preserve
  the text; do not append the reference or execute the default profile over it.
- With no default profile, use the existing chooser; canceling it must send nothing.
- Inspect diagnostic stages `window-show`, `tab-acquired`, and `connection-wait`.
  Terminal body text and reference paths must not appear in diagnostic logs.

## API warning policy

Internal API and compatibility failures remain release blockers. Deprecated and
experimental usages remain visible in the verifier reports; they are not suppressed.
Compare reports by API and caller, not just by total count.

- Profile selection uses a shared `DialogWrapper` with a `ComboBox<AgentProfile>`.
  Confirm selects the actual profile object (including duplicate names); Cancel,
  Escape and window close must neither launch an agent nor change the default ID.
- Classic retains `TerminalToolWindowManager.createShellWidget` because the matching
  `createNewSession` overload is internal in the compile SDK. It also retains
  `JediTermWidget.getTerminalStarter`; the callback alternative is protected.
  Preserve queued input and reference insertion without Enter.
- Public Reworked Terminal APIs are experimental upstream. Keep them behind
  `ModernTerminalSession` and inspect new warnings on each supported IDE.

Run the sandbox checklist above on both pinned IDE builds, each with Classic and
Reworked. Install the same distribution ZIP in a separate RC IDE sandbox; confirm
the exact build in Help → About. Test startup, tab recreation, reference insertion
without submission, cancellation, shell integration disabled, and profile selection
(including duplicate names and all cancellation paths). Verify that an old process
probe or pending request is never reused for a replacement connection.

Sources: [Terminal API](https://plugins.jetbrains.com/docs/intellij/embedded-terminal.html),
[DialogWrapper](https://plugins.jetbrains.com/docs/intellij/dialog-wrapper.html),
[verification configuration](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html).

## JSON Prettier Swing event regression

A Swing button event can run on EDT without a write-intent lock. A document
write command alone does not protect PSI work performed after the command ends.
Schedule Format and folding operations with `Application.invokeLater`, using
`ModalityState.stateForComponent(panel)` and an expiration condition for a
disposed project/editor. This supplies both write intent and a write-safe
context; acquiring write intent alone is insufficient for a write-unsafe event.

`JsonPrettierPanelPlatformTest` invokes the actual buttons through raw Swing
events and asserts that the event starts without write intent. It verifies
formatting/PSI commit/array status, Collapse All and Expand All, malformed input
preservation and blank-input status reset. Additional cases exercise full-document
copy with an active selection (restoring the clipboard afterward), Undo/Redo,
array-to-object status changes, repeated formatting without duplicate folds,
folding before formatting, and cancelling queued operations when the panel closes. It supplements the manual UI checks
and does not establish sandbox interaction results.

Reference: [IntelliJ threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html).

Run the focused suite while developing UI callback changes:

```bash
./gradlew test --tests 'io.snakeorcamel.jsonprettier.JsonPrettierPanelPlatformTest'
```

Run `./gradlew build` afterward to include the rest of the platform fixtures. The
normal CI test task includes these regressions and uploads both HTML reports and
JUnit XML (`build/test-results/test/`). Do not wrap the test button event itself
in a platform read/write action to make a failure disappear; only test setup and
assertion reads should acquire the appropriate platform context.

UI entry-point audit: JSON Prettier's Format callback is the direct Swing-to-PSI
commit path; Format and folding now use the modality-aware scheduler. Copy uses
the document's text and clipboard APIs. Case conversion writes within its command
and is registered through the action system; existing editor fixtures cover
multi-caret Undo/Redo. Agent settings listeners edit Swing/profile configuration,
while asynchronous terminal probe completions return through platform
`Application.invokeLater` with cancellation/generation checks. This audit is not
a substitute for interactive verification of either terminal engine.
