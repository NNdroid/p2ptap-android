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
        // versionCode tracks releases, not commits: it is the number of
        // published v* tags plus one, so it goes up once per release and stays
        // put for every commit in between (two builds of the same pending
        // release produce the same code). Counting commits instead made every
        // stray commit bump it. APP_VERSION_CODE overrides it for an
        // Android-only release that must not reuse the previous code.
        // NOTE: this counts *local* tags. CI sees them only because
        // actions/checkout fetches tags at fetch-depth 0; a tag that was never
        // pushed is invisible there and would make CI compute a lower code.
        val releaseTagCount = providers.exec {
            workingDir(rootDir)
            commandLine("git", "tag", "--list", "v*")
        }.standardOutput.asText.get().lineSequence().filter { it.isNotBlank() }.count()
        versionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull()
            ?: (releaseTagCount + 1).coerceAtLeast(1)

        // versionName names the app, so it is derived from this repo, not from
        // p2ptap-core: the APK version, the release tag and the About card
        // (MainActivity reads PackageInfo.versionName) should all track the
        // Android build. The Go engine still carries its own version, stamped
        // into p2ptap.aar via P2PTAP_VERSION, and is unaffected by this.
        // APP_VERSION_NAME remains as an override for a one-off release.
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

    // The Go engine ships one ~34 MB libgojni.so per ABI, which made the single
    // universal APK ~143 MB. Publish a ~37 MB APK per ABI alongside it.
    // isUniversalApk keeps the all-ABI APK on disk: release.yml reads the
    // version pair out of it (aapt2 dump badging), so dropping it would break
    // version naming.
    //
    // All the outputs intentionally share one versionCode. Play Store requires
    // a distinct code per APK, but these ship as GitHub Release downloads, where
    // a shared code is what lets a user switch variants in place: installing a
    // lower code over a higher one is blocked as a downgrade, which would force
    // uninstalling the app — and that drops the VPN profile with it.
    splits {
        abi {
            isEnable = !project.hasProperty("NO_SPLITS")
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = !project.hasProperty("NO_SPLITS")
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
    // VPN service, config, i18n and state layers, shared with :tv.
    // :core pulls in the Go engine AAR as an `api` dependency, so both the
    // Java surface and the per-ABI libgojni.so propagate here.
    implementation(project(":core"))
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
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
