plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.localdrop"
    compileSdk = 35
    defaultConfig { applicationId = "app.localdrop"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "0.3.0"; testInstrumentationRunner = "app.localdrop.WorkflowInstrumentation"; ndk { abiFilters += listOf("arm64-v8a") } }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.google.zxing:core:3.5.3") }
tasks.named("preBuild") {
    doFirst {
        check(file("src/main/jniLibs/arm64-v8a/liblocaldrop_android.so").isFile) {
            "Build the Rust library first: scripts/build-android.ps1"
        }
    }
}
