package com.floopel.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

object FloopelBrand { val travelerStartup = Color(0xFF3730A3) }

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
    val locationCard = RoundedCornerShape(12.dp)
    val thumbnail = RoundedCornerShape(8.dp)
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
    val locationCardWidth = 160.dp
    val locationCardHeight = 208.dp
}

/** Catalog text roles from the Home, Spot and Location frames. */
object FloopelCatalogTypography {
    private val lineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    val toolbar = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, lineHeightStyle = lineHeight)
    val metadata = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, lineHeightStyle = lineHeight)
    val detailMetadata = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal, lineHeightStyle = lineHeight)
    val detailScore = detailMetadata.copy(fontWeight = FontWeight.Medium)
    val compactTitle = detailScore
    val locationCategory = metadata.copy(fontWeight = FontWeight.Medium)
    val locationParent = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, lineHeightStyle = lineHeight)
    val sectionAction = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, lineHeightStyle = lineHeight)
    val mediaParent = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, lineHeightStyle = lineHeight)
    val discoveryTitle = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp, lineHeightStyle = lineHeight)
    val discoveryCategory = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp, lineHeightStyle = lineHeight)
    val discoveryReference = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp, lineHeightStyle = lineHeight)
}
