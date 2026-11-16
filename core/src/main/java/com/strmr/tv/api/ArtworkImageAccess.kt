package com.strmr.tv.api

import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** One HTTP call's routing snapshot. This is not a cache or content permission. */
internal class ArtworkImageAccess(
    private val base: HttpUrl,
    headers: Map<String, String>,
    private val profileId: String,
    private val current: () -> Boolean,
) {
    private val headers = headers.toMap()
    private val prefix = base.encodedPath.trimEnd('/')
    internal enum class Route { NONE, PUBLIC, PRIVATE }
    private val transport = setOf("x-client-id", "x-remote-access-credential")
    private val secrets = buildSet {
        headers.forEach { (name, value) ->
            when (name.lowercase()) {
                "authorization" -> { add(value); if (value.startsWith("Bearer ", true)) add(value.substringAfter(' ')) }
                "x-profile-unlock", "x-remote-access-credential" -> add(value)
            }
        }
    }.filter(String::isNotEmpty).toSet()

    internal fun route(url: HttpUrl): Route {
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return Route.NONE
        val path = url.encodedPath
        if (!path.startsWith("$prefix/api/")) return Route.NONE
        val encoded = path.removePrefix("$prefix/api/").split('/')
        val parts = url.pathSegments.takeLast(encoded.size)
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.any { c -> c == '/' || c == '\\' || c.isISOControl() } }) return Route.NONE
        return when {
            parts.size == 5 && parts[0] == "library" && parts[1] == "items" && parts[3] == "artwork" -> Route.PRIVATE
            parts.size == 3 && parts[0] == "users" && parts[2] == "icon" -> Route.PUBLIC
            parts.size == 3 && parts[0] == "branding" && parts[1] == "images" -> Route.PUBLIC
            parts == listOf("sports", "logo") -> Route.PUBLIC
            parts == listOf("images", "proxy") || parts == listOf("images", "gif-first-frame") -> Route.PUBLIC
            parts.size == 2 && parts[0] == "static" && parts[1] in staticImages -> Route.PUBLIC
            parts.size == 3 && parts[0] == "static" && parts[1] == "rating_icons" && parts[2] in ratingImages -> Route.PUBLIC
            else -> Route.NONE
        }
    }

    private fun validate(url: HttpUrl): HttpUrl {
        val route = route(url)
        if (route == Route.NONE) {
            if (url.queryParameterNames.any { name -> url.queryParameterValues(name).any { it in secrets } }) unavailable()
            return url
        }
        if (route != Route.PRIVATE) return url
        val selectors = url.queryParameterNames.filter { it.equals("profileId", true) || it.equals("userId", true) }
        if (selectors.any { name -> url.queryParameterValues(name).any { it != profileId } }) unavailable()
        return if (selectors.isEmpty()) url.newBuilder().addQueryParameter("profileId", profileId).build() else url
    }

    /** Preserve encoded queries (including duplicates) and the configured reverse-proxy prefix. */
    internal fun url(raw: String): String? {
        if (!current() || raw.isBlank() || raw.any(Char::isISOControl)) return null
        val directory = base.newBuilder().encodedPath("$prefix/").build()
        val parsed = raw.toHttpUrlOrNull() ?: directory.resolve(raw)?.let {
            if (raw.startsWith("/api/")) it.newBuilder().encodedPath(prefix + it.encodedPath).build() else it
        } ?: return null
        return try { validate(parsed).toString() } catch (_: IOException) { null }
    }

    private fun checkCurrent() { if (!current()) throw IOException("Artwork context changed") }
    private fun unavailable(): Nothing = throw IOException("Artwork image is unavailable")

    /** Immutable, private provenance: never infer injection from an arbitrary incoming header. */
    private class Injection(val access: ArtworkImageAccess, val values: Map<String, String>)

    private fun begin(request: Request): Request {
        checkCurrent()
        val url = validate(request.url)
        val route = route(url)
        val builder = request.newBuilder().url(url)
        val injected = mutableMapOf<String, String>()
        headers.forEach { (name, value) ->
            val allowed = route == Route.PRIVATE || route == Route.PUBLIC && name.lowercase() in transport
            // Deliberate caller/provider headers are outside this helper's provenance.
            if (allowed && request.headers.values(name).isEmpty()) {
                builder.header(name, value)
                injected[name] = value
            }
        }
        return builder.tag(Injection::class.java, Injection(this, injected.toMap())).build()
    }

    private fun hop(request: Request, injected: Map<String, String>): Request {
        checkCurrent()
        val url = validate(request.url)
        val route = route(url)
        return request.newBuilder().url(url).apply {
            injected.forEach { (name, value) ->
                val allowed = route == Route.PRIVATE || route == Route.PUBLIC && name.lowercase() in transport
                if (!allowed) {
                    val existing = request.headers.values(name)
                    val retained = existing.filterNot { it == value }
                    if (retained.size != existing.size) {
                        removeHeader(name)
                        retained.forEach { addHeader(name, it) }
                    }
                }
            }
        }.build()
    }

    companion object {
        private val staticImages = setOf("favicon-16.png", "app-logo-wide.png", "icon-rounded.png", "favicon-32.png", "numbers-station-sender.jpg", "icon-80.png")
        private val ratingImages = setOf("metacritic.png", "tv-y.png", "pg-13.png", "rt_rotten.png", "rt_audience.png", "imdb.png", "tmdb.png", "g.png", "tv-g.png", "tv-ma.png", "tv-14.png", "nc-17.png", "letterboxd.png", "trakt.png", "tv-pg.png", "tv-y7.png", "rt_critics.png", "r.png", "pg.png")

        /** The same pair is installed by the actual TV and phone image-loader factory. */
        internal fun client(base: OkHttpClient, capture: () -> ArtworkImageAccess): OkHttpClient = base.newBuilder()
            .addInterceptor { chain ->
                val access = try { capture() } catch (_: CancellationException) { throw IOException("Artwork context changed") }
                val response = chain.proceed(access.begin(chain.request()))
                try { access.checkCurrent() } catch (error: IOException) { response.close(); throw error }
                response
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val injection = request.tag(Injection::class.java) ?: throw IOException("Artwork request is unavailable")
                val response = chain.proceed(injection.access.hop(request, injection.values))
                try { injection.access.checkCurrent() } catch (error: IOException) { response.close(); throw error }
                response
            }.build()
    }
}
