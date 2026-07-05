import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

// Release signing config, sourced from (in priority order) environment
// variables (CI) then a local, git-ignored `key.properties` (see
// key.properties.example). If neither is present, the release build is left
// UNSIGNED so a plain `assembleRelease` still works without any secrets.
val keystorePropsFile = rootProject.file("key.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env) ?: keystoreProps.getProperty(prop)

android {
    namespace = "dev.njr.zync"
    // Bumped to 37 because androidx.core 1.19.0 / lifecycle 2.11.0 require
    // compiling against API 37+. targetSdk stays 36 (compileSdk lets us use
    // newer APIs; bumping targetSdk separately opts into new runtime behavior).
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.njr.zync"
        minSdk = 34
        targetSdk = 36
        // Overridable from CI: `-PzyncVersionCode=<n> -PzyncVersionName=<s>`
        // so each published release gets a monotonically increasing code.
        versionCode = (project.findProperty("zyncVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("zyncVersionName") as String?) ?: "1.0"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingValue("ZYNC_KEYSTORE_FILE", "storeFile")
            if (storeFilePath != null) {
                // rootProject.file resolves a repo-root-relative path (local
                // key.properties) and passes an absolute path (CI) through.
                storeFile = rootProject.file(storeFilePath)
                storePassword = signingValue("ZYNC_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("ZYNC_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("ZYNC_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign only when a keystore was actually configured; otherwise the
            // APK is unsigned (CI provides the keystore via secrets).
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
        excludes += "/META-INF/INDEX.LIST"
        excludes += "/META-INF/io.netty.versions.properties"
      }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // Robolectric unit tests (with isIncludeAndroidResources) read assets from the merged
    // debug-variant assets, not a test-specific assets dir — so the exported Room schemas
    // (needed by MigrationTestHelper) must be wired onto the "debug" sourceSet's assets.
    sourceSets {
        getByName("debug") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity)

  // Room
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)

  // Ktor server stack + serialization
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.server.websockets)
  implementation(libs.ktor.server.status.pages)
  implementation(libs.ktor.network.tls.certificates)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.bouncycastle.bcpkix)
  // Ktor/Netty log via SLF4J; without a binding, exceptions inside route handlers/the Netty
  // engine vanish silently instead of reaching logcat.
  implementation(libs.slf4j.android)

  // On-device QR scanning for pairing (no camera permission required)
  implementation(libs.play.services.code.scanner)
  // On-device document scanning for capture (no camera permission required)
  implementation(libs.play.services.mlkit.document.scanner)

  // Background scheduling for automatic backups
  implementation(libs.androidx.work.runtime.ktx)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.room.testing)
  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.ktor.client.content.negotiation)
  testImplementation(libs.ktor.client.cio)
  testImplementation(libs.androidx.work.testing)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
