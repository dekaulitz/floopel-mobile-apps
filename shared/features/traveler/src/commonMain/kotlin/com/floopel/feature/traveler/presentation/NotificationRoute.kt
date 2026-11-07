package com.floopel.feature.traveler.presentation

/** Server registry destinations only. An inbox UUID is never substituted for a source UUID. */
internal fun notificationRoute(target: String?): TravelerRoute? {
    if (target == null || '?' in target || '#' in target || '%' in target) return null
    if (target == "/me/itinerary-moderation") return TravelerRoute(Destination.OWNER_MODERATION)
    val id = "([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})"
    val routes = listOf("/me/trip-notices/" to Destination.NOTICE,
        "/trip-applications/" to Destination.APPLICATION, "/trips/" to Destination.TRIP,
        "/quest-groups/" to Destination.QUEST_GROUP, "/quest-group-invitations/" to Destination.QUEST_GROUP_INVITATION)
    for ((prefix, destination) in routes) {
        Regex("^$prefix$id$").matchEntire(target)?.let { return TravelerRoute(destination, it.groupValues[1]) }
    }
    return null
}
