import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.omi4wos.mobile"
    compileSdk = 34

    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.omi4wos"
        minSdk = 28
        targetSdk = 34
        // 2026-09-18: 提到 13（> wear 的 12），保证手机端 APK 能覆盖安装
        // 两个模块 applicationId 必须相同（Wear Data Layer 要求），故只能用版本号区分
        versionCode = 13
        versionName = "1.13.0"

        // CI 编译号：GitHub Actions run number（经 -PbuildNumber 传入），本地无参数时回退 "local"
        val buildNumber = providers.gradleProperty("buildNumber").orElse("local").get()
        // 编译时间：构建时刻北京时间（Asia/Shanghai），写死进 BuildConfig，About 页展示
        val buildTime = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        buildConfigField("String", "BUILD_NUMBER", "\"${buildNumber}\"")
        buildConfigField("String", "BUILD_TIME", "\"${buildTime}\"")
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("fixed")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX_LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation(project(":shared"))

    // Wear Data Layer
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // Room database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // OkHttp for Omi REST API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // JSON：禁用 org.json:json —— Android 平台已自带 org.json（android.jar / core-libart.jar）。
    // 【2026-09-16 真实崩溃根因】引入 Maven 版会造成「影子类」：
    //   编译期解析到 Maven 版（有 put(String, float) 重载）
    //   运行期系统优先加载平台版（无 float 重载）→ NoSuchMethodError: put(String, float)
    // 表现为周期性定位上报静默杀进程（Error 逃过所有 catch(Exception)）。
    // 不要在依赖里加回 org.json；float 一律显式 toDouble()。

    // Material3 theme (Theme.Material3.DayNight.NoActionBar referenced in AndroidManifest)
    implementation("com.google.android.material:material:1.12.0")

    // AppCompat for AppCompatDelegate.setApplicationLocales (in-app language switching)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // WorkManager for background upload retry / phone recording watcher
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // AWS S3 SDK for S3-compatible object storage (Tencent COS / Cloudflare R2 / MinIO / AWS S3)
    implementation("com.amazonaws:aws-android-sdk-s3:2.77.0")
    implementation("com.amazonaws:aws-android-sdk-core:2.77.0")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
}
