import org.gradle.api.GradleException
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val isReleaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}

android {
    namespace = "com.dualapps.cloner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dualapps.cloner"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (!keystorePropsFile.exists()) {
                if (isReleaseTaskRequested) {
                    throw GradleException("Release signing failed: keystore.properties not found in project root.")
                }
            } else {
                val storeFilePath = keystoreProps.getProperty("storeFile")?.trim().orEmpty()
                val alias = keystoreProps.getProperty("keyAlias")?.trim().orEmpty()
                val storePass = keystoreProps.getProperty("storePassword")?.trim()
                    .takeUnless { it.isNullOrEmpty() }
                    ?: System.getenv("DUALAPPS_STORE_PASSWORD").orEmpty()
                val keyPass = keystoreProps.getProperty("keyPassword")?.trim()
                    .takeUnless { it.isNullOrEmpty() }
                    ?: System.getenv("DUALAPPS_KEY_PASSWORD").orEmpty()

                if (isReleaseTaskRequested &&
                    (storeFilePath.isEmpty() || alias.isEmpty() || storePass.isEmpty() || keyPass.isEmpty())
                ) {
                    throw GradleException(
                        "Release signing failed: keystore.properties is missing storeFile, keyAlias, or passwords."
                    )
                }
                if (storeFilePath.isNotEmpty()) {
                    storeFile = file(storeFilePath)
                }
                keyAlias = alias
                storePassword = storePass
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.all {
            it.testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    // Mesin signing & keystore per perangkat (terkunci Bagian 1)
    implementation("com.android.tools.build:apksig:8.5.2")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78")

    // AndroidX & UI (Views + ViewBinding, tanpa Compose)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Unit test
    testImplementation("junit:junit:4.13.2")
}
