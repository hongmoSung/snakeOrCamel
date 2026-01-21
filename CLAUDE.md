# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

snakeOrCamel is a JetBrains IntelliJ Platform plugin that converts text between snake_case and camelCase. It works with all IntelliJ-based IDEs (IntelliJ IDEA, WebStorm, PyCharm, PhpStorm, Rider, etc.).

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

The plugin consists of two Java classes in `src/main/java/io/snakeorcamel/`:

- **ConvertUtil.java** - Static utility methods for case conversion:
  - `toCamelCase(String)` - Converts snake_case/kebab-case to camelCase
  - `toSnakeCase(String)` - Converts camelCase to snake_case

- **EditorAction.java** - IntelliJ `AnAction` implementation that:
  - Handles editor text selection events
  - Supports multi-caret/multi-selection
  - Auto-detects case format (checks for underscore presence)
  - Uses `WriteCommandAction` for undoable text replacement

Plugin configuration is in `src/main/resources/META-INF/plugin.xml`:
- Action ID: `Name.Convert`
- Keyboard shortcut: `Ctrl+Cmd+C` (macOS)
- Available in Code Format menu and Editor popup menu

## Tech Stack

- Java 17
- Gradle 9.3.0 with IntelliJ Platform Gradle Plugin 2.10.5
- Target IDE: IntelliJ IDEA 2024.1+ (sinceBuild 241, no upper limit)
