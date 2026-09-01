plugins {
    alias(libs.plugins.android.application)
}

@Suppress("UnstableApiUsage")
android {
    namespace = "app.fjj.p2ptap"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.fjj.p2ptap"
        minSdk = 31
        targetSdk = 37
        versionCode = 1

        val baseVersionName = "1.0"
        val goCommitHash: String = run {
            val envHash = System.getenv("GO_COMMIT_HASH") ?: project.findProperty("GO_COMMIT_HASH")?.toString()
            if (!envHash.isNullOrEmpty()) {
                envHash
            } else {
                try {
                    val candidatePaths = listOf("p2ptap-core", "core", "../p2ptap-core", "../core")
                    var subDir: java.io.File? = null
                    for (p in candidatePaths) {
                        val f = file(p)
                        if (f.exists() && f.isDirectory) {
                            subDir = f
                            break
                        }
                    }
                    if (subDir != null) {
                        val hash = providers.exec {
                            commandLine("git", "rev-parse", "--short", "HEAD")
                            workingDir(subDir)
                            isIgnoreExitValue = true
                        }.standardOutput.asText.orNull?.trim().orEmpty()
                        if (hash.isNotBlank()) hash else "dev"
                    } else {
                        "dev"
                    }
                } catch (_: Exception) {
                    "dev"
                }
            }
        }
        versionName = "$baseVersionName-$goCommitHash"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI Filters for native binaries (support all Android architectures)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    androidResources {
        localeFilters += listOf("en", "zh-rCN", "zh-rHK", "zh-rTW", "de", "es", "fr", "ja", "ko", "ru")
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "DebugProbesKt.bin"
            )
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("KEYSTORE_FILE") ?: project.findProperty("KEYSTORE_FILE")?.toString()
            val storePass = System.getenv("KEYSTORE_PASSWORD") ?: project.findProperty("KEYSTORE_PASSWORD")?.toString()
            val alias = System.getenv("KEY_ALIAS") ?: project.findProperty("KEY_ALIAS")?.toString()
            val keyPass = System.getenv("KEY_PASSWORD") ?: project.findProperty("KEY_PASSWORD")?.toString()

            if (!storeFilePath.isNullOrEmpty() && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(files("libs/p2ptap.aar"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}