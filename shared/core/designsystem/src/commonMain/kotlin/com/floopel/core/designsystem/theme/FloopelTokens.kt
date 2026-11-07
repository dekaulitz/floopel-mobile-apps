package com.floopel.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object FloopelSpace {
    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val gutter = 20.dp
    val section = 24.dp
    val compact = 12.dp
}

object FloopelShape {
    val card = RoundedCornerShape(16.dp)
    val media = RoundedCornerShape(10.dp)
    val hero = RoundedCornerShape(24.dp)
    val chip = RoundedCornerShape(8.dp)
    val pill = RoundedCornerShape(24.dp)
    val control = RoundedCornerShape(4.dp)
    val tripCover = RoundedCornerShape(14.dp)
    val publicationCover = RoundedCornerShape(12.dp)
}

object FloopelControlSize { val touchTarget = 48.dp; val compactInput = 44.dp }

object FloopelMediaSize {
    val profileAvatar = 48.dp
    val publicationAvatar = 40.dp
    val organizerAvatar = 28.dp
    val tripCover = 190.dp
    val publicationCover = 204.dp
    val curatedCover = 170.dp
    val profileItineraryCover = 180.dp
}
