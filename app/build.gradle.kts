plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kleinops.gitmount"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kleinops.gitmount"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // 保持不混淆：本工程无反射型序列化，体积也可控，稳定性优先。
            isMinifyEnabled = false
            isShrinkResources = false
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // git push 上传链路（替代 Contents API 的 base64 膨胀）：
    // JGit 5.13.3 是 Java 8 兼容的稳定版，Android 上久经验证（MGit 同款），
    // 依赖 slf4j-api，实测传 Android 前需确认不再使用 java.sql/javax.* 等受限 API。
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
    // JGit 的日志走 slf4j；Android 上无绑定会打印警告，用 nop 绑定静默（不影响功能）
    implementation("org.slf4j:slf4j-nop:1.7.36")

    // 说明：FTP / WebDAV 服务均为手写实现（ServerSocket + 协议子集），
    // 不引入 Apache FtpServer / 任何 WebDAV 库，减少 Android 兼容性与构建风险。

    debugImplementation("androidx.compose.ui:ui-tooling")
}
