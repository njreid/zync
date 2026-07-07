# ProGuard/R8 rules for the release build.
#
# Minification is currently disabled (isMinifyEnabled = false in
# app/build.gradle.kts), so these rules are not applied yet. This file exists so
# the proguardFiles(...) reference resolves and so rules are in place the moment
# minification is turned on.
#
# When enabling R8, keep rules for Ktor/Netty reflection, kotlinx.serialization
# generated serializers, and Room here.
