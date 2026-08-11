plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.techno.roofing"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
