package com.floopel.feature.traveler.presentation.passport

internal enum class PassportTab { OVERVIEW, BADGES, STATS }
internal data class PassportContentState(val tab: PassportTab = PassportTab.OVERVIEW,
    val completed: Boolean = false, val expandedQr: Boolean = false, val badgeId: String? = null)
