// Windows desktop terminal. Shares :core (MFP client, streams, risk, settings) with the Android app.
//   Run:              ./gradlew :desktop:run
//   Portable folder:  ./gradlew :desktop:createReleaseDistributable   -> desktop/build/compose/binaries/main-release/app/
//   Installer:        ./gradlew :desktop:packageReleaseMsi
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Same fonts and launcher icon as the Android app, straight from its resources.
sourceSets.main {
    resources.srcDirs("../app/src/main/res/font", "../app/src/main/res/mipmap-xxxhdpi")
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    // Windows DPAPI for the API key file.
    implementation("net.java.dev.jna:jna-platform:5.15.0")
}

compose.desktop {
    application {
        mainClass = "com.alex.mfppro.desktop.MainKt"
        jvmArgs += listOf("-Xss2m", "-XX:+UseZGC", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Delta1kx"
            packageVersion = "0.1.0"
            description = "Delta 1kx trading terminal"
            modules("java.naming", "jdk.unsupported", "java.net.http")
            windows {
                menuGroup = "Delta 1kx"
                upgradeUuid = "6f1a3c52-9e0b-4b7e-a1d4-2c5f8e7b9d10"
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

// Headless connection check: ./gradlew :desktop:smoke
tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Loads MFP markets and a live BTC price through :core, without a window or key."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.alex.mfppro.desktop.smoke.SmokeKt")
}

// Offscreen render of the workspace with live public data: ./gradlew :desktop:snapshot -Pout=shot.png
tasks.register<JavaExec>("snapshot") {
    group = "verification"
    description = "Renders the workspace to a PNG without opening a window."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.alex.mfppro.desktop.smoke.SnapshotKt")
    args(
        providers.gradleProperty("out").getOrElse(layout.buildDirectory.file("snapshot.png").get().asFile.path),
        providers.gradleProperty("grid").getOrElse(""),
        providers.gradleProperty("bands").getOrElse("false"),
    )
}
