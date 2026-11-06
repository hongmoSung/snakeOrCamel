import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.snakeOrCamel"
version = "1.2.4"

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
            create(IntelliJPlatformType.IntellijIdea, "263.7307.112")
        }
    }
}

// Exercise the actual installed IDE in an isolated sandbox without changing the compile SDK.
providers.gradleProperty("localIdePath").orNull?.let { idePath ->
    intellijPlatform.pluginVerification.ides.local(file(idePath))
    intellijPlatformTesting.runIde.register("runLocalIde") {
        localPath = file(idePath)
        task {
            jvmArgs("-Didea.log.debug.categories=#io.snakeorcamel.agentterminal.AgentSessions")
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
            <h2>1.2.4</h2>
            <ul>
                <li>Run Agent and file references share a dedicated project/profile terminal tab, regardless of tab order.</li>
                <li>Coalesce startup and duplicate references; preserve exited tabs and create a fresh agent tab after confirmed exit.</li>
                <li>Keep session ownership across tab moves and wait safely when process state is unknown.</li>
            </ul>
            <h2>1.2.3</h2>
            <ul>
                <li>Fixed first-terminal reference startup with Powerlevel10k or unavailable Reworked shell integration.</li>
                <li>Queue the default agent once in a newly owned tab before showing Terminal; insert the reference without Enter after startup.</li>
            </ul>
            <h2>1.2.2</h2>
            <ul>
                <li>Replaced deprecated profile chooser dialogs and added compatibility verification for IDEA 2026.3 RC.</li>
                <li>Fixed JSON formatting from Swing button events without an implicit write-intent lock.</li>
            </ul>
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
