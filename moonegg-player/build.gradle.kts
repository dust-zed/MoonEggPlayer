plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.dustzed.moonegg.player"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 33

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("main") {
            kotlin.srcDir("build/generated/uniffi/kotlin")
            jniLibs.srcDir("build/generated/uniffi/jniLibs")
        }
    }

}

dependencies {
    implementation(variantOf(libs.jna) {
        artifactType("aar")
    })

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}