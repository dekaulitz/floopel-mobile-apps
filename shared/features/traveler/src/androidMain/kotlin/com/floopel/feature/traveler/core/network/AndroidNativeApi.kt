package com.floopel.feature.traveler.core.network

import com.floopel.feature.traveler.data.remote.*

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/** Android owns its version source; shared transport never invents a release version. */
internal fun androidNativeApi(origin: String, appVersion: String) = NativeApi(HttpClient(OkHttp) {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
}, origin, AppPlatform.ANDROID, readCache = TravelerReadCache(), appVersion = appVersion)
