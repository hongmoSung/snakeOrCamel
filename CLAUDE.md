# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

snakeOrCamel is a JetBrains IntelliJ Platform plugin that provides text conversion utilities. It works with all IntelliJ-based IDEs (IntelliJ IDEA, WebStorm, PyCharm, PhpStorm, Rider, etc.).

**Features:**
- Convert text between snake_case/kebab-case and camelCase
- JSON Prettier tool window for formatting JSON

## Build Commands

```bash
# Build the plugin
./gradlew build

# Run plugin in IDE sandbox for testing
./gradlew runIde

# Sign plugin for distribution (requires env vars: CERTIFICATE_CHAIN, PRIVATE_KEY, PRIVATE_KEY_PASSWORD)
./gradlew signPlugin

# Publish to JetBrains Marketplace (requires env var: PUBLISH_TOKEN)
./gradlew publishPlugin
```

## Architecture

Java classes in `src/main/java/io/snakeorcamel/`:

**Case Conversion (Editor Action):**
- **ConvertUtil.java** - Static utility methods: `toCamelCase(String)` (handles both snake_case and kebab-case) and `toSnakeCase(String)`
- **EditorAction.java** - IntelliJ `AnAction` that handles multi-caret text selection, auto-detects case format (presence of underscore → convert to camelCase, otherwise → convert to snake_case), and performs undoable replacement via `WriteCommandAction`

**JSON Prettier (Tool Window):**
- **JsonUtil.java** - JSON formatting using Gson: `prettify(String)`, `isValidJson(String)`, and `getArrayCount(String)`
- **JsonPrettierToolWindowFactory.java** - `ToolWindowFactory` that creates the JSON Prettier panel
- **JsonPrettierPanel.java** - Swing UI with input/output text areas and format/copy/clear buttons

Plugin configuration: `src/main/resources/META-INF/plugin.xml`
- Case conversion: Action ID `Name.Convert`, keyboard shortcut `Ctrl+Meta+C` (`⌃⌘C` on macOS)
- JSON Prettier: Tool window ID "JSON Prettier", anchored to right sidebar
- Dependencies: `com.intellij.modules.platform`, `com.intellij.modules.json`

## Tech Stack

- Java 17
- Gradle 9.3.0 with IntelliJ Platform Gradle Plugin 2.10.5
- Target IDE: IntelliJ IDEA 2024.1+ (sinceBuild 241)
- Gson for JSON processing