plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // The daemon runs on a headless JRE (no javac); pin a JDK toolchain so the
    // jvm() target's Java compile/test tasks resolve a real compiler (Gradle
    // auto-provisions it via the foojay resolver).
    jvmToolchain(17)

    jvm()
    android {
        namespace = "dev.njr.zync.core"
        compileSdk = 37
        minSdk = 34
        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
