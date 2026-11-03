import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.snakeOrCamel"
version = "1.2.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdea, "2026.2.4")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("com.intellij.modules.json")
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val platformDependency = configurations.named("intellijPlatformDependency")
listOf("compileClasspath", "testCompileClasspath").forEach { classpathName ->
    configurations.named(classpathName) {
        incoming.beforeResolve {
            platformDependency.get().resolve()
        }
    }
}

intellijPlatform {
    pluginVerification {
        failureLevel.set(listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
        ))
        ides {
            current()
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    patchPluginXml {
        sinceBuild.set("262.11879.100")
        changeNotes.set("""
            <h2>1.2.1</h2>
            <ul>
                <li>Replaced internal Reworked Terminal API usage with a public API for session identity tracking.</li>
            </ul>
            <h2>1.2.0</h2>
            <ul>
                <li>Requires IntelliJ IDEA 2026.2.4 or newer with Java 25.</li>
                <li>Added opt-in Agent Terminal integration with project-local Claude, Codex, and Custom launch profiles.</li>
                <li>Added file and selected-line references targeting the first visible Terminal tab without submitting the prompt.</li>
                <li>Added readiness detection, timeout recovery, and reversible shortcut assignment with persistent conflict backups.</li>
                <li>Kept Terminal integration optional so Case Converter and JSON Prettier remain available without the Terminal plugin.</li>
            </ul>
            <h2>1.1.0</h2>
            <ul>
                <li>Added JSON Prettier tool window for formatting JSON</li>
            </ul>
        """.trimIndent())
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
