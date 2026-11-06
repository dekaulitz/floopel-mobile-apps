package com.floopel.feature.traveler.domain.model

enum class CommentTarget(val wire: String) { CONTENT("content"), ITINERARY("itinerary"), LOCATION("location"), SPOT("spot") }
enum class CommentVisibility(val wire: String) { VISIBLE("visible"), HIDDEN("hidden"), REMOVED("removed") }
/** Traveler identity level is optional authoritative metadata; absent never implies level one. */
data class TravelerComment(val id: String, val username: String, val body: String, val status: CommentVisibility, val likeCount: Int,
    val liked: Boolean, val createdAt: String, val parentId: String?, val replyCount: Int, val replies: List<TravelerComment>, val avatarUrl: String? = null, val level: Int? = null)
data class CommentPage(val items: List<TravelerComment>, val nextCursor: String?)
data class CommentLike(val commentId: String, val count: Int, val liked: Boolean)



/** Contract counts Unicode code points after trimming, not UTF-16 code units. */
fun visibleCharacterCount(value: String): Int {
    val text = value.trim()
    var count = 0
    var index = 0
    while (index < text.length) {
        val paired = text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()
        index += if (paired) 2 else 1
        count++
    }
    return count
}
