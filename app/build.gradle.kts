plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { 
    namespace = "com.example.lancast" 
    compileSdk = 34
    
    defaultConfig { 
        applicationId = "com.example.lancast" 
        minSdk = 26 
        targetSdk = 34 
        versionCode = 1 
        versionName = "1.0" 
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
}
