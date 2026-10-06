import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
        // APK build sequence, monotonic with this repo's history so every
        // rebuild gets a higher code. APP_VERSION_CODE overrides it for an
        // Android-only release that must not reuse the previous code.
        val appRevision = providers.exec {
            workingDir(rootDir)
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.get().trim().toInt()
        versionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: appRevision.coerceAtLeast(1)

        // Identical to what the Go engine reports, so the APK metadata and the
        // About card can never disagree. CI exports APP_VERSION_NAME straight
        // from p2ptap-core/scripts/get_version.sh; local builds derive the same
        // format from the core submodule below, so keep the two in sync. The
        // last resort is the older 1.0-<hash> form, which still names the
        // engine the supplied AAR was built from.
        val coreDir = rootDir.resolve("p2ptap-core")
        val coreVersion: String? = if (coreDir.isDirectory) {
            val coreCount = providers.exec {
                workingDir(coreDir)
                commandLine("git", "rev-list", "--count", "HEAD")
            }.standardOutput.asText.get().trim()
            val coreHash = providers.exec {
                workingDir(coreDir)
                commandLine("git", "rev-parse", "--short=7", "HEAD")
            }.standardOutput.asText.get().trim()
            "v1.0.${ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd"))}.${coreCount}-${coreHash}"
        } else {
            null
        }
        versionName = System.getenv("APP_VERSION_NAME")
            ?: project.findProperty("APP_VERSION_NAME")?.toString()
            ?: coreVersion
            ?: "1.0-${System.getenv("GO_COMMIT_HASH") ?: project.findProperty("GO_COMMIT_HASH")?.toString() ?: "dev"}"

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
            if (providers.gradleProperty("TEST_SIGNING").orNull == "true") {
                signingConfig = signingConfigs.getByName("debug")
            } else if (releaseSigning?.storeFile?.exists() == true) {
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
    // Android's local-test org.json classes are stubs; exercise real Go JSON fixtures.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
