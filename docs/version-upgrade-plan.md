# JDK 25 기반 빌드 및 IntelliJ 호환성 업그레이드 계획

> 과거 계획 기록입니다. Java 21·IDE 2024.2 호환 정책은 폐기했습니다. 현재 기준은 Java 25와 IntelliJ IDEA 2026.2.4이며, 최신 실행·검증 절차는 [development.md](development.md)를 따릅니다.

## 목표와 확정 버전

Java 25로 빌드하면서 IDE 2024.2 지원을 유지하고, IDE 2026.2.4에서의 호환성을 검증한다.

Java 25는 빌드용 JDK를 의미한다. 플러그인의 소스 문법, Java 표준 API 및 바이트코드는 Java 21 범위로 제한한다.

| 항목 | 적용 기준 |
|---|---|
| Gradle 실행 및 컴파일 JDK | Java 25 |
| 소스 문법·표준 API·바이트코드 | Java 21 (`--release 21`) |
| Gradle | 9.8.2 |
| IntelliJ Platform Gradle Plugin | 2.19.0 |
| 컴파일 기준 플랫폼 | IntelliJ IDEA Community 2024.2 |
| 최소 지원 빌드 | `242` |
| 최신 검증 플랫폼 | IntelliJ IDEA 2026.2.4 |

Gradle과 빌드 플러그인은 계획 수립일인 2026-10-31에 확인한 안정 버전으로 고정한다.

- [Gradle 릴리스](https://gradle.org/releases/)
- [IntelliJ Platform Gradle Plugin 릴리스](https://plugins.gradle.org/plugin/org.jetbrains.intellij.platform)

## 빌드 설정 변경

- Gradle Wrapper를 9.8.2로 갱신하고 스크립트·JAR·배포 체크섬을 함께 반영한다.
- `build.gradle.kts`에서 IntelliJ Platform Gradle Plugin을 2.19.0으로 올린다.
- Java toolchain을 25로 지정하고 모든 `JavaCompile` 작업에 `options.release.set(21)`을 적용한다. 기존 Java 17 설정은 제거한다. Java 21 이후 문법과 표준 API 사용은 컴파일 단계에서 차단한다.
- 플랫폼 의존성을 IntelliJ IDEA Community 2024.2로 변경하고 `sinceBuild`를 `242`로 올린다. `untilBuild`는 현재처럼 지정하지 않는다.
- 기본 `runIde`는 2024.2를 실행한다. 별도 `runIde2026` 작업을 추가해 통합 IntelliJ IDEA 2026.2.4를 전용 샌드박스에서 실행한다.

Toolchain과 `--release`를 함께 사용하는 방식은 [Gradle 교차 컴파일 설정](https://docs.gradle.org/current/userguide/toolchains.html)을 따른다.

## 호환성 검증과 CI

- `recommended()` 대신 Plugin Verifier 대상을 IntelliJ IDEA Community 2024.2와 IntelliJ IDEA 2026.2.4로 명시한다. 최신 대상에는 Community 타입이 아닌 `IntellijIdea` 타입을 사용한다.
- Plugin Verifier는 각 IDE에 번들된 JBR을 사용하도록 설정한다. 호환성 오류, 누락 의존성, 잘못된 플러그인 구조는 CI 실패로 처리한다.
- GitHub Actions의 빌드·검증 작업을 Temurin 25로 변경한다. 기존 테스트 결과 및 검증 보고서 업로드는 유지한다.
- Java 21 런타임에서도 기존 단위 테스트를 실행하는 `testJava21` 작업을 추가하고 `check`에 연결한다. CI에는 실행에 필요한 JDK 21도 설치하되, Gradle 실행 JDK는 25로 유지한다.
- 업그레이드로 발견되는 API·의존성 문제는 IDE 2024.2에서도 사용할 수 있는 방식으로 수정한다.

IDE 제품 타입은 [IntelliJ Platform Gradle Plugin 타입 안내](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-types.html)를 따른다. 여러 IDE 버전 지원 시 컴파일 기준은 [공식 호환성 가이드](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)에 따라 가장 오래된 지원 플랫폼으로 유지한다.

## 완료 기준

- `./gradlew --version`에서 Gradle 9.8.2와 실행 JDK 25를 확인한다.
- `./gradlew clean build verifyPlugin`이 성공하고, 두 IDE의 검증 보고서에 호환성 오류가 없어야 한다. `build`의 `check`를 통해 기본 단위 테스트와 `testJava21`이 모두 실행되어야 한다.
- 배포 ZIP 내부 플러그인 클래스가 Java 21 바이트코드인 `major version 65`인지 확인한다.
- 두 IDE의 샌드박스에서 다음 사용자 동작을 확인한다.
  - 이름 변환: snake_case·kebab-case → camelCase, camelCase → snake_case, 멀티 커서, Undo.
  - JSON Prettier: 포맷, 오류 표시, 배열 항목 수 표시, 전체 펼치기·접기, 복사.
- README와 개발 안내 문서에 빌드 JDK 25, Java 21 호환 출력, 최소 IDE 2024.2 및 실행·검증 명령을 반영한다.

## 범위와 기본 방침

- 기존 작업 중인 변경을 보존한다.
- 기존 기능 변경은 업그레이드 과정에서 발견되는 호환성 수정으로 한정한다.
- 플러그인 ID, 액션 ID, 사용자 기능 및 프로젝트 버전 `1.1.0`은 유지한다.
- Marketplace 배포는 포함하지 않는다.
- 이 문서는 구현 계획이며, 버전 업그레이드 또는 검증 완료를 의미하지 않는다.
