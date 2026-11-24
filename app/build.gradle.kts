import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// Per-developer settings from local.properties (never committed). To test against the
// local Firebase emulator instead of the deployed function, add this line there:
//     cavcals.useFunctionsEmulator=true
// Optional: cavcals.emulatorHost=192.168.1.20 for a physical phone (default 10.0.2.2, which
// is the laptop as seen from the Android emulator).
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val useFunctionsEmulator = localProps.getProperty("cavcals.useFunctionsEmulator")?.toBoolean() ?: false
val emulatorHost = localProps.getProperty("cavcals.emulatorHost") ?: "10.0.2.2"

android {
    namespace = "com.example.cavcals"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.cavcals"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "USE_FUNCTIONS_EMULATOR", useFunctionsEmulator.toString())
            buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
        }
        release {
            // Release builds always use the deployed function, whatever local.properties says.
            buildConfigField("boolean", "USE_FUNCTIONS_EMULATOR", "false")
            buildConfigField("String", "EMULATOR_HOST", "\"\"")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    // Firestore stores saved meals (users/{uid}/meals). Functions calls the analyzeMeal backend.
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.kotlinx.coroutines.play.services)
    // Google sign-in: Credential Manager shows the account picker, googleid reads the ID token.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
