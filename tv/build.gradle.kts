import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
}

@Suppress("UnstableApiUsage")
android {
    namespace = "app.fjj.p2ptap.tv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Same applicationId as :mobile, on purpose: the TV build is the same
        // app for a different form factor, not a second product. Package name
        // decides the data directory, so the node identity lives in the same
        // place a phone user expects it — and moving an identity between
        // devices is a file copy, not a re-pairing exercise.
        //
        // It also means the two APKs cannot be installed side by side on one
        // device. That is fine and intentional: they target different
        // characteristics (:mobile is a phone, this one declares
        // android.software.leanback required=true), and they share one
        // signing key, so a switch between them is an in-place replace.
        applicationId = "app.fjj.p2ptap"
        minSdk = 31
        targetSdk = 37

        // Same version derivation as :mobile, on purpose: this module is built
        // from the same tree in the same release, so both form factors move in
        // lockstep. Sharing versionCode is a feature here, not a conflict —
        // with one applicationId it is exactly what lets a user replace the
        // phone build with the TV build without a downgrade-rejected install.
        // Note: this counts *local* tags, visible to CI only because
        // actions/checkout fetches tags at fetch-depth 0.
        val releaseTagCount = providers.exec {
            workingDir(rootDir)
            commandLine("git", "tag", "--list", "v*")
        }.standardOutput.asText.get().lineSequence().filter { it.isNotBlank() }.count()
        versionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull()
            ?: (releaseTagCount + 1).coerceAtLeast(1)

        val repoCount = providers.exec {
            workingDir(rootDir)
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.get().trim()
        val repoHash = providers.exec {
            workingDir(rootDir)
            commandLine("git", "rev-parse", "--short=7", "HEAD")
        }.standardOutput.asText.get().trim()
        val repoVersion =
            "v1.0.${ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd"))}.${repoCount}-${repoHash}"
        versionName = System.getenv("APP_VERSION_NAME")
            ?: project.findProperty("APP_VERSION_NAME")?.toString()
            ?: repoVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Support all Android architectures, matching :mobile.
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

    // The Go engine ships one ~34 MB libgojni.so per ABI, which would make a
    // universal APK ~143 MB. Publish a ~37 MB APK per ABI alongside it, with
    // isUniversalApk keeping the all-ABI APK for release.yml's version extraction.
    //
    // All outputs share one versionCode, matching :mobile: this is what lets a
    // user switch variants in place without a downgrade-rejected install.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
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
        // Needed for src/main/aidl. Kept off everywhere else in the repo.
        aidl = true
    }
}

dependencies {
    // VPN service, config, i18n and state layers, shared with :mobile.
    // :core pulls in the Go engine AAR as an `api` dependency, so both the
    // Java surface and the per-ABI libgojni.so propagate here.
    implementation(project(":core"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    // Material Design 3: the color, type and shape tokens the shell and every
    // screen render against. `Theme.Material3.DayNight.NoActionBar` is the
    // parent of Theme.P2ptap.Tv (see res/values/themes_tv.xml). We keep leanback
    // as the RecyclerView runtime for TV — it understands focus — and layer MD3
    // tokens on top for visual identity.
    implementation(libs.material)
    // Navigation Component: the shell is one Activity hosting a NavHostFragment,
    // so destinations change without leaving the process. This replaces the
    // eight per-screen Activities that predated the redesign.
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    // QR encoding only. The TV cannot scan — there is no camera — so the
    // zxing-android-embedded camera library that :mobile uses is not needed,
    // and this one is what writes the code a phone camera reads.
    implementation(libs.zxing.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.leanback.grid)
    // L3 keep-alive. Every call site is gated on Shizuku.pingBinder(), so an
    // app without Shizuku installed behaves exactly as if these were absent.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
