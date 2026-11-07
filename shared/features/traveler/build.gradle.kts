plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.floopel.feature.traveler"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources { enable = true }
        withHostTestBuilder {}
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "TravelerKit"
            isStatic = true
            binaryOption("bundleId", "com.floopel.traveler.kit")
        }
    }

    sourceSets {
        named("androidHostTest") { kotlin.srcDir("src/androidTestFixtures/kotlin") }
        commonMain.dependencies {
            implementation(project(":shared:core:model"))
            implementation(project(":shared:core:designsystem"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.resources)
            api(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.ktor.core)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.runtime)
            implementation(libs.coil.compose)
            implementation(libs.coil.network)
            implementation(libs.coil.svg)
        }
        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
            implementation(libs.coroutines.android)
        }
        iosMain.dependencies { implementation(libs.ktor.darwin) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
    }
}

compose.resources { packageOfResClass = "com.floopel.feature.traveler.resources" }

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name == "testAndroidHostTest") {
        val live = providers.gradleProperty("floopelLiveSmoke").orElse("false").get().toBooleanStrict()
        val privateLive = providers.gradleProperty("floopelPrivateSmoke").orElse("false").get().toBooleanStrict()
        val privateMedia = providers.gradleProperty("floopelPrivateMediaSmoke").orElse("false").get().toBooleanStrict()
        require(listOf(live, privateLive, privateMedia).count { it } <= 1) { "Choose one live smoke mode" }
        if (live || privateLive || privateMedia) outputs.upToDateWhen { false }
        if (privateLive || privateMedia) {
            filter.includeTestsMatching(if (privateMedia) "*.LivePrivateMediaPublicationTest" else "*.LivePrivateTravelerTest")
            systemProperty("floopel.test.origin", providers.gradleProperty("floopelApiOrigin").get())
            systemProperty("floopel.test.fixture", providers.gradleProperty("floopelPrivateFixtureFile").get())
            if (privateMedia) {
                systemProperty("floopel.test.mediaWorker", providers.gradleProperty("floopelPrivateMediaWorker").get())
                systemProperty("floopel.test.backendService", providers.gradleProperty("floopelPrivateBackendService").get())
                systemProperty("floopel.test.processorImage", providers.gradleProperty("floopelPrivateProcessorImage").get())
            }
        } else if (live) {
            filter.includeTestsMatching("*.LiveCatalogSmokeTest")
            systemProperty("floopel.test.origin", providers.gradleProperty("floopelApiOrigin").get())
        } else {
            filter.excludeTestsMatching("*.LiveCatalogSmokeTest")
            filter.excludeTestsMatching("*.LivePrivateTravelerTest")
            filter.excludeTestsMatching("*.LivePrivateMediaPublicationTest")
        }
    }
}
