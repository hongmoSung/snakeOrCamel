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

Requires IntelliJ Platform **2024.2 or later** (build `242+`). Compatibility verification targets IntelliJ IDEA Community **2024.2** and unified IntelliJ IDEA **2026.2.4**.

IntelliJ-based IDEs include:
- IntelliJ IDEA
- WebStorm
- PyCharm
- PhpStorm
- Rider
- And other JetBrains IDEs

## Development

Install **JDK 25** for Gradle and compilation, and **JDK 21** for runtime compatibility tests. Set `JAVA_HOME` to JDK 25. Gradle must be able to discover both installations.

The build uses Gradle **9.8.2** and IntelliJ Platform Gradle Plugin **2.19.0**. All Java compilation uses `--release 21`, restricting source syntax, standard APIs, and bytecode to Java 21. The compile platform remains IntelliJ IDEA Community 2024.2.

```bash
./gradlew --version
./gradlew clean build verifyPlugin
./gradlew runIde          # IntelliJ IDEA Community 2024.2
./gradlew runIde2026      # IntelliJ IDEA 2026.2.4, separate sandbox
```

`build` runs the unit tests on JDK 25 (`test`) and JDK 21 (`testJava21`). Reports are in `build/reports/tests/`. Plugin Verifier checks both target IDEs using each IDE's bundled JBR; reports are in `build/reports/pluginVerifier/`. The distribution ZIP is in `build/distributions/`.

See the [development and verification guide](./docs/development.md) for setup, bytecode inspection, and the sandbox smoke test checklist.

## Feedback & Contributions

Feedback, feature requests, and contributions are welcome! Please feel free to submit issues or pull requests on our [GitHub repository](https://github.com/hongmoSung/snakeOrCamel).

## License

This plugin is available under the terms of the [LICENSE](./LICENSE) file.
