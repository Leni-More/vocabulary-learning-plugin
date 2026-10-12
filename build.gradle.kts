plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.7.0"
}

group = "com.vocab"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // The IDE to compile against. Point ideaLocalPath at an installed IntelliJ IDEA to skip
        // a large download; see gradle.properties.
        //
        // This must resolve: resolving the platform from the remote repositories instead needs a
        // real published build number, and a wrong one fails deep inside dependency resolution
        // with an error that says nothing about the actual mistake. Failing here instead, with
        // the path in the message, is what a fresh clone needs to see.
        val localIde = providers.gradleProperty("ideaLocalPath").orNull
        if (localIde.isNullOrBlank()) {
            error(
                "ideaLocalPath is not set. Set it in gradle.properties (or pass -PideaLocalPath=…), " +
                    "for example: ideaLocalPath=/Applications/IntelliJ IDEA.app " +
                    "or ideaLocalPath=C:/Program Files/JetBrains/IntelliJ IDEA 2026.2",
            )
        }
        if (!file(localIde).isDirectory) {
            error("ideaLocalPath points at '$localIde', which is not an existing directory.")
        }
        local(localIde)
    }

    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

// The dictionary is intentionally NOT packaged: it is a large download that users fetch
// themselves and import through 选择词典文件. Only plugin code ships in the jar.

kotlin {
    jvmToolchain(21)
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter("5.11.4")
        }
    }
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Tests point vocab.plugin.dir at a temp directory; make sure a stale value never leaks in.
    systemProperty("vocab.plugin.dir", layout.buildDirectory.dir("test-vocab").get().asFile.absolutePath)
}

intellijPlatform {
    // 2026.2 local IDE breaks instrumentCode; not needed (no UI Forms).
    instrumentCode.set(false)

    // The 2026.2 option indexer fails on any locale with
    // "IllegalArgumentException: Locale must be default" (TraverseUIStarter.saveResults),
    // which aborts buildPlugin. There are no UI Forms here, so the index has nothing to index.
    buildSearchableOptions.set(false)

    pluginConfiguration {
        ideaVersion {
            // 242 = IDEA 2024.2, the first release that runs on JBR 21. The classes here are
            // compiled for Java 21 (kotlin.jvmToolchain(21)), so anything older refuses to load
            // them with UnsupportedClassVersionError. Lowering this further means lowering the
            // bytecode target too, not just this number.
            sinceBuild = "242"
        }
    }
}
