plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localKey = providers.fileContents(rootProject.layout.projectDirectory.file(".key"))
    .asText.map { it.trim() }.orElse("")
fun String.asJavaString() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    namespace = "com.xpl.a2uichat"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "com.xpl.a2uichat"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEEPSEEK_MODEL", "\"deepseek-flash\"")
        buildConfigField("String", "DEEPSEEK_URL", "\"https://api.deepseek.com/chat/completions\"")
    }
    signingConfigs {
        getByName("debug") { storeFile = rootProject.file(".android-user/debug.keystore") }
    }
    buildTypes {
        debug { buildConfigField("String", "DEEPSEEK_API_KEY", localKey.get().asJavaString()) }
        release { buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

tasks.matching { it.name == "preDebugBuild" }.configureEach {
    doFirst { require(localKey.get().isNotBlank()) { "请在项目根目录 .key 中配置 DeepSeek API Key。" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui:1.13.0-alpha03")
    implementation("androidx.compose.ui:ui-tooling-preview:1.13.0-alpha03")
    implementation("androidx.compose.foundation:foundation:1.13.0-alpha03")
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    implementation("androidx.compose.material3:material3-a2ui:1.0.0-alpha01")
    implementation("androidx.a2ui.compose:compose-ui:1.0.0-alpha01")
    implementation("androidx.a2ui.compose:compose-runtime:1.0.0-alpha01")
    implementation("androidx.a2ui:a2ui-engine:1.0.0-alpha01")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.13.0-alpha03")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.13.0-alpha03")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.13.0-alpha03")
}
