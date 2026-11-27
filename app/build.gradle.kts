plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cramqin.hyperfps"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cramqin.hyperfps"
        minSdk = 30
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("hyperfps.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "hyperfps123"
                keyAlias = System.getenv("KEY_ALIAS") ?: "hyperfps"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "hyperfps123"
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            multiDexKeepProguard = file("main-dex-rules.pro")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            multiDexKeepProguard = file("main-dex-rules.pro")
        }
    }

    packaging {
        resources {
            merges += listOf("META-INF/xposed/*")
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}

tasks.withType<com.android.build.gradle.internal.tasks.CheckAarMetadataTask>().configureEach {
    enabled = false
}
