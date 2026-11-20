import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.esos.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.esos.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 40
        versionName = "1.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        val propertiesFile = File(System.getProperty("user.home"), ".android-toolchain/esos-signing.properties")
        if (propertiesFile.isFile) {
            val signing = Properties().apply { propertiesFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.findByName("release") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

val semanticVersion = android.defaultConfig.versionName
    ?: error("Set android.defaultConfig.versionName before exporting an APK")
require(Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""").matches(semanticVersion)) {
    "versionName must be MAJOR.MINOR.PATCH SemVer without a prerelease suffix"
}

tasks.register<Copy>("exportDebugApk") {
    group = "distribution"
    description = "Build and export the debug APK as eSOS-v<version>.apk"
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "eSOS-v$semanticVersion.apk" }
    finalizedBy("syncDebugApk")
}

tasks.register<Copy>("exportReleaseApk") {
    group = "distribution"
    description = "Build and export the signed release APK as eSOS-v<version>.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "eSOS-v$semanticVersion.apk" }
    finalizedBy("syncDebugApk")
}

tasks.register<Copy>("syncDebugApk") {
    group = "distribution"
    description = "Copy the exported APK to the Sync apps folder"
    val target = File(System.getProperty("user.home"), "Sync/apps/Apks")
    from(rootProject.layout.projectDirectory.file("dist/eSOS-v$semanticVersion.apk"))
    into(target)
    onlyIf { target.isDirectory }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.alphacephei:vosk-android:0.3.75")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}