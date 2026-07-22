plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The wire types (OpEnvelope/OpIntent/EnvelopeResult/BlobKeyResult) are the contract.
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
