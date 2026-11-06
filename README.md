# SnakeOrCamel - Case Converter

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/23755-snakeorcamel.svg)](https://plugins.jetbrains.com/plugin/23755-snakeorcamel)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/23755-snakeorcamel.svg)](https://plugins.jetbrains.com/plugin/23755-snakeorcamel)

## Ultimate Text Case Conversion Tool for Developers

**SnakeOrCamel** is a powerful IntelliJ-based IDE plugin that instantly converts between different text case formats, making it effortless to work with various naming conventions across different languages and frameworks.

## Features

- **Bidirectional Conversion**:
  - Convert from **snake_case** to **camelCase**
  - Convert from **camelCase** to **snake_case**

- **Multiple Access Points**:
  - Context menu - Right-click and select "Snake or Camel"
  - Code Format menu - Find in the Code menu under Format options
  - Keyboard shortcut - Use `⌃ + ⌘ + C` (macOS) for instant conversion

- **Multi-selection Support**:
  - Convert multiple selections simultaneously
  - Perfect for batch operations

- **Agent Terminal (opt-in)**:
  - Save project-local Claude, Codex, or Custom launch profiles.
  - Insert file or selected-line references into the **dedicated default-agent Terminal tab**, preserving the question without pressing Enter.
  - Disabled by default. Enable under **Settings → Tools → SnakeOrCamel → Agent Terminal** after reviewing shortcut conflicts and behavior.
  - Use **Tools → SnakeOrCamel → Run Agent…** or the editor reference action. The reference shortcut is `⌥⌘K` on macOS / `Ctrl+Alt+K` on Windows and Linux when no existing assignment conflicts.
  - See the [usage, recovery, and verification notes](./docs/agent-terminal.md), including remaining sandbox UI checks and CLI detection limits.

## Usage

1. **Select** the text you want to convert
2. **Convert** using one of these methods:
   - Right-click and select "Snake or Camel" from the context menu
   - Press `⌃ + ⌘ + C` (Control + Command + C) on macOS
   - Select "Snake or Camel" from the Code Format menu

![SnakeOrCamel in action](./docs/snakeOrCamel.gif)

## Use Cases

- **Cross-language Development**: Convert between Java/JavaScript camelCase and Python snake_case
- **Database Integration**: Transform SQL column names (snake_case) to programming variables (camelCase)
- **API Development**: Match various API naming conventions

## Installation

- **JetBrains Marketplace**: [SnakeOrCamel Plugin](https://plugins.jetbrains.com/plugin/23755-snakeorcamel)
- **Within IDE**: Go to Preferences → Plugins → Marketplace → Search for "SnakeOrCamel"

## Supported IDEs

Requires **IntelliJ IDEA 2026.2.4 or later** (build `262.11879.100+`) with a Java 25 runtime. Compatibility verification targets **2026.2.4**; other IntelliJ-based IDEs and later versions require separate verification.

## Development

Install **JDK 25** and set `JAVA_HOME` to it. Gradle, compilation, and all tests use Java 25.

The build uses the project wrapper (Gradle **9.8.2**) and IntelliJ Platform Gradle Plugin **2.19.0**. Compilation and the default sandbox use IntelliJ IDEA **2026.2.4**.

```bash
./gradlew --version
./gradlew clean build verifyPlugin
./gradlew runIde
```

`build` runs unit and platform fixture tests through `test`. Reports are in `build/reports/tests/`. Plugin Verifier checks 2026.2.4 using its bundled JBR; reports are in `build/reports/pluginVerifier/`. The distribution ZIP is in `build/distributions/`.

See the [development and verification guide](./docs/development.md) for setup, bytecode inspection, and the sandbox smoke test checklist.

## Feedback & Contributions

Feedback, feature requests, and contributions are welcome! Please feel free to submit issues or pull requests on our [GitHub repository](https://github.com/hongmoSung/snakeOrCamel).

## License

This plugin is available under the terms of the [LICENSE](./LICENSE) file.
