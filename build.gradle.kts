import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.snakeOrCamel"
version = "1.1.0"

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
        create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2")
        bundledPlugin("org.jetbrains.plugins.terminal")
        // JSON is part of the platform in 2024.2; it became a separate plugin in 2024.3.
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // BasePlatformTestCase uses JUnit 3; Vintage runs it alongside the JUnit 5 tests.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginVerification {
        useBundledRuntime.set(true)
        failureLevel.set(listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
        ))
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.4")
        }
    }
}

intellijPlatformTesting.runIde.register("runIde2026") {
    type = IntelliJPlatformType.IntellijIdea
    version = "2026.2.4"
}

val testJava21 = intellijPlatformTesting.testIde.register("testJava21") {
    task {
        description = "Runs unit tests and IntelliJ editor fixture tests on Java 21."
        group = "verification"
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        })
        shouldRunAfter(tasks.test)
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    withType<Test> {
        useJUnitPlatform()
    }

    test {
        // The 2024.2 platform fixture requires Java 21; testJava21 runs these tests.
        exclude("**/CaseConvertActionTest.class")
        exclude("**/*PlatformTest.class")
        javaLauncher.set(project.javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        })
    }

    check {
        dependsOn(testJava21)
    }

    patchPluginXml {
        sinceBuild.set("242")
        changeNotes.set("""
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
