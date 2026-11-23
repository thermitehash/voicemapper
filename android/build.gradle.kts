plugins {
    id("com.android.application") version "9.5.2" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}

// Documents is synced by iCloud Drive, which duplicates files in busy build
// folders ("Foo 2.class") and breaks R8 and dexing. iCloud skips names ending
// in .nosync, so build there instead.
subprojects {
    layout.buildDirectory = rootDir.resolve("${project.name}/build.nosync")
}
