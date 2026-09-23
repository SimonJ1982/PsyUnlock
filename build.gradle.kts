plugins {
    id("com.android.application") version "8.5.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // ✅ ADD THIS LINE — matches Kotlin version
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}