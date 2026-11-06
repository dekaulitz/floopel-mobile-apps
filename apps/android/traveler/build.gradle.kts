plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.floopel.traveler"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.floopel.traveler"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        val origin = providers.gradleProperty("floopelApiOrigin").orElse("http://10.0.2.2:8080").get()
        require(Regex("https?://[A-Za-z0-9.:-]+").matches(origin)) { "Provide an API origin without a path or credentials" }
        buildConfigField("String", "API_ORIGIN", "\"$origin\"")
        val cloudProject = providers.gradleProperty("floopelPlayCloudProjectNumber").orElse("0").get().toLong()
        require(cloudProject >= 0)
        buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "${cloudProject}L")
        val googleClient = providers.gradleProperty("floopelGoogleServerClientId").orElse("").get()
        require(googleClient.isBlank() || Regex("[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com").matches(googleClient))
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleClient\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.named("androidTest") {
        kotlin.directories.add(rootProject.file("shared/features/traveler/src/androidTestFixtures/kotlin").path)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared:features:traveler"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)
    implementation(libs.play.integrity)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.google.id)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.ktor.mock)
    androidTestImplementation(libs.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        require(providers.gradleProperty("floopelApiOrigin").orNull?.startsWith("https://") == true) {
            "Release requires the approved HTTPS API origin via floopelApiOrigin"
        }
    }
}
