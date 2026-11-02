# AGENTS.md

This file provides project context and working instructions for AI coding agents working in this repository.

## Project Overview

snakeOrCamel is a JetBrains IntelliJ Platform plugin that provides text conversion utilities. It requires the platform and JSON modules declared in `plugin.xml`. Configured compatibility verification targets IntelliJ IDEA 2026.2.4; compatibility with other IntelliJ-based IDEs must be verified separately.

**Features:**
- Convert text between snake_case/kebab-case and camelCase
- JSON Prettier tool window for formatting JSON

## Build Commands

Use JDK 25 as `JAVA_HOME`. Source, standard APIs, bytecode, and tests all target Java 25.

```bash
# Build the plugin
./gradlew build

# Run plugin in IDE sandbox for testing
./gradlew runIde

# Clean build, test on Java 25, and verify IntelliJ IDEA 2026.2.4
./gradlew clean build verifyPlugin

# Run utility and IntelliJ platform fixture tests
./gradlew test

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

- Build, compilation, and tests: JDK 25 (class major version 69)
- Gradle 9.8.2 with IntelliJ Platform Gradle Plugin 2.19.0
- Compile, sandbox, and verification IDE: IntelliJ IDEA 2026.2.4 (sinceBuild 262.11879.100, no untilBuild)
- `test` includes utility and IntelliJ platform fixture tests; Plugin Verifier uses the IDE's bundled JBR
- Gson for JSON processing

Automated tests cover `ConvertUtil` and the registered case conversion action using IntelliJ editor fixtures, including multiple selections and grouped Undo/Redo. Changes to editor actions or the JSON tool window also need the sandbox smoke tests described in `docs/development.md`; automated test success alone does not verify those UI behaviors.

Use public IntelliJ IDEA 2026.2.4 APIs and Java 25. Keep Classic and Reworked terminal engines supported. See `docs/development.md` for setup and the manual verification checklist.
