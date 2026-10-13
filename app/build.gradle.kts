import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)

    // for Room
    id("kotlin-kapt")
}

// Secret values (e.g. the Google Maps API key) are read from local.properties,
// which is NOT committed to git
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY", "")
if (mapsApiKey.isBlank()) {
    logger.warn("MAPS_API_KEY not found in local.properties: the map of the Run page will not load.")
}

// Address of the GymBro REST API (server/ folder): API_BASE_URL in local.properties, the server
// online on Vercel (e.g. https://gymbro.vercel.app/). Without it: the server started on this PC
// (10.0.2.2 is the PC as seen from the emulator).
val apiBaseUrl: String = localProperties.getProperty("API_BASE_URL", "http://10.0.2.2:5000/")
if (localProperties.getProperty("API_BASE_URL").isNullOrBlank()) {
    logger.warn("API_BASE_URL not found in local.properties: the app will use the server started on this PC.")
}

android {
    namespace = "com.example.gymbro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gymbro"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Google Maps API key, injected into AndroidManifest.xml as ${MAPS_API_KEY}
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

        // Address of the REST API, readable from the code as R.string.api_base_url.
        // (A resource, not a BuildConfig constant: constants get copied into the compiled code
        // and a changed value may not reach the app without a full rebuild.)
        resValue("string", "api_base_url", apiBaseUrl)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Room (working with database)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Google Maps and location (GPS)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    // Lists (exercise list) and fragments (map)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.fragment)
}