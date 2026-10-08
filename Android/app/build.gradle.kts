import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val configuredApiUrl = providers.gradleProperty("API_BASE_URL").orNull
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.fragpicker.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fragpicker.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        debug { buildConfigField("String", "API_BASE_URL", quoted(configuredApiUrl ?: "http://10.0.2.2:18080")) }
        release { buildConfigField("String", "API_BASE_URL", quoted(configuredApiUrl ?: "https://unconfigured.invalid")) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        val uri = configuredApiUrl?.let { URI(it) }
        require(uri?.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Release requires -PAPI_BASE_URL=https://your-backend; do not include credentials or query parameters"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.github.kyant0:backdrop:1.0.0")
    implementation("io.noties.markwon:core:4.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
