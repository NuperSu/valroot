plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}
kotlin {
    jvmToolchain(25)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    android {
        namespace = "io.valroot.ui"
        compileSdk = 37
        minSdk = 26
    }
    // jvmSharedMain: code for both JVM hosts (desktop and Android), such as the CoinGecko logo download.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
            }
        }
    }
    sourceSets.jvmTest.dependencies {
        implementation(kotlin("test"))
        implementation(compose.desktop.uiTestJUnit4)
        implementation(compose.desktop.currentOs)
    }
    sourceSets.commonMain.dependencies {
        implementation(project(":core"))
        implementation(libs.compose.backhandler)
        implementation(libs.compose.runtime)
        implementation(libs.compose.foundation)
        implementation(libs.compose.material3)
        // Local dates and times in the device's time zone on every platform.
        implementation(libs.kotlinx.datetime)
        implementation(libs.atomicfu)
    }
}
