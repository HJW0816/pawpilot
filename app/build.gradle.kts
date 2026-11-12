plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.estareng.pawpilot"
    compileSdk = 35
    useLibrary("android.car", false)

    defaultConfig {
        applicationId = "com.estareng.pawpilot"
        minSdk = 28          // Pleos 요구사항: 차량 사이버보안(서명키 로테이션) 때문에 28 이상
        targetSdk = 35
        versionCode = 4      // 심사 제출마다 반드시 올릴 것 (앱 버전 관리 정책)
        versionName = "0.4.0"
        resValue("string", "companion_base_url", providers.gradleProperty("pawpilotCompanionUrl").orElse("").get())
        resValue("string", "recommendation_base_url", providers.gradleProperty("pawpilotRecommendationUrl").orElse("").get())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false  // 앱 보안 가이드: 릴리즈 debuggable 금지
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
        viewBinding = true
    }
}

dependencies {
    // Pleos Connect SDK — 즉시 사용 가능
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // 준비 중(Playground 상태 '준비 중') — 활성화되면 주석 해제
    // implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
}
