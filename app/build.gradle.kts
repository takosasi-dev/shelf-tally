plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.takosasidev.shelftally"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.takosasidev.shelftally"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Room のスキーマの JSON を書き出して git で管理する(マイグレーションの確かめに使う)
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Android 7.x(API 24・25)でも java.time を使えるようにする
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // 画面の画像は `-Pshots` を付けたときだけ撮る(app/build/outputs/roborazzi/)。付けなければ撮影のテストは素通りする
            all { if (project.hasProperty("shots")) it.systemProperty("roborazzi.test.record", "true") }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core"))

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.zip4j)

    // バーコードの読み取りのカメラ。解析の画像は :core の BarcodeDecoder(ZXing)へ渡す
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
