plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)
    jvm()
    android {
        namespace = "dev.njr.zync.data"
        compileSdk = 37
        minSdk = 34
        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.sqldelight.runtime)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
    }
}

sqldelight {
    databases {
        create("ZyncDatabase") {
            packageName.set("dev.njr.zync.data.db")
        }
    }
}
