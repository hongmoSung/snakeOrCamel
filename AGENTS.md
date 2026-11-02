# AGENTS.md

This file provides project context and working instructions for AI coding agents working in this repository.

## Project Overview

snakeOrCamel is a JetBrains IntelliJ Platform plugin that provides text conversion utilities. It requires the platform and JSON modules declared in `plugin.xml`. Configured compatibility verification targets IntelliJ IDEA Community 2024.2 and IntelliJ IDEA 2026.2.4; compatibility with other IntelliJ-based IDEs must be verified separately.

**Features:**
- Convert text between snake_case/kebab-case and camelCase
- JSON Prettier tool window for formatting JSON

## Build Commands

Use JDK 25 as `JAVA_HOME` and install JDK 21 for runtime compatibility tests. All Java sources compile with `--release 21`.

```bash
# Build the plugin
./gradlew build

# Run plugin in IDE sandbox for testing
./gradlew runIde

# Run in a separate IntelliJ IDEA 2026.2.4 sandbox
./gradlew runIde2026

# Clean build, test on Java 25 and 21, and verify both supported IDE targets
./gradlew clean build verifyPlugin

# Run utility tests on both Java runtimes and editor fixture tests on Java 21
./gradlew test testJava21

# Run utility and editor fixture tests on Java 21 only
./gradlew testJava21

# Sign plugin for distribution (requires env vars: CERTIFICATE_CHAIN, PRIVATE_KEY, PRIVATE_KEY_PASSWORD)
./gradlew signPlugin

# Publish to JetBrains Marketplace (requires env var: PUBLISH_TOKEN)
./gradlew publishPlugin
```

## Architecture

Java classes in `src/main/java/io/snakeorcamel/`, grouped into `caseconvert` and `jsonprettier` packages:

**Case Conversion (Editor Action):**
- **ConvertUtil.java** - Static utility methods: `toCamelCase(String)` (handles both snake_case and kebab-case) and `toSnakeCase(String)`
- **CaseConvertAction.java** - IntelliJ `AnAction` that handles multi-caret text selection, auto-detects case format (underscore or hyphen → convert to camelCase, otherwise → convert to snake_case), and performs undoable replacement via `WriteCommandAction`

**JSON Prettier (Tool Window):**
- **JsonUtil.java** - JSON formatting using Gson: `prettify(String)` and `getArrayCount(String)`
- **JsonPrettierToolWindowFactory.java** - `ToolWindowFactory` that creates the JSON Prettier panel
- **JsonPrettierPanel.java** - IntelliJ JSON editor with format, expand/collapse, copy buttons, and array item counts

Plugin configuration: `src/main/resources/META-INF/plugin.xml`
- Case conversion: Action ID `Name.Convert`, keyboard shortcut `Ctrl+Meta+C` (`⌃⌘C` on macOS)
- JSON Prettier: Tool window ID "JSON Prettier", anchored to right sidebar
- Dependencies: `com.intellij.modules.platform`, `com.intellij.modules.json`

## Tech Stack

- Build and compilation: JDK 25; Java 21 source, standard APIs, and bytecode (`--release 21`)
- Gradle 9.8.2 with IntelliJ Platform Gradle Plugin 2.19.0
- Compile and default sandbox IDE: IntelliJ IDEA Community 2024.2 (sinceBuild 242, no untilBuild)
- Additional sandbox and verification IDE: unified IntelliJ IDEA 2026.2.4
- `check` includes utility tests on Java 25 and Java 21 and IntelliJ 2024.2 editor fixture tests on Java 21; Plugin Verifier uses each IDE's bundled JBR
- Gson for JSON processing

Automated tests cover `ConvertUtil` and the registered case conversion action using IntelliJ editor fixtures, including multiple selections and grouped Undo/Redo. Changes to editor actions or the JSON tool window also need the sandbox smoke tests described in `docs/development.md`; automated test success alone does not verify those UI behaviors.

Keep IntelliJ API calls compatible with the 2024.2 compilation baseline and preserve `--release 21` when changing build settings. See `docs/development.md` for setup and the manual verification checklist.
