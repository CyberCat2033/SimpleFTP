import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun property(name: String): String? =
    (findProperty(name) as String?) ?: localProperties.getProperty(name)

val releaseStoreFile = property("RELEASE_STORE_FILE")
val releaseStorePassword = property("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = property("RELEASE_KEY_ALIAS")
val releaseKeyPassword = property("RELEASE_KEY_PASSWORD")

val hasReleaseSigning =
    releaseStoreFile != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

android {
    namespace = "com.cybercat.simpleftp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cybercat.simpleftp"
        minSdk = 23
        targetSdk = 35
        versionCode = property("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = property("VERSION_NAME") ?: "0.1.0"
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://cybercat2033.github.io/SimpleFTP/updates/latest.json\""
        )
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
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
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.zxing.core)

    debugImplementation(libs.compose.ui.tooling)
}
