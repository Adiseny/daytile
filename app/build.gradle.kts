import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.privateplanner"
    compileSdk = 36
    testBuildType = "instrumentedTest"

    val releaseStoreFile = providers.environmentVariable("DAYTILE_RELEASE_STORE_FILE").orNull
    val releaseStorePassword = providers.environmentVariable("DAYTILE_RELEASE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("DAYTILE_RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("DAYTILE_RELEASE_KEY_PASSWORD").orNull

    defaultConfig {
        applicationId = "com.privateplanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.4.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (
            !releaseStoreFile.isNullOrBlank() &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        create("instrumentedTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".instrumented"
            matchingFallbacks += "debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles("proguard-rules.pro")
            // The source is public; the APK need not carry a record of the checkout.
            vcsInfo.include = false
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
        buildConfig = true
    }

    androidResources {
        localeFilters += setOf("en")
        // The reminder chime is played from a file descriptor, which a deflated
        // entry cannot give. aapt2 stores .ogg and .wav uncompressed already but
        // has no such rule for .flac.
        noCompress += "flac"
    }

    // The encrypted dependency report is for Play; a sideloaded APK only carries its bytes.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        // Stored, not compressed. Android 9 and later run a stored dex straight from the APK;
        // a compressed one is unpacked at install into a second, full-size copy that stays on
        // the device beside the APK. Prefer lower installed storage to a smaller download.
        dex {
            useLegacyPackaging = false
        }
        resources {
            // Build and reflection metadata nothing reads at runtime; the app ships no
            // Kotlin reflection, the only reader of the module and builtins files.
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "kotlin/**",
                "kotlin-tooling-metadata.json"
            )
        }
    }

}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

tasks.register("verifyPrivacy") {
    dependsOn("processReleaseMainManifest")

    doLast {
        val manifest = fileTree(layout.buildDirectory.dir("intermediates/merged_manifest/release")) {
            include("**/AndroidManifest.xml")
        }.files.firstOrNull()
            ?: error("Merged release manifest was not found")

        val manifestText = manifest.readText()
        if ("<permission" in manifestText) {
            error("Release manifest must not declare custom permissions")
        }

        // Reminders need these four and nothing else. None grants access to any
        // data; INTERNET in particular can never be added without failing here.
        val allowedPermissions = setOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.RECEIVE_BOOT_COMPLETED"
        )
        val declaredPermissions = Regex("<uses-permission[^>]*?android:name=\"([^\"]+)\"")
            .findAll(manifestText)
            .map { match -> match.groupValues[1] }
            .toSet()
        val unexpectedPermissions = declaredPermissions - allowedPermissions
        if (unexpectedPermissions.isNotEmpty()) {
            error("Release manifest declares unexpected permissions: $unexpectedPermissions")
        }
        if ("android:usesCleartextTraffic=\"true\"" in manifestText) {
            error("Cleartext traffic must not be enabled")
        }

        val bannedFragments = listOf(
            "firebase",
            "crashlytics",
            "google-analytics",
            "amplitude",
            "mixpanel",
            "sentry",
            "facebook",
            "play-services-ads",
            "appsflyer",
            "adjust",
            "segment",
            "retrofit",
            "okhttp",
            "ktor-client",
            "volley",
            "openai"
        )

        val runtimeDependencies = configurations
            .getByName("releaseRuntimeClasspath")
            .resolvedConfiguration
            .resolvedArtifacts
            .joinToString(separator = "\n") { dependency ->
                "${dependency.moduleVersion.id.group}:${dependency.name}:${dependency.moduleVersion.id.version}"
                    .lowercase(Locale.US)
            }

        bannedFragments.forEach { banned ->
            if (banned in runtimeDependencies) {
                error("Forbidden dependency detected: $banned")
            }
        }

        val sourceText = fileTree("src/main/java") {
            include("**/*.kt")
        }.files.joinToString(separator = "\n") { it.readText() }

        val forbiddenLoggingTokens = listOf("android.util.Log", "Log.", "println(")
        forbiddenLoggingTokens.forEach { token ->
            if (token in sourceText) {
                error("Planner builds must not contain logging token: $token")
            }
        }
    }
}

// Gate every task that can produce a distributable release artefact. App bundle tasks do not
// depend on assembleRelease, so the privacy check must also sit on the package tasks.
val releaseDistributionTasks = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "packageReleaseBundle"
)
tasks.configureEach {
    if (name in releaseDistributionTasks) {
        dependsOn("verifyPrivacy")
    }
}
