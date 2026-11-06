package com.floopel.feature.traveler.domain.model

data class AccountErasureState(val status: String, val deadline: String?, val canCancel: Boolean, val canRequest: Boolean, val blockingReason: String?)
