plugins {
    alias(libs.plugins.dev)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** The one source of truth for the app version; release tags must match it. */
val appVersionName = "1.0.0"

android {
    namespace = "com.dhatech.roku.cast"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dhatech.roku.cast"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = appVersionName
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Release signing is environment-driven so the same code path serves local
    // builds and CI: point KITE_KEYSTORE_PATH at the keystore and supply the
    // passwords, or the release APK stays unsigned.
    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystorePath = System.getenv("KITE_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KITE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KITE_KEY_ALIAS")
                keyPassword = System.getenv("KITE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // A release is the clean version — the suffix logic below deliberately
        // does not exist here, so no environment variable can dirty it.
        release {
            signingConfigs.findByName("release")?.takeIf { it.storeFile != null }?.let {
                signingConfig = it
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Canary: real-device behaviour with logcat and debugger access —
        // debuggable, not minified, but signed with the release key so it can
        // replace (and be replaced by) released installs of the same app.
        // CI appends e.g. "-canary.42-ab12cd3" so installs are identifiable.
        create("canary") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            matchingFallbacks += listOf("release")
            versionNameSuffix = System.getenv("KITE_VERSION_SUFFIX")?.let { "-$it" }.orEmpty()
        }
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
            versionNameSuffix = System.getenv("KITE_VERSION_SUFFIX")?.let { "-$it" }.orEmpty()
        }
    }

    buildFeatures {
        compose = true
    }

    bundle {
        abi {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        language {
            // Keep all 11 locales bundled so Android 13+ per-app language switching works offline.
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        }
    }
}

dependencies {
    implementation(project(":airplay"))
    implementation(project(":capture"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // AppCompatDelegate.setApplicationLocales() drives the per-app language,
    // which the system Settings entry also reads.
    implementation(libs.androidx.appcompat)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.leakcanary.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    detektPlugins(libs.detekt.rules.ktlint.wrapper)
}

detekt {
    buildUponDefaultConfig = true
    autoCorrect = true
    config.setFrom("$rootDir/detekt.yml")
    baseline = file("$projectDir/detekt-baseline.xml")
}
