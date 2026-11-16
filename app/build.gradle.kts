plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.toast.nothingwidgets"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.toast.nothingwidgets"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
    }
    signingConfigs {
        create("release") {
            // keystore + passwords live outside the repo (~/.gradle/gradle.properties)
            storeFile = rootProject.file("release.jks")
            storePassword = (project.findProperty("NW_STORE_PASSWORD") as String?) ?: ""
            keyAlias = "nothingwidgets"
            keyPassword = (project.findProperty("NW_KEY_PASSWORD") as String?) ?: ""
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            // ./gradlew assembleRelease -Prtest : side-by-side install for testing the R8 build
            if (project.hasProperty("rtest")) applicationIdSuffix = ".rtest"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
