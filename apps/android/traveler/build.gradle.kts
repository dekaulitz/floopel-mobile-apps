import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

fun environmentValue(property: String, variable: String, fallback: String = ""): String =
    providers.gradleProperty(property).orElse(providers.environmentVariable(variable)).orElse(fallback).get().trim()

fun checkedOrigin(value: String, name: String, httpsOnly: Boolean = false): String {
    if (value.isBlank()) return ""
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.host != null && uri.scheme in (if (httpsOnly) listOf("https") else listOf("http", "https")) &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/") &&
        (uri.port == -1 || uri.port in 1..65535)) { "$name must be an origin without credentials, path, query or fragment" }
    return value.trimEnd('/')
}

fun checkedGoogleClient(value: String): String = value.also {
    require(it.isBlank() || Regex("[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com").matches(it)) { "Invalid Google server client identifier" }
}

fun checkedProject(value: String): Long = requireNotNull(value.toLongOrNull()?.takeIf { it >= 0 }) { "Play Cloud project number must be nonnegative" }

val developmentOrigin = checkedOrigin(environmentValue("floopelDevelopmentApiOrigin", "FLOOPEL_DEVELOPMENT_API_ORIGIN",
    providers.gradleProperty("floopelApiOrigin").orElse("http://10.0.2.2:8080").get()), "Development API origin")
val productionOrigin = checkedOrigin(environmentValue("floopelProductionApiOrigin", "FLOOPEL_PRODUCTION_API_ORIGIN"), "Production API origin", httpsOnly = true)
val productionSigning = listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith {
    providers.environmentVariable("FLOOPEL_TRAVELER_PRODUCTION_$it").orNull
}

android {
    namespace = "com.floopel.traveler"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.floopel.traveler"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = providers.environmentVariable("FLOOPEL_TRAVELER_VERSION_CODE").orElse("1").get().toInt().also { require(it > 0) }
        versionName = providers.environmentVariable("FLOOPEL_TRAVELER_VERSION_NAME").orElse("0.1.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs.create("production") {
        productionSigning["KEYSTORE_FILE"]?.takeIf(String::isNotBlank)?.let { storeFile = file(it) }
        storePassword = productionSigning["KEYSTORE_PASSWORD"]
        keyAlias = productionSigning["KEY_ALIAS"]
        keyPassword = productionSigning["KEY_PASSWORD"]
    }
    flavorDimensions += "environment"
    productFlavors {
        create("development") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Floopel Traveler Dev")
            buildConfigField("String", "BUILD_ENVIRONMENT", "\"development\"")
            buildConfigField("String", "API_ORIGIN", "\"$developmentOrigin\"")
            val client = checkedGoogleClient(environmentValue("floopelDevelopmentGoogleServerClientId", "FLOOPEL_DEVELOPMENT_GOOGLE_SERVER_CLIENT_ID",
                providers.gradleProperty("floopelGoogleServerClientId").orElse("").get()))
            val projectNumber = checkedProject(environmentValue("floopelDevelopmentPlayCloudProjectNumber", "FLOOPEL_DEVELOPMENT_PLAY_CLOUD_PROJECT_NUMBER",
                providers.gradleProperty("floopelPlayCloudProjectNumber").orElse("0").get()))
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$client\"")
            buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "${projectNumber}L")
        }
        create("production") {
            dimension = "environment"
            resValue("string", "app_name", "Floopel Traveler")
            signingConfig = signingConfigs.getByName("production")
            buildConfigField("String", "BUILD_ENVIRONMENT", "\"production\"")
            buildConfigField("String", "API_ORIGIN", "\"$productionOrigin\"")
            val client = checkedGoogleClient(environmentValue("floopelProductionGoogleServerClientId", "FLOOPEL_PRODUCTION_GOOGLE_SERVER_CLIENT_ID"))
            val projectNumber = checkedProject(environmentValue("floopelProductionPlayCloudProjectNumber", "FLOOPEL_PRODUCTION_PLAY_CLOUD_PROJECT_NUMBER", "0"))
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$client\"")
            buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "${projectNumber}L")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
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
    implementation(libs.androidx.core)
    implementation(libs.androidx.splashscreen)
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
    androidTestImplementation(libs.compose.foundation)
    androidTestImplementation(libs.ktor.mock)
    androidTestImplementation(libs.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}

val validateProductionEnvironment = tasks.register("validateProductionEnvironment") {
    val origin = productionOrigin
    doLast { require(origin.isNotBlank()) { "Set FLOOPEL_PRODUCTION_API_ORIGIN or floopelProductionApiOrigin before building production" } }
}
val validateProductionSigning = tasks.register("validateProductionSigning") {
    val complete = productionSigning.values.all { !it.isNullOrBlank() }
    doLast { require(complete) { "Set all FLOOPEL_TRAVELER_PRODUCTION_KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD variables for production release signing" } }
}
tasks.matching { it.name == "preProductionDebugBuild" || it.name == "preProductionReleaseBuild" }.configureEach {
    dependsOn(validateProductionEnvironment)
    if (name == "preProductionReleaseBuild") dependsOn(validateProductionSigning)
}
