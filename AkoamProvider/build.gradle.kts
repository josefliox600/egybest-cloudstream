// build.gradle.kts pour AkoamProvider
plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Dépendance CloudStream - utilisez la bonne notation pour votre projet
    implementation("com.lagradost:cloudstream3:pre-release")
    // Ou si c'est un projet local:
    // implementation(project(":cloudstream3"))
    
    implementation("org.jetbrains:annotations:24.0.1")
    implementation("org.jsoup:jsoup:1.15.3")
}
