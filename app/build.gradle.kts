import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ksp)
}

val releaseKeyPropertiesFile = rootProject.file("key.properties")
val releaseKeyProperties = Properties().apply {
    if (releaseKeyPropertiesFile.isFile) {
        releaseKeyPropertiesFile.inputStream().use(::load)
    }
}

fun releaseValue(propertyName: String, envName: String): String? =
    (releaseKeyProperties.getProperty(propertyName) ?: providers.environmentVariable(envName).orNull)
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseValue("storeFile", "ZYNC_KEYSTORE_FILE")
val releaseStorePassword = releaseValue("storePassword", "ZYNC_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseValue("keyAlias", "ZYNC_KEY_ALIAS")
val releaseKeyPassword = releaseValue("keyPassword", "ZYNC_KEY_PASSWORD")
val hasReleaseSigning =
    releaseStoreFile != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

val zyncVersionCode = (
    findProperty("zync.versionCode")?.toString()
        ?: providers.environmentVariable("ZYNC_VERSION_CODE").orNull
        ?: "1"
).toInt()

val zyncVersionName = (
    findProperty("zync.versionName")?.toString()
        ?: providers.environmentVariable("ZYNC_VERSION_NAME").orNull
        ?: "1.0"
)

android {
    namespace = "dev.njr.zync"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.njr.zync"
        minSdk = 34
        targetSdk = 36
        versionCode = zyncVersionCode
        versionName = zyncVersionName
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
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

gradle.taskGraph.whenReady {
    val validatesReleaseSigning = allTasks.any { task ->
        task.name == "validateSigningRelease"
    }
    if (validatesReleaseSigning && !hasReleaseSigning) {
        error(
            "Release builds require signing values in key.properties or env vars: " +
                "ZYNC_KEYSTORE_FILE, ZYNC_KEYSTORE_PASSWORD, ZYNC_KEY_ALIAS, ZYNC_KEY_PASSWORD"
        )
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
  implementation(libs.glance)
  implementation(libs.glance.appwidget)
  // Ktor/Netty log via SLF4J; without a binding, exceptions inside route handlers/the Netty
  // engine vanish silently instead of reaching logcat.
  implementation(libs.slf4j.android)

  // On-device QR scanning for pairing (no camera permission required)
  implementation(libs.play.services.code.scanner)
  implementation(libs.play.services.mlkit.document.scanner)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.room.testing)
  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.ktor.client.content.negotiation)
  testImplementation(libs.ktor.client.cio)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
