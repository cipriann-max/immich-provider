import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Version of the Immich server this build's API layer was generated from and tested against.
 * Bump the value here and regenerate the client from the OpenAPI spec of the new release.
 * See README.md -> "Updating for a new Immich release".
 */
val testedImmichVersion = "3.4.1"

/** Version of the OpenAPI document used by tools/openapi/generate_models.py. */
val testedImmichFeatureLevel = 2

/**
 * Signing configuration, resolved from two places so the same build script works on a
 * developer machine and in CI:
 *
 *  1. `keystore.properties` in the project root (gitignored) for local release builds.
 *  2. Environment variables for CI, where the keystore is restored from a secret onto
 *     the runner and no properties file exists.
 *
 * The environment wins only when it is complete, so a stale local file cannot silently
 * produce a differently-signed APK in a release pipeline.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

private fun envOrNull(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }

val envStoreFile = envOrNull("RELEASE_KEYSTORE_FILE")
val envStorePassword = envOrNull("RELEASE_KEYSTORE_PASSWORD")
val envKeyAlias = envOrNull("RELEASE_KEY_ALIAS")
val envKeyPassword = envOrNull("RELEASE_KEY_PASSWORD")

val signingStoreFile = envStoreFile ?: keystoreProperties.getProperty("storeFile")
val signingStorePassword = envStorePassword ?: keystoreProperties.getProperty("storePassword")
val signingKeyAlias = envKeyAlias ?: keystoreProperties.getProperty("keyAlias")
val signingKeyPassword = envKeyPassword ?: keystoreProperties.getProperty("keyPassword")

/** A release APK is signed only when the keystore and every credential are present. */
val signingKeystoreFile: File? = signingStoreFile
    ?.let { rootProject.file(it) }
    ?.takeIf { file ->
        file.exists() &&
            !signingStorePassword.isNullOrBlank() &&
            !signingKeyAlias.isNullOrBlank() &&
            !signingKeyPassword.isNullOrBlank()
    }

/** Default version when nothing overrides it. */
val defaultVersionName = "1.1.0"

/**
 * Version stamped into the APK, overridable from a git tag in CI or from `release.sh`
 * locally. The name is read from an environment variable or a Gradle property, whichever
 * the caller used; the code is derived from the name so releases always sort correctly.
 *
 * Derivation: `major * 10000 + minor * 100 + patch`, so 1.0.0 -> 10000, 1.2.3 -> 10203,
 * and this release's 1.1.0 -> 10100.
 * An explicit RELEASE_VERSION_CODE always wins, for a build that must not move the code.
 */
val versionNameOverride = envOrNull("RELEASE_VERSION_NAME")
    ?: (project.findProperty("releaseVersionName") as String?)?.takeIf { it.isNotBlank() }

val versionCodeOverride = (envOrNull("RELEASE_VERSION_CODE")
    ?: (project.findProperty("releaseVersionCode") as String?))
    ?.toIntOrNull()
    ?: versionNameOverride?.let { name ->
        val parts = name.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size >= 3) parts[0] * 10_000 + parts[1] * 100 + parts[2] else null
    }

android {
    // Kotlin package for R and BuildConfig. Independent of applicationId below, and kept
    // as-is so the 200+ source files and generated API models need no churn.
    namespace = "dev.immich.picker"
    compileSdk = 36

    signingConfigs {
        if (signingKeystoreFile != null) {
            create("release") {
                storeFile = signingKeystoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                // v1 for API 26 devices, v2/v3 for modern installs.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        /**
         * The app's public identity. Reverse-DNS of a domain Virtunio Ltd controls, so the
         * id does not imply affiliation with the Immich project (`immich.dev` belongs to
         * them, and this app is an independent third-party client).
         *
         * Changing this again would make the app a *different* install: no upgrade path,
         * and stored credentials and caches would not carry over.
         */
        applicationId = "com.virtunio.immichpicker"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOverride ?: run {
            val parts = defaultVersionName.split('.').mapNotNull { it.toIntOrNull() }
            parts[0] * 10_000 + parts[1] * 100 + parts[2]
        }
        versionName = versionNameOverride ?: defaultVersionName

        buildConfigField("String", "TESTED_IMMICH_VERSION", "\"$testedImmichVersion\"")
        buildConfigField("int", "TESTED_IMMICH_FEATURE_LEVEL", testedImmichFeatureLevel.toString())
        buildConfigField("String", "GENERATED_FROM_SPEC_VERSION", "\"$testedImmichVersion\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Left unset when no keystore is configured, so a contributor without signing
            // material can still compile. Such an APK is `-unsigned` and not shippable.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main").kotlin.srcDir("src/main/kotlin")
        getByName("test").kotlin.srcDir("src/test/kotlin")
        getByName("debug").kotlin.srcDir("src/debug/kotlin")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 2026.06.01 resolves to compose-ui 1.11.4 / material3 1.4.0, both of which
    // compile against API 35+. Newer BOMs require compileSdk 37 and AGP 9.x.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Needed for Icons.Filled in SettingsScreen. The *extended* set was only used by the
    // removed in-app picker grid; the core set is what remains necessary.
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.security:security-crypto:1.1.0")

    // 5.3+ ships an okhttp-android artifact that wants compileSdk 37.
    implementation("com.squareup.okhttp3:okhttp:5.2.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.2.3")
}
