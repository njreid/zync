plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":web"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.awssdk.s3)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.zxing.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sse)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.njr.zync.server.MainKt")
}

// Dev-only: serve the shared :web UI in-memory for headless browser (Playwright) testing.
tasks.register<JavaExec>("webDevServer") {
    group = "application"
    description = "Run the in-memory dev server that serves the :web UI (for Playwright)."
    mainClass.set("dev.njr.zync.server.DevServerKt")
    classpath = sourceSets["main"].runtimeClasspath
}
