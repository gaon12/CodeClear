import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val validationBuild = providers.gradleProperty("codeclear.validationBuild")
    .map(String::toBooleanStrict).getOrElse(false)

val ownedTranslationUrl = providers.environmentVariable("CODECLEAR_TRANSLATION_URL")
    .orElse("").map { value ->
        if (value.isNotEmpty()) {
            val route = runCatching { URI(value) }.getOrNull()
            val publicHost = route?.host?.matches(
                Regex("(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}")
            ) == true
            require(
                value.length <= 2048 && value.all { it.code in 33..126 } &&
                    route?.scheme == "https" && publicHost &&
                    route.port in listOf(-1, 443) && route.rawUserInfo == null &&
                    route.rawQuery == null && route.rawFragment == null
            ) {
                "Invalid private translation route; use an HTTPS endpoint without credentials or query"
            }
        }
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

android {
    namespace = "xyz.gaon.codeclear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = if (validationBuild) {
            "xyz.gaon.codeclear.validation"
        } else {
            "xyz.gaon.codeclear"
        }
        minSdk = 30
        targetSdk = 37
        versionCode = 6
        versionName = "1.0.1"
        buildConfigField("String", "OWNED_TRANSLATION_URL", ownedTranslationUrl.get())

        testInstrumentationRunner = if (validationBuild) {
            "xyz.gaon.codeclear.CollectionPreservingTestRunner"
        } else {
            "androidx.test.runner.AndroidJUnitRunner"
        }
        manifestPlaceholders["applicationLabel"] = if (validationBuild) {
            resValue("string", "validation_application_name", "CodeClear Validation")
            "@string/validation_application_name"
        } else {
            "@string/app_name"
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = if (validationBuild) "-validation" else "-capture-probe"
        }
        release {
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
        resValues = validationBuild
    }
    bundle {
        language {
            enableSplit = false
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) {
        if (validationBuild) it.enable = false
    }
}

dependencies {
    constraints {
        implementation(libs.okhttp) {
            because(
                "ML Kit requests an obsolete HTTP client with known certificate vulnerabilities."
            )
        }
        implementation(libs.okio) {
            because("Use the patched I/O implementation for SDK-managed model downloads.")
        }
    }
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.okhttp)
}
