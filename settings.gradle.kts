rootProject.name = "DrawBoxSample"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":desktopApp")
include(":shared")
include(":webApp")
include(":DrawBox")
include(":drawbox-ui")

// RangVikalp (the color picker) is consumed from the schmidt9 fork as a Gradle
// composite build, so picker fixes are compiled from source instead of the
// published io.ak1:rang-vikalp artifact. The submodule tracks the fork's
// `master`; update it with `git submodule update --remote third_party/rang-vikalp`.
val rangVikalpForkDir = file("third_party/rang-vikalp")
if (rangVikalpForkDir.resolve("settings.gradle.kts").isFile) {
    // The included build has its own root, so it does not see this project's
    // local.properties. Mirror the SDK path so AGP can configure the fork's
    // Android target.
    val sdkLine = file("local.properties")
        .takeIf { it.isFile }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?: System.getenv("ANDROID_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let { "sdk.dir=$it" }
    if (sdkLine != null) {
        runCatching {
            val includedLocal = rangVikalpForkDir.resolve("local.properties")
            if (!includedLocal.exists() || includedLocal.readText() != "$sdkLine\n") {
                includedLocal.writeText("$sdkLine\n")
            }
        }.onFailure {
            logger.warn(
                "Could not mirror sdk.dir into ${rangVikalpForkDir.path}/local.properties: " +
                    "${it.message}. Set ANDROID_HOME if the Android build fails."
            )
        }
    }

    includeBuild(rangVikalpForkDir) {
        dependencySubstitution {
            substitute(module("io.ak1:rang-vikalp")).using(project(":RangVikalp"))
        }
    }
} else {
    throw GradleException(
        "The RangVikalp fork submodule is missing. " +
            "Run `git submodule update --init --recursive` in " +
            "${rootDir.path} and rebuild."
    )
}
