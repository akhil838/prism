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
        versionName = "0.1.0"
        // From versionName (1.2.3 -> 10203): every release installs over the last (About -> Check for Updates).
        versionCode = versionName!!.split('.').map { it.toInt() }.fold(0) { acc, p -> acc * 100 + p }
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

    // Per-CPU APKs on request (CI: -Pabis=arm64-v8a,armeabi-v7a [-Puniversal=true]). Prism has no
    // native code, so every APK runs on any CPU; the split only labels the download per architecture.
    // Without -Pabis the build makes one universal APK, as before.
    val abis = (findProperty("abis") as String?)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    splits {
        abi {
            isEnable = abis.isNotEmpty()
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = (findProperty("universal") as String?).toBoolean()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The repo's LICENSE and NOTICE ship inside the app (About → Licenses), one source of truth.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/legal").get().asFile)
}

val copyLegal by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
    into(layout.buildDirectory.dir("generated/legal"))
}
tasks.named("preBuild") { dependsOn(copyLegal) }

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
