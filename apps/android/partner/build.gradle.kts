plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val productionSigning = listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith {
    providers.environmentVariable("FLOOPEL_PARTNER_PRODUCTION_$it").orNull
}

android {
    namespace = "com.floopel.partner"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.floopel.partner"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = providers.environmentVariable("FLOOPEL_PARTNER_VERSION_CODE").orElse("1").get().toInt().also { require(it > 0) }
        versionName = providers.environmentVariable("FLOOPEL_PARTNER_VERSION_NAME").orElse("0.1.0").get()
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
            resValue("string", "app_name", "Floopel Partner Dev")
            buildConfigField("String", "BUILD_ENVIRONMENT", "\"development\"")
        }
        create("production") {
            dimension = "environment"
            resValue("string", "app_name", "Floopel Partner")
            signingConfig = signingConfigs.getByName("production")
            buildConfigField("String", "BUILD_ENVIRONMENT", "\"production\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val validateProductionSigning = tasks.register("validateProductionSigning") {
    val complete = productionSigning.values.all { !it.isNullOrBlank() }
    doLast { require(complete) { "Set all FLOOPEL_PARTNER_PRODUCTION_KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD variables for production release signing" } }
}
tasks.matching { it.name == "preProductionReleaseBuild" }.configureEach { dependsOn(validateProductionSigning) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared:features:partner"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
}
