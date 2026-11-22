// Pure Kotlin port of the iOS DSP/ and Analysis/ layers. No Android
// dependencies, so the accuracy and mapping suites run on the JVM:
//   ./gradlew :core:test
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // The iOS sources, for the parity checks (iOS stays the source of truth).
    systemProperty("voicemapper.iosRoot", rootDir.resolve("../VoiceMapper").absolutePath)
    systemProperty("voicemapper.reference", rootDir.resolve("../Tools/DSPCheck/reference").absolutePath)
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
