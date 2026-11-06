package com.floopel.feature.traveler.core.network

import com.floopel.feature.traveler.data.remote.*

internal class ApiFailure(val status: Int, val code: String?, val retryAfterSeconds: Long?) : Exception("API request failed (HTTP $status)")
