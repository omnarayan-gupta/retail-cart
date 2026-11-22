import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spotless) apply false
}

// Shared conventions for every Java module (apps/* and libs/*).
subprojects {
    group = "com.target.retailcart"
    version = "0.0.1-SNAPSHOT"

    plugins.withId("java") {
        apply(plugin = "com.diffplug.spotless")
        apply(plugin = "jacoco")

        configure<JavaPluginExtension> {
            toolchain { languageVersion = JavaLanguageVersion.of(21) }
        }

        // Formatting is enforced: `check` (and therefore `build`) fails on unformatted code.
        configure<SpotlessExtension> {
            lineEndings = com.diffplug.spotless.LineEnding.UNIX
            java {
                target("src/**/*.java")
                googleJavaFormat()
                removeUnusedImports()
                trimTrailingWhitespace()
                endWithNewline()
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.compilerArgs.add("-parameters")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            finalizedBy("jacocoTestReport")
        }

        tasks.named<JacocoReport>("jacocoTestReport") {
            reports {
                xml.required = true
                html.required = true
            }
        }
    }
}
