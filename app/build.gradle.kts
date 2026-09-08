import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")
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
        versionCode = 5
        versionName = "1.2.0"
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
        compose = true
    }

    androidResources {
        localeFilters += setOf("en")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencyLocking {
    lockAllConfigurations()
}

configurations.configureEach {
    if (name.startsWith("kotlinCompilerPluginClasspath") || name.startsWith("kspPluginClasspath")) {
        resolutionStrategy.eachDependency {
            if (
                requested.group == "org.jetbrains.kotlinx" &&
                requested.name.startsWith("kotlinx-serialization")
            ) {
                useVersion("1.8.1")
                because("Room 2.8.4 schema serializers need the 1.8.x GeneratedSerializer interface during KSP.")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    val roomVersion = "2.8.4"

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Lifecycle 2.11 requires compileSdk 37 and AGP 9.1, which this toolchain cannot use.
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    baselineProfile(project(":benchmark"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.7.0")
    // Compose UI Test still requests Espresso 3.5.0; pin the current test runtime so
    // instrumentation works on Android 17, where the old reflective input API is gone.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    add("instrumentedTestImplementation", "androidx.compose.ui:ui-test-manifest")
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
