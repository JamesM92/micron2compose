plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.jamesm92.micron2compose"
    // compileSdk 37: matches nomadportal-android, whose dependency graph
    // (androidx.core 1.19.0 / lifecycle-runtime-compose-android 2.11.0)
    // requires compiling against API 37+ — kept in step so a downstream
    // consumer building both projects never hits a mismatched-compileSdk
    // warning.
    compileSdk = 37

    defaultConfig {
        // 24, not nomadportal-android's 31 — that floor was specific to its
        // Bluetooth-LE scanning requirement (BLUETOOTH_SCAN's
        // neverForLocation flag needs API 31+). This library has no BLE
        // dependency at all, and 24 is a realistic general Compose floor —
        // keeping it low is deliberate, per this being a generic dependency
        // any Android/Compose project should be able to pull in, not just
        // nomadportal-android.
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    publishing {
        // Publishes the release AAR variant — consumed via JitPack
        // (`implementation("com.github.JamesM92:micron2compose:<tag>")`),
        // which builds straight off a tagged GitHub release with no other
        // infra required. See README "Installation" for the consumer side.
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.JamesM92"
            artifactId = "micron2compose"
            version = project.findProperty("libVersion") as String? ?: "0.0.1"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
