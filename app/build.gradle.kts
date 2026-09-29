import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// Optional isolated output for verification while Android Studio/Drive holds build files.
providers.gradleProperty("driverBuildDir").orNull?.let { layout.buildDirectory.set(file(it)) }

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val mapsApiKey = localProperties.getProperty("MAPS_API_KEY", "")
fun configString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

android { namespace = "tw.driver.schedule"; compileSdk = 35
    defaultConfig {
        applicationId = "tw.driver.schedule"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        buildConfigField("String", "ROUTES_API_KEY", configString(localProperties.getProperty("ROUTES_API_KEY", mapsApiKey)))
        buildConfigField("String", "GEMINI_API_KEY", configString(localProperties.getProperty("GEMINI_API_KEY", "")))
        buildConfigField("String", "GEMINI_MODEL", configString(localProperties.getProperty("GEMINI_MODEL", "gemini-2.5-flash")))
        buildConfigField("String", "DEEPSEEK_API_KEY", configString(localProperties.getProperty("DEEPSEEK_API_KEY", "")))
        buildConfigField("String", "DEEPSEEK_MODEL", configString(localProperties.getProperty("DEEPSEEK_MODEL", "deepseek-flash")))
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.maps.android:maps-compose:6.1.2")
    implementation("com.google.android.libraries.places:places:4.1.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    debugImplementation("androidx.compose.ui:ui-tooling")

    constraints {
        implementation("androidx.core:core-ktx:1.15.0") {
            because("1.19.0 requires compileSdk 37")
        }
        implementation("androidx.core:core:1.15.0") {
            because("1.19.0 requires compileSdk 37")
        }
    }
}
