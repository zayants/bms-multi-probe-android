plugins {
    id("com.android.application")
}

android {
    namespace = "com.zayants.bmsmultiprobe"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.zayants.bmsmultiprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.3.7"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
