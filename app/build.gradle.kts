plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.akhil838.prism"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.akhil838.prism"
        minSdk = 30 // TCL TV runs Android 11
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideload build: sign with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "DEV_TOOLS", "false")
        }
        // Release-identical (minified, not debuggable: real performance) plus the Diag adb switches
        // and PrismMem event log. Same application id, so it installs over a release build.
        //   ./gradlew :app:assembleDev   ->  app/build/outputs/apk/dev/app-dev.apk
        create("dev") {
            initWith(getByName("release"))
            buildConfigField("boolean", "DEV_TOOLS", "true")
            matchingFallbacks += "release"
        }
        debug {
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
