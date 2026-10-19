import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Build-time switches (all optional, all safe to ignore)
//
//   -Pofflinegguf.llama=true     force the real llama.cpp engine on; when the
//                                pinned source is synced under app/llama-cpp it
//                                is detected and enabled automatically.
//   -Pofflinegguf.llamaDir=PATH  use a llama.cpp checkout outside the repo.
//   -Pofflinegguf.abis=arm64-v8a  restrict ABIs (faster local / CI builds).
//   -Pofflinegguf.minify=true    enable R8 + resource shrinking for release.
//
//   keystore.properties OR KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/
//   KEY_PASSWORD env vars       -> signed release APK. Without them the
//   release build falls back to the debug key so CI artifacts stay installable.
//   ONE keystore signs every release forever (scripts/setup-keystore.sh,
//   docs/SIGNING.md) — that is what makes every future APK update in place.
// ---------------------------------------------------------------------------
fun flag(name: String): Boolean =
    (project.findProperty(name) as String?)?.toBooleanStrictOrNull() == true

val defaultLlamaDir = file("llama-cpp")
val llamaEnabled: Boolean = flag("offlinegguf.llama") ||
    System.getenv("OFFLINEGGUF_LLAMA").toBoolean() ||
    defaultLlamaDir.resolve("include/llama.h").isFile
val llamaDir: File = (project.findProperty("offlinegguf.llamaDir") as String?)
    ?.let { file(it) }
    ?: file("llama-cpp")
val configuredAbis: List<String> = (project.findProperty("offlinegguf.abis") as String?)
    ?.split(",", " ")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val minifyEnabledRelease: Boolean = flag("offlinegguf.minify")

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun keystoreValue(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val hasReleaseKeystore = keystoreValue("storeFile", "KEYSTORE_FILE") != null &&
    keystoreValue("storePassword", "KEYSTORE_PASSWORD") != null &&
    keystoreValue("keyAlias", "KEY_ALIAS") != null &&
    keystoreValue("keyPassword", "KEY_PASSWORD") != null

android {
    namespace = "com.offlinegguf.chat"
    compileSdk = 34
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.offlinegguf.chat"
        minSdk = 24
        targetSdk = 34
        versionCode = (project.findProperty("APP_VERSION_CODE") as String?)?.toIntOrNull() ?: 5
        versionName = (project.findProperty("APP_VERSION_NAME") as String?) ?: "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DOFFLINEGGUF_WITH_LLAMA=${if (llamaEnabled) "ON" else "OFF"}",
                    "-DLLAMA_CPP_DIR=${llamaDir.absolutePath}",
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_CXX_FLAGS=-O2 -ffunction-sections -fdata-sections",
                )
                abiFilters += configuredAbis
            }
        }
        ndk {
            abiFilters += configuredAbis
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreValue("storeFile", "KEYSTORE_FILE")!!)
                storePassword = keystoreValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = keystoreValue("keyAlias", "KEY_ALIAS")
                keyPassword = keystoreValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = minifyEnabledRelease
            isShrinkResources = minifyEnabledRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = when {
                hasReleaseKeystore -> signingConfigs.getByName("release")
                else -> signingConfigs.getByName("debug")
            }
            ndk {
                debugSymbolLevel = "none"
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=all",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "META-INF/*.version",
                "/kotlin/**",
                "/kotlinx/**",
                "DebugProbesKt.bin",
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        // Reports are published by CI as artifacts; they must not red the build.
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
        htmlReport = true
        xmlReport = true
        disable += setOf("ObsoleteLintCustomCheckers", "TextFields", "UnusedMaterial3ScaffoldPaddingParameter")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/samples")
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// A failing test has to name itself on the console. CI reads stdout, and the
// HTML/XML reports live on a host that is not always reachable from a sandbox.
tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = false
    }
}

// Surfaces the effective native-engine configuration on every build so a CI
// log tells you *why* inference is or isn't available.
val printEngineConfig = tasks.register("engineConfig") {
    group = "offlinegguf"
    description = "Prints which native inference backend the APK will contain."
    val marker = if (llamaEnabled) "llama.cpp (${llamaDir})" else "none (UI + GGUF tooling only)"
    doLast {
        logger.lifecycle("Native inference backend : $marker")
        logger.lifecycle("ABI filters              : ${configuredAbis.joinToString()}")
        logger.lifecycle("Release minification     : $minifyEnabledRelease")
        logger.lifecycle("Release signing          : ${if (hasReleaseKeystore) "upload keystore" else "debug key (fallback)"}")
    }
}

tasks.named("preBuild") { dependsOn(printEngineConfig) }
