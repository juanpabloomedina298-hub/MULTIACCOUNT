plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val twitchClientId = providers.gradleProperty("TWITCH_CLIENT_ID").orElse(providers.environmentVariable("TWITCH_CLIENT_ID")).getOrElse("")

android {
    namespace = "com.example.multichatmanager"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "com.example.multichatmanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.4"
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"$twitchClientId\"")
    }

    // Java and Kotlin must compile to the same JVM target.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Keep Kotlin and Java bytecode targets aligned on JVM 17.
    kotlinOptions {
        jvmTarget = "17"
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
