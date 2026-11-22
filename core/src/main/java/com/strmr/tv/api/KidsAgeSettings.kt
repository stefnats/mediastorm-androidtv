package com.strmr.tv.api

import okhttp3.HttpUrl

/** Presentation of the server's existing profile rule, never a local playback decision. */
data class KidsAgeSummary(val headline: String, val detail: String)

fun kidsAgeSummary(profile: Profile): KidsAgeSummary {
    if (profile.isKidsProfile == false) return KidsAgeSummary("Inactive for this profile", "This is not a child profile. No child age rule is applied here.")
    if (profile.isKidsProfile != true) return KidsAgeSummary("Rule unavailable", "The server did not provide this profile's child setting.")
    return when (profile.kidsMode) {
        "rating_de" -> {
            fun age(raw: String?) = if (raw in setOf("0", "6", "12", "16", "18")) raw else "unavailable"
            KidsAgeSummary("TMDB Germany title metadata", "Maximum movie rating: ${age(profile.kidsMaxMovieRating)}. Maximum series rating: ${age(profile.kidsMaxTVRating)}.\n\nThe series limit uses the series rating, not proof for an individual episode or edition. Missing, conflicting or stale German metadata is blocked. This is a TMDB claim, not an independently verified FSK classification.")
        }
        "content_list" -> KidsAgeSummary("Selected title lists", "This profile uses the title lists chosen in the server's parent settings. No German TMDB age option is selected.")
        "rating" -> {
            val limits = listOfNotNull(profile.kidsMaxMovieRating?.takeIf { it.isNotBlank() }?.let { "Movies: $it" },
                profile.kidsMaxTVRating?.takeIf { it.isNotBlank() }?.let { "Series: $it" },
                profile.kidsMaxRating?.takeIf { it.isNotBlank() }?.let { "Legacy shared limit: $it" })
            KidsAgeSummary("Legacy rating rules", (limits.takeIf { it.isNotEmpty() }?.joinToString(". ") ?: "Limits were not provided by the server.") + "\n\nExisting US/server rating rules are unchanged. They are not converted into German ages.")
        }
        null, "" -> KidsAgeSummary("Server child rules", "This profile uses the server's existing child settings. No German TMDB age option is selected.")
        else -> KidsAgeSummary("Rule unavailable", "This server uses a child rule this app cannot describe. Review it in the server Web UI.")
    }
}

/** A refreshed list must still contain the originally selected profile. */
fun selectedKidsAgeProfile(profiles: List<Profile>, selectedId: String): Profile =
    profiles.singleOrNull { selectedId.isNotBlank() && it.id == selectedId }
        ?: throw ApiError.NotReady("The selected profile is unavailable. Refresh the profile list.")

suspend fun ApiClient.kidsAgeProfile(selectedId: String, expectedContext: Long): Profile {
    inContext(expectedContext) { check(profileId == selectedId) }
    val current = profiles()
    return inContext(expectedContext) {
        check(profileId == selectedId)
        selectedKidsAgeProfile(current, selectedId)
    }
}

/** The normal browser signs in separately; no API session or parent credential travels in this URL. */
fun kidsAgeEditorUrl(server: HttpUrl?, selectedId: String, remote: Boolean): HttpUrl? {
    if (server == null || selectedId.isBlank() || remote) return null
    return server.newBuilder().username("").password("").query(null).fragment(null)
        .addPathSegment("account").addPathSegment("kids-settings")
        .addQueryParameter("profileId", selectedId).build()
}
