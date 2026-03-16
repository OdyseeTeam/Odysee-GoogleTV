package com.odysee.androidtv.feature.discover.data

import com.odysee.androidtv.core.auth.AuthRepository
import com.odysee.androidtv.core.network.OdyseeApiClient
import com.odysee.androidtv.feature.discover.model.Category
import com.odysee.androidtv.feature.discover.model.VideoClaim
import org.json.JSONArray
import org.json.JSONObject

class DiscoverRepository(
    private val api: OdyseeApiClient,
    private val authRepository: AuthRepository,
) {
    private data class WatchLaterCollection(
        val claimRefs: List<String> = emptyList(),
        val resolvedClaims: List<JSONObject> = emptyList(),
        val itemCount: Int = 0,
    )

    suspend fun loadCategories(): List<Category> {
        val payload = api.getJson(FRONTPAGE_URL)
        val categories = extractCategories(payload)
        return categories.ifEmpty { fallbackCategories() }
    }

    suspend fun loadCategoryVideos(category: Category, page: Int = 1, pageSize: Int = 24): List<VideoClaim> {
        if (category.id.equals("following", ignoreCase = true)) {
            return loadFollowingVideos(page = page, pageSize = pageSize)
        }
        if (isWatchLaterToken(category.id)) {
            return loadWatchLaterVideos(page = page, pageSize = pageSize)
        }

        val primaryQuery = buildCategoryQuery(category = category, page = page, pageSize = pageSize)
        val primaryResult = runCatching {
            api.callSdk(method = "claim_search", params = primaryQuery)
        }.getOrNull()
        val primaryItems = normalizeClaims(primaryResult?.optJSONArray("items") ?: JSONArray())
        if (primaryItems.isNotEmpty()) {
            return primaryItems
        }

        val fallbackQuery = JSONObject()
            .put("claim_type", JSONArray().put("stream"))
            .put("stream_types", JSONArray().put("video"))
            .put("has_source", true)
            .put("no_totals", true)
            .put("page", page)
            .put("page_size", pageSize)
            .put("order_by", JSONArray().put("trending_group").put("trending_mixed"))
            .put("fee_amount", "<=0")
            .put("not_tags", JSONArray(NSFW_TAGS))

        val fallbackResult = runCatching {
            api.callSdk(method = "claim_search", params = fallbackQuery)
        }.getOrNull()
        return normalizeClaims(fallbackResult?.optJSONArray("items") ?: JSONArray())
    }

    suspend fun resolveStreamUrl(video: VideoClaim, forceAuthRefresh: Boolean = false): String {
        val candidates = resolveStreamCandidates(video = video, forceAuthRefresh = forceAuthRefresh)
        return candidates.firstOrNull() ?: throw IllegalStateException("No stream URL available")
    }

    suspend fun resolveStreamCandidates(video: VideoClaim, forceAuthRefresh: Boolean = false): List<String> {
        val uri = when {
            video.canonicalUrl.isNotBlank() -> video.canonicalUrl
            video.normalizedName.isNotBlank() -> "lbry://${video.normalizedName}#${video.claimId}"
            else -> ""
        }
        if (uri.isBlank()) {
            throw IllegalStateException("Missing claim URI")
        }

        val result = getStreamResultWithAuthRetry(uri = uri, forceAuthRefresh = forceAuthRefresh)
        val streamUrl = result.optString("streaming_url")
        val authToken = runCatching {
            authRepository.ensureAnonymousAuth(forceRefresh = forceAuthRefresh)
        }.getOrDefault("")

        val candidates = mutableListOf<String>()
        val apiV3Url = buildApiV3StreamUrl(video)
        if (apiV3Url.isNotBlank()) {
            candidates += addAuthTokenToUrl(apiV3Url, authToken)
            candidates += apiV3Url
        }
        if (streamUrl.isNotBlank()) {
            candidates += streamUrl
        }
        val legacyApiV3Url = buildLegacyApiV3StreamUrl(video)
        if (legacyApiV3Url.isNotBlank()) {
            candidates += addAuthTokenToUrl(legacyApiV3Url, authToken)
            candidates += legacyApiV3Url
        }

        val prioritized = prioritizeStreamCandidates(pruneCandidatesForTv(uniqueUrls(candidates)))
        if (prioritized.isNotEmpty()) {
            return prioritized
        }
        throw IllegalStateException("No stream URL available")
    }

    suspend fun loadChannelVideos(channelIdRaw: String, page: Int = 1, pageSize: Int = 24): List<VideoClaim> {
        val channelId = normalizeClaimId(channelIdRaw)
        if (channelId.isBlank()) {
            return emptyList()
        }
        val params = JSONObject()
            .put("claim_type", JSONArray().put("stream"))
            .put("stream_types", JSONArray().put("video"))
            .put("has_source", true)
            .put("no_totals", true)
            .put("page", page)
            .put("page_size", pageSize)
            .put("order_by", JSONArray().put("release_time"))
            .put("fee_amount", "<=0")
            .put("not_tags", JSONArray(NSFW_TAGS))
            .put("channel_ids", JSONArray().put(channelId))
            .put("release_time", "<${System.currentTimeMillis() / 1000}")

        return runCatching {
            api.callSdk(method = "claim_search", params = params)
        }.getOrNull()?.optJSONArray("items")?.let(::normalizeClaims).orEmpty()
    }

    suspend fun searchVideos(queryRaw: String, page: Int = 1, pageSize: Int = 36): List<VideoClaim> {
        val query = queryRaw.trim()
        if (query.isBlank()) {
            return emptyList()
        }
        val safePage = page.coerceAtLeast(1)

        val rows = runCatching {
            val primary = requestSearchRows(buildSearchUrl(LIGHTHOUSE_API, query, safePage, pageSize))
            if (primary.isNotEmpty()) {
                primary
            } else {
                requestSearchRows(buildSearchUrl(LIGHTHOUSE_ALT, query, safePage, pageSize))
            }
        }.getOrDefault(emptyList())

        val claimIds = extractClaimIdsFromSearchRows(rows)
        if (claimIds.isEmpty()) {
            return runDirectTextSearch(query, page = safePage, pageSize = pageSize)
        }

        val items = runCatching {
            api.callSdk(
                method = "claim_search",
                params = JSONObject()
                    .put("claim_type", JSONArray().put("stream"))
                    .put("stream_types", JSONArray().put("video"))
                    .put("has_source", true)
                    .put("claim_ids", JSONArray(claimIds.take(2047)))
                    .put("no_totals", true)
                    .put("page_size", pageSize)
                    .put("not_tags", JSONArray(NSFW_TAGS))
                    .put("fee_amount", "<=0")
                    .put("order_by", JSONArray().put("release_time")),
            )
        }.getOrNull()?.optJSONArray("items")?.let(::normalizeClaims).orEmpty()

        if (items.isNotEmpty()) {
            return items
        }
        return runDirectTextSearch(query, page = safePage, pageSize = pageSize)
    }

    suspend fun loadRecommendedVideos(video: VideoClaim, pageSize: Int = 20): List<VideoClaim> {
        val claimId = normalizeClaimId(video.claimId)
        val title = video.title.trim().ifBlank {
            video.normalizedName.trim().replace('-', ' ')
        }
        if (claimId.isBlank() || title.isBlank()) {
            return emptyList()
        }

        val safePageSize = pageSize.coerceAtLeast(1)
        val userId = runCatching { authRepository.getUserId().trim() }.getOrDefault("")
        val rows = runCatching {
            requestSearchRows(
                buildRecommendationSearchUrl(
                    query = title,
                    relatedToClaimId = claimId,
                    pageSize = safePageSize,
                    userId = userId,
                )
            )
        }.getOrDefault(emptyList())
        if (rows.isEmpty()) {
            return emptyList()
        }

        val recommendationClaimIds = extractClaimIdsFromSearchRows(rows)
            .filterNot { it == claimId }
        if (recommendationClaimIds.isEmpty()) {
            return emptyList()
        }

        val claimResult = runCatching {
            api.callSdk(
                method = "claim_search",
                params = JSONObject()
                    .put("claim_type", JSONArray().put("stream"))
                    .put("stream_types", JSONArray().put("video"))
                    .put("has_source", true)
                    .put("claim_ids", JSONArray(recommendationClaimIds.take(2047)))
                    .put("no_totals", true)
                    .put("page_size", safePageSize)
                    .put("not_tags", JSONArray(NSFW_TAGS))
                    .put("fee_amount", "<=0")
                    .put("order_by", JSONArray().put("release_time")),
            )
        }.getOrNull()

        val claimRows = jsonArrayObjects(claimResult?.optJSONArray("items") ?: JSONArray())
        if (claimRows.isEmpty()) {
            return emptyList()
        }

        val orderedRows = reorderClaimsByRefs(claimRows, recommendationClaimIds)
        return normalizeClaims(JSONArray(orderedRows))
            .filterNot { normalizeClaimId(it.claimId) == claimId }
    }

    suspend fun isChannelFollowed(channelIdRaw: String): Boolean {
        val strictChannelId = normalizeClaimId(channelIdRaw)
        val looseChannelId = normalizeClaimIdLoose(channelIdRaw)
        if (strictChannelId.isBlank() && looseChannelId.isBlank()) {
            return false
        }
        val authToken = authRepository.getAuthToken()
        if (authToken.isBlank()) {
            return false
        }
        val followedIds = listFollowingChannelIds(authToken)
        if (strictChannelId.isNotBlank() && followedIds.contains(strictChannelId)) {
            return true
        }
        if (followedIds.any { followedId -> claimIdsMatch(followedId, looseChannelId) }) {
            return true
        }
        return isChannelFollowedByPreferenceHandle(
            authToken = authToken,
            channelId = strictChannelId.ifBlank { looseChannelId },
        )
    }

    suspend fun followChannel(channelIdRaw: String, channelNameRaw: String) {
        val channelId = normalizeClaimId(channelIdRaw)
        if (channelId.isBlank()) {
            throw IllegalStateException("Invalid channel id")
        }
        val authToken = authRepository.getAuthToken()
        if (authToken.isBlank()) {
            throw IllegalStateException("Unable to initialize auth token")
        }
        val channelName = channelNameRaw.trim().ifBlank { "Channel" }
        val primaryAttempt = runCatching {
            api.callRoot(
                resource = "subscription",
                action = "new",
                params = mapOf(
                    "auth_token" to authToken,
                    "claim_id" to channelId,
                    "channel_name" to channelName,
                    "notifications_disabled" to "false",
                ),
                method = "POST",
            )
        }
        if (primaryAttempt.isFailure) {
            // Some backends reject channel_name; retry with only claim_id.
            api.callRoot(
                resource = "subscription",
                action = "new",
                params = mapOf(
                    "auth_token" to authToken,
                    "claim_id" to channelId,
                ),
                method = "POST",
            )
        }

        // Keep sync-backed follow state aligned with subscription endpoints.
        runCatching {
            persistChannelFollowPreference(
                authToken = authToken,
                channelId = channelId,
                channelName = channelName,
                shouldFollow = true,
            )
        }
    }

    suspend fun unfollowChannel(channelIdRaw: String, channelNameRaw: String = "") {
        val channelId = normalizeClaimId(channelIdRaw)
        if (channelId.isBlank()) {
            throw IllegalStateException("Invalid channel id")
        }
        val authToken = authRepository.getAuthToken()
        if (authToken.isBlank()) {
            throw IllegalStateException("Unable to initialize auth token")
        }
        api.callRoot(
            resource = "subscription",
            action = "delete",
            params = mapOf(
                "auth_token" to authToken,
                "claim_id" to channelId,
            ),
            method = "POST",
        )

        // Keep sync-backed follow state aligned with subscription endpoints.
        runCatching {
            persistChannelFollowPreference(
                authToken = authToken,
                channelId = channelId,
                channelName = channelNameRaw.trim(),
                shouldFollow = false,
            )
        }
    }

    suspend fun listClaimReactions(claimIdRaw: String): JSONObject {
        val claimId = normalizeClaimId(claimIdRaw)
        if (claimId.isBlank()) {
            return JSONObject()
        }
        val authToken = runCatching { authRepository.ensureAnonymousAuth(forceRefresh = false) }.getOrDefault("")
        val payload = api.callRoot(
            resource = "reaction",
            action = "list",
            params = buildMap {
                put("claim_ids", claimId)
                if (authToken.isNotBlank()) {
                    put("auth_token", authToken)
                }
            },
            method = "POST",
        )
        return payload as? JSONObject ?: JSONObject()
    }

    suspend fun reactToClaim(
        claimIdRaw: String,
        reactionTypeRaw: String,
        remove: Boolean,
    ) {
        val claimId = normalizeClaimId(claimIdRaw)
        if (claimId.isBlank()) {
            throw IllegalStateException("Missing claim id")
        }

        val reactionType = reactionTypeRaw.trim().lowercase()
        if (reactionType != "like" && reactionType != "dislike") {
            throw IllegalStateException("Unsupported reaction type")
        }

        val authToken = authRepository.ensureAnonymousAuth(forceRefresh = false)
        if (authToken.isBlank()) {
            throw IllegalStateException("Unable to initialize auth token")
        }

        api.callRoot(
            resource = "reaction",
            action = "react",
            params = buildMap {
                put("auth_token", authToken)
                put("claim_ids", claimId)
                put("type", reactionType)
                put("clear_types", if (reactionType == "like") "dislike" else "like")
                if (remove) {
                    put("remove", "true")
                }
            },
            method = "POST",
        )
    }

    suspend fun logFileView(video: VideoClaim) {
        val claimId = normalizeClaimId(video.claimId)
        if (claimId.isBlank()) {
            throw IllegalStateException("Missing claim id")
        }

        val uri = firstNonBlank(
            video.canonicalUrl.trim(),
            if (video.normalizedName.isNotBlank()) "lbry://${video.normalizedName}#$claimId" else "",
        )
        if (uri.isBlank()) {
            throw IllegalStateException("Missing claim URI")
        }

        val outpoint = firstNonBlank(
            video.outpoint.trim(),
            if (video.txid.isNotBlank() && video.nout.isNotBlank()) "${video.txid}:${video.nout}" else "",
        )
        if (outpoint.isBlank()) {
            throw IllegalStateException("Missing claim outpoint")
        }

        val authToken = runCatching { authRepository.ensureAnonymousAuth(forceRefresh = false) }.getOrDefault("")
        api.callRoot(
            resource = "file",
            action = "view",
            params = buildMap {
                put("uri", uri)
                put("claim_id", claimId)
                put("outpoint", outpoint)
                if (authToken.isNotBlank()) {
                    put("auth_token", authToken)
                }
            },
            method = "POST",
        )
    }

    fun getMyClaimReaction(payload: JSONObject, claimIdRaw: String): String {
        val claimId = normalizeClaimId(claimIdRaw)
        if (claimId.isBlank()) {
            return ""
        }
        val myReactions = when (val raw = payload.opt("my_reactions") ?: payload.opt("myReactions") ?: payload.opt("reactions")) {
            is JSONObject -> raw
            else -> return ""
        }

        var entry = myReactions.optJSONObject(claimIdRaw)
            ?: myReactions.optJSONObject(claimId)
        if (entry == null) {
            val keys = myReactions.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.equals(claimId, ignoreCase = true)) {
                    entry = myReactions.optJSONObject(key)
                    break
                }
            }
        }

        entry ?: return ""
        if (entry.optInt("like", 0) > 0) {
            return "like"
        }
        if (entry.optInt("dislike", 0) > 0) {
            return "dislike"
        }
        return ""
    }

    private suspend fun requestSearchRows(url: String): List<JSONObject> {
        if (url.isBlank()) {
            return emptyList()
        }
        val payload = runCatching { api.getJson(url) }.getOrNull()
        return when (payload) {
            is JSONArray -> jsonArrayObjects(payload)
            is JSONObject -> {
                when {
                    payload.optJSONArray("data") != null -> jsonArrayObjects(payload.optJSONArray("data") ?: JSONArray())
                    payload.optJSONArray("results") != null -> jsonArrayObjects(payload.optJSONArray("results") ?: JSONArray())
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun buildSearchUrl(base: String, query: String, page: Int, pageSize: Int): String {
        if (base.isBlank()) {
            return ""
        }
        val safePage = page.coerceAtLeast(1)
        val safePageSize = pageSize.coerceAtLeast(1)
        val from = (safePage - 1) * safePageSize
        return "$base?s=${encodeURIComponent(query)}&size=$safePageSize&from=$from&claimType=file&nsfw=false&free_only=true"
    }

    private fun buildRecommendationSearchUrl(
        query: String,
        relatedToClaimId: String,
        pageSize: Int,
        userId: String,
    ): String {
        if (LIGHTHOUSE_ALT.isBlank()) {
            return ""
        }
        val normalizedQuery = query.trim().replace("/", " ")
        val safePageSize = pageSize.coerceAtLeast(1)
        val base = "$LIGHTHOUSE_ALT?s=${encodeURIComponent(normalizedQuery)}&size=$safePageSize&from=0&claimType=file&nsfw=false&free_only=true&related_to=$relatedToClaimId"
        return if (userId.isBlank()) {
            base
        } else {
            "$base&user_id=${encodeURIComponent(userId)}&uid=${encodeURIComponent(userId)}"
        }
    }

    private fun extractClaimIdsFromSearchRows(rows: List<JSONObject>): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        rows.forEach { row ->
            val candidates = listOf(
                row.optString("claimId"),
                row.optString("claim_id"),
                row.optString("id"),
                row.optString("guid"),
                row.optJSONObject("claim")?.optString("claimId").orEmpty(),
                row.optJSONObject("claim")?.optString("claim_id").orEmpty(),
                row.optJSONObject("claim")?.optString("id").orEmpty(),
                row.optJSONObject("value")?.optString("claim_id").orEmpty(),
            )
            val claimId = candidates.asSequence().map(::normalizeClaimId).firstOrNull { it.isNotBlank() }.orEmpty()
            if (claimId.isNotBlank() && seen.add(claimId)) {
                out += claimId
            }
        }
        return out
    }

    private suspend fun runDirectTextSearch(query: String, page: Int, pageSize: Int): List<VideoClaim> {
        val result = runCatching {
            api.callSdk(
                method = "claim_search",
                params = JSONObject()
                    .put("claim_type", JSONArray().put("stream"))
                    .put("stream_types", JSONArray().put("video"))
                    .put("has_source", true)
                    .put("no_totals", true)
                    .put("page", page.coerceAtLeast(1))
                    .put("page_size", pageSize)
                    .put("order_by", JSONArray().put("release_time"))
                    .put("fee_amount", "<=0")
                    .put("not_tags", JSONArray(NSFW_TAGS))
                    .put("text", query),
            )
        }.getOrNull()
        return normalizeClaims(result?.optJSONArray("items") ?: JSONArray())
    }

    private fun jsonArrayObjects(array: JSONArray): List<JSONObject> =
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }

    private fun encodeURIComponent(raw: String): String =
        java.net.URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")

    private suspend fun getStreamResultWithAuthRetry(uri: String, forceAuthRefresh: Boolean = false): JSONObject {
        val initialToken = runCatching {
            authRepository.ensureAnonymousAuth(forceRefresh = forceAuthRefresh)
        }.getOrDefault("")
        return runCatching {
            val primary = getStreamResult(uri = uri, authToken = initialToken)
            if (primary.optString("streaming_url").isNotBlank() || initialToken.isBlank()) {
                primary
            } else {
                val freshToken = runCatching { authRepository.ensureAnonymousAuth(forceRefresh = true) }.getOrDefault("")
                if (freshToken.isBlank() || freshToken == initialToken) {
                    primary
                } else {
                    runCatching { getStreamResult(uri = uri, authToken = freshToken) }.getOrDefault(primary)
                }
            }
        }.getOrElse { error ->
            if (initialToken.isBlank()) {
                throw error
            }
            val freshToken = runCatching { authRepository.ensureAnonymousAuth(forceRefresh = true) }.getOrDefault("")
            if (freshToken.isBlank() || freshToken == initialToken) {
                throw error
            }
            getStreamResult(uri = uri, authToken = freshToken)
        }
    }

    private suspend fun getStreamResult(uri: String, authToken: String): JSONObject {
        val params = JSONObject().put("uri", uri)
        if (authToken.isNotBlank()) {
            params.put("auth_token", authToken)
        }
        return api.callSdk(
            method = "get",
            params = params,
            authToken = authToken.takeIf { it.isNotBlank() },
        )
    }

    private fun buildApiV3StreamUrl(video: VideoClaim): String =
        buildApiV3StreamUrlWithBase(VIDEO_API_BASE, video)

    private fun buildLegacyApiV3StreamUrl(video: VideoClaim): String =
        buildApiV3StreamUrlWithBase(LEGACY_VIDEO_API_BASE, video)

    private fun buildApiV3StreamUrlWithBase(baseUrl: String, video: VideoClaim): String {
        if (video.normalizedName.isBlank() || video.claimId.isBlank()) {
            return ""
        }
        if (baseUrl.isBlank()) {
            return ""
        }
        val base = "${baseUrl.trimEnd('/')}/api/v3/streams/free/${encodeURIComponent(video.normalizedName)}/${video.claimId}"
        val fileExt = getExtensionFromMediaType(video.sourceMediaType)
        val sdHash = video.sourceSdHash.takeIf { it.length >= 6 }?.substring(0, 6).orEmpty()
        val sourceHash = video.sourceHash.takeIf { it.length >= 6 }?.substring(0, 6).orEmpty()

        return when {
            sdHash.isNotBlank() && fileExt.isNotBlank() -> "$base/$sdHash.$fileExt"
            sdHash.isNotBlank() -> "$base/$sdHash"
            sourceHash.isNotBlank() -> "$base/$sourceHash"
            else -> base
        }
    }

    private fun addAuthTokenToUrl(url: String, authToken: String): String {
        val value = url.trim()
        val token = authToken.trim()
        if (value.isBlank() || token.isBlank() || value.contains("auth_token=")) {
            return value
        }
        return "$value${if (value.contains("?")) "&" else "?"}auth_token=${encodeURIComponent(token)}"
    }

    private fun uniqueUrls(candidates: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val output = mutableListOf<String>()
        candidates.forEach { raw ->
            val url = raw.trim()
            if (url.isNotBlank() && seen.add(url)) {
                output += url
            }
        }
        return output
    }

    private fun pruneCandidatesForTv(candidates: List<String>): List<String> {
        val hasHttps = candidates.any { it.startsWith("https://", ignoreCase = true) }
        if (!hasHttps) {
            return candidates
        }
        return candidates.filterNot { it.startsWith("http://", ignoreCase = true) }
    }

    private fun prioritizeStreamCandidates(candidates: List<String>): List<String> {
        return candidates.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<String>> { rankStreamCandidate(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    private fun rankStreamCandidate(urlRaw: String): Int {
        val url = urlRaw.trim().lowercase()
        if (url.isBlank()) {
            return Int.MIN_VALUE
        }
        var score = 0
        when {
            url.startsWith("https://") -> score += 40
            url.startsWith("http://") -> score -= 30
        }
        if (url.contains("/api/v3/streams/free/")) {
            score += 320
        }
        if (url.contains("cdn.lbryplayer.xyz")) {
            score += 220
        }
        if (url.contains("/v6/streams/")) {
            score += 120
        }
        if (url.contains("auth_token=")) {
            score += 200
        }
        score += when {
            Regex("/playlist\\.m3u8(?:$|\\?)").containsMatchIn(url) -> 160
            Regex("/master\\.m3u8(?:$|\\?)").containsMatchIn(url) -> 90
            Regex("\\.m3u8(?:$|\\?)").containsMatchIn(url) -> 150
            Regex("\\.mp4(?:$|\\?)").containsMatchIn(url) -> 35
            else -> -260
        }
        return score
    }

    private fun getExtensionFromMediaType(mediaTypeRaw: String): String {
        val mediaType = mediaTypeRaw.lowercase().trim()
        return when {
            mediaType.endsWith("mp4") || mediaType.contains("video/mp4") -> "mp4"
            mediaType.endsWith("webm") || mediaType.contains("video/webm") -> "webm"
            mediaType.endsWith("mov") || mediaType.contains("video/quicktime") -> "mov"
            mediaType.endsWith("mkv") || mediaType.contains("video/x-matroska") -> "mkv"
            mediaType.endsWith("mpeg") || mediaType.contains("video/mpeg") -> "mpeg"
            mediaType.endsWith("mpg") -> "mpg"
            mediaType.endsWith("m3u8") || mediaType.contains("application/vnd.apple.mpegurl") -> "m3u8"
            else -> ""
        }
    }

    private suspend fun loadFollowingVideos(page: Int, pageSize: Int): List<VideoClaim> {
        val token = authRepository.getAuthToken()
        val channelIds = listFollowingChannelIds(token).take(180)
        if (channelIds.isEmpty()) {
            return emptyList()
        }

        val params = JSONObject()
            .put("claim_type", JSONArray().put("stream"))
            .put("stream_types", JSONArray().put("video"))
            .put("has_source", true)
            .put("no_totals", true)
            .put("page", page)
            .put("page_size", pageSize)
            .put("order_by", JSONArray().put("release_time"))
            .put("fee_amount", "<=0")
            .put("not_tags", JSONArray(NSFW_TAGS))
            .put("channel_ids", JSONArray(channelIds))
            .put("limit_claims_per_channel", 5)
            .put("release_time", "<${System.currentTimeMillis() / 1000}")

        val result = api.callSdk(method = "claim_search", params = params, authToken = token)
        return normalizeClaims(result.optJSONArray("items") ?: JSONArray())
    }

    private suspend fun listFollowingChannelIds(authToken: String): List<String> {
        if (authToken.isBlank()) {
            return emptyList()
        }
        val follows = api.callRoot(
            resource = "subscription",
            action = "list",
            params = mapOf(
                "auth_token" to authToken,
                "page" to "1",
                "page_size" to "2047",
            ),
            method = "POST",
        )
        return extractFollowingChannelIds(follows)
    }

    private suspend fun isChannelFollowedByPreferenceHandle(
        authToken: String,
        channelId: String,
    ): Boolean {
        if (authToken.isBlank() || channelId.isBlank()) {
            return false
        }
        val channelHandle = resolveChannelHandleById(
            authToken = authToken,
            channelId = channelId,
        )
        if (channelHandle.isBlank()) {
            return false
        }
        val normalizedHandle = channelHandle.lowercase()
        return runCatching {
            preferenceContainsFollowHandle(authToken, "shared", normalizedHandle)
        }.getOrDefault(false) || runCatching {
            preferenceContainsFollowHandle(authToken, "local", normalizedHandle)
        }.getOrDefault(false)
    }

    private suspend fun resolveChannelHandleById(authToken: String, channelId: String): String {
        val payload = runCatching {
            api.callSdk(
                method = "claim_search",
                params = JSONObject()
                    .put("claim_type", JSONArray().put("channel"))
                    .put("claim_ids", JSONArray().put(channelId))
                    .put("no_totals", true)
                    .put("page", 1)
                    .put("page_size", 1),
                authToken = authToken,
            )
        }.getOrNull() ?: return ""
        val first = payload.optJSONArray("items")?.optJSONObject(0) ?: return ""
        val fromNormalized = first.optString("normalized_name").trim()
        if (fromNormalized.startsWith("@")) {
            return fromNormalized
        }
        val fromName = first.optString("name").trim()
        if (fromName.startsWith("@")) {
            return fromName
        }
        return extractChannelHandle(
            firstNonBlank(
                first.optString("permanent_url"),
                first.optString("canonical_url"),
                first.optString("short_url"),
            )
        )
    }

    private suspend fun preferenceContainsFollowHandle(
        authToken: String,
        key: String,
        normalizedHandle: String,
    ): Boolean {
        val payload = api.callSdk(
            method = "preference_get",
            params = JSONObject().put("key", key),
            authToken = authToken,
        )
        val envelope = parsePreferenceEnvelope(payload, key)
        val value = envelope.optJSONObject("value") ?: return false

        val followingRows = mutableListOf<String>()
        when (val following = value.opt("following")) {
            is JSONArray -> {
                for (i in 0 until following.length()) {
                    when (val row = following.opt(i)) {
                        is JSONObject -> {
                            val uri = firstNonBlank(
                                row.optString("uri"),
                                row.optString("url"),
                                row.optString("permanent_url"),
                                row.optString("canonical_url"),
                            )
                            if (uri.isNotBlank()) {
                                followingRows += uri
                            }
                        }
                        is String -> if (row.isNotBlank()) followingRows += row
                    }
                }
            }
            is JSONObject -> {
                val keys = following.keys()
                while (keys.hasNext()) {
                    val uri = keys.next().trim()
                    if (uri.isNotBlank()) {
                        followingRows += uri
                    }
                }
            }
        }

        when (val subscriptions = value.opt("subscriptions")) {
            is JSONArray -> {
                for (i in 0 until subscriptions.length()) {
                    val uri = subscriptions.optString(i).trim()
                    if (uri.isNotBlank()) {
                        followingRows += uri
                    }
                }
            }
            is JSONObject -> {
                val keys = subscriptions.keys()
                while (keys.hasNext()) {
                    val uri = keys.next().trim()
                    if (uri.isNotBlank()) {
                        followingRows += uri
                    }
                }
            }
        }

        return followingRows.any { uri ->
            val handle = extractChannelHandle(uri)
            handle.isNotBlank() && handle.lowercase() == normalizedHandle
        }
    }

    private suspend fun persistChannelFollowPreference(
        authToken: String,
        channelId: String,
        channelName: String,
        shouldFollow: Boolean,
    ) {
        val sharedResult = runCatching {
            updateFollowPreferenceForKey(
                authToken = authToken,
                key = "shared",
                channelId = channelId,
                channelName = channelName,
                shouldFollow = shouldFollow,
            )
        }
        if (sharedResult.isSuccess) {
            return
        }
        runCatching {
            updateFollowPreferenceForKey(
                authToken = authToken,
                key = "local",
                channelId = channelId,
                channelName = channelName,
                shouldFollow = shouldFollow,
            )
        }
    }

    private suspend fun updateFollowPreferenceForKey(
        authToken: String,
        key: String,
        channelId: String,
        channelName: String,
        shouldFollow: Boolean,
    ) {
        val payload = api.callSdk(
            method = "preference_get",
            params = JSONObject().put("key", key),
            authToken = authToken,
        )
        val envelope = parsePreferenceEnvelope(payload, key)
        val value = envelope.optJSONObject("value") ?: JSONObject().also { envelope.put("value", it) }
        val channelUri = resolveChannelUriForPreference(
            authToken = authToken,
            channelId = channelId,
            channelName = channelName,
        )
        updateSubscriptionsPreference(
            value = value,
            channelId = channelId,
            channelUri = channelUri,
            shouldFollow = shouldFollow,
        )
        updateFollowingPreference(
            value = value,
            channelId = channelId,
            channelUri = channelUri,
            shouldFollow = shouldFollow,
        )
        api.callSdk(
            method = "preference_set",
            params = JSONObject()
                .put("key", key)
                .put("value", envelope.toString()),
            authToken = authToken,
        )
    }

    private suspend fun resolveChannelUriForPreference(
        authToken: String,
        channelId: String,
        channelName: String,
    ): String {
        val resolvedUri = runCatching {
            val payload = api.callSdk(
                method = "claim_search",
                params = JSONObject()
                    .put("claim_type", JSONArray().put("channel"))
                    .put("claim_ids", JSONArray().put(channelId))
                    .put("no_totals", true)
                    .put("page", 1)
                    .put("page_size", 1),
                authToken = authToken,
            )
            val first = payload.optJSONArray("items")?.optJSONObject(0)
            val permanent = first?.optString("permanent_url").orEmpty().trim()
            if (permanent.startsWith("lbry://", ignoreCase = true)) {
                return@runCatching permanent
            }

            val uriLike = firstNonBlank(
                first?.optString("canonical_url").orEmpty(),
                first?.optString("short_url").orEmpty(),
                first?.optString("url").orEmpty(),
                first?.optString("uri").orEmpty(),
            )
            val handleFromUri = extractChannelHandle(uriLike)
            if (handleFromUri.isNotBlank()) {
                return@runCatching "lbry://$handleFromUri:$channelId"
            }

            val normalizedName = first?.optString("normalized_name").orEmpty().trim()
            val handleFromName = if (normalizedName.startsWith("@")) normalizedName else ""
            if (handleFromName.isNotBlank()) {
                return@runCatching "lbry://$handleFromName:$channelId"
            }

            ""
        }.getOrDefault("")
        if (resolvedUri.startsWith("lbry://", ignoreCase = true)) {
            return resolvedUri
        }

        val fallbackHandle = channelName.trim()
            .removePrefix("lbry://")
            .let { raw ->
                when {
                    raw.startsWith("@") && CHANNEL_HANDLE_REGEX.matches(raw) -> raw
                    CHANNEL_HANDLE_REGEX.matches("@$raw") -> "@$raw"
                    else -> ""
                }
            }
        return if (fallbackHandle.isNotBlank()) "lbry://$fallbackHandle:$channelId" else ""
    }

    private fun updateSubscriptionsPreference(
        value: JSONObject,
        channelId: String,
        channelUri: String,
        shouldFollow: Boolean,
    ) {
        val subscriptions = mutableListOf<String>()
        when (val existing = value.opt("subscriptions")) {
            is JSONArray -> {
                for (i in 0 until existing.length()) {
                    val uri = existing.optString(i).trim()
                    if (uri.isNotBlank()) {
                        subscriptions += uri
                    }
                }
            }
            is JSONObject -> {
                val keys = existing.keys()
                while (keys.hasNext()) {
                    val uri = keys.next().trim()
                    if (uri.isNotBlank()) {
                        subscriptions += uri
                    }
                }
            }
        }

        val filtered = subscriptions
            .filterNot { matchesChannelUri(it, channelId) }
            .toMutableList()
        if (shouldFollow && channelUri.isNotBlank()) {
            filtered += channelUri
        }
        val seen = mutableSetOf<String>()
        val deduped = filtered.filter { seen.add(it.lowercase()) }
        value.put("subscriptions", JSONArray(deduped))
    }

    private fun updateFollowingPreference(
        value: JSONObject,
        channelId: String,
        channelUri: String,
        shouldFollow: Boolean,
    ) {
        val following = mutableListOf<JSONObject>()
        when (val existing = value.opt("following")) {
            is JSONArray -> {
                for (i in 0 until existing.length()) {
                    when (val row = existing.opt(i)) {
                        is JSONObject -> following += JSONObject(row.toString())
                        is String -> {
                            val uri = row.trim()
                            if (uri.isNotBlank()) {
                                following += JSONObject()
                                    .put("uri", uri)
                                    .put("notificationsDisabled", false)
                            }
                        }
                    }
                }
            }
            is JSONObject -> {
                val keys = existing.keys()
                while (keys.hasNext()) {
                    val uri = keys.next().trim()
                    if (uri.isNotBlank()) {
                        following += JSONObject()
                            .put("uri", uri)
                            .put("notificationsDisabled", false)
                    }
                }
            }
        }

        val filtered = following
            .filterNot { row ->
                val uri = firstNonBlank(
                    row.optString("uri"),
                    row.optString("url"),
                    row.optString("canonical_url"),
                    row.optString("permanent_url"),
                )
                val entryChannelId = normalizeClaimId(
                    firstNonBlank(
                        row.optString("claim_id"),
                        row.optString("claimId"),
                        row.optString("channel_id"),
                        row.optString("channelId"),
                        extractClaimIdFromUri(uri),
                    )
                )
                (entryChannelId.isNotBlank() && sameChannelId(entryChannelId, channelId)) ||
                    (uri.isNotBlank() && matchesChannelUri(uri, channelId))
            }
            .toMutableList()

        if (shouldFollow && channelUri.isNotBlank()) {
            filtered += JSONObject()
                .put("uri", channelUri)
                .put("notificationsDisabled", false)
        }

        val output = JSONArray()
        val seen = mutableSetOf<String>()
        filtered.forEach { row ->
            val uri = firstNonBlank(
                row.optString("uri"),
                row.optString("url"),
                row.optString("canonical_url"),
                row.optString("permanent_url"),
            ).trim()
            if (uri.isBlank()) {
                return@forEach
            }
            if (!seen.add(uri.lowercase())) {
                return@forEach
            }
            output.put(
                JSONObject()
                    .put("uri", uri)
                    .put("notificationsDisabled", row.optBoolean("notificationsDisabled", false))
            )
        }
        value.put("following", output)
    }

    private fun parsePreferenceEnvelope(payload: JSONObject, keyName: String): JSONObject {
        val keyed = payload.opt(keyName)
        val base = when (keyed) {
            is JSONObject -> keyed
            is String -> parseMaybeJson(keyed) as? JSONObject ?: JSONObject()
            null, JSONObject.NULL -> payload
            else -> JSONObject()
        }
        val envelope = JSONObject(base.toString())
        val value = when (val rawValue = envelope.opt("value")) {
            is JSONObject -> rawValue
            is String -> parseMaybeJson(rawValue) as? JSONObject ?: JSONObject()
            else -> JSONObject()
        }
        envelope.put("value", value)
        if (!envelope.has("type")) {
            envelope.put("type", "object")
        }
        if (!envelope.has("version")) {
            envelope.put("version", "0.1")
        }
        return envelope
    }

    private fun matchesChannelUri(uriRaw: String, channelIdRaw: String): Boolean {
        val channelId = normalizeClaimIdLoose(channelIdRaw)
        if (channelId.isBlank()) {
            return false
        }
        val uriClaimId = extractClaimIdFromUri(uriRaw)
        if (uriClaimId.isBlank()) {
            return false
        }
        return claimIdsMatch(uriClaimId, channelId)
    }

    private fun extractClaimIdFromUri(uriRaw: String): String {
        val uri = uriRaw.trim().lowercase()
        if (uri.isBlank()) {
            return ""
        }
        val match = URI_CLAIM_ID_REGEX.find(uri) ?: return ""
        return normalizeClaimIdLoose(match.groupValues.getOrNull(1).orEmpty())
    }

    private fun extractChannelHandle(uriRaw: String): String {
        val uri = uriRaw.trim()
        if (!uri.startsWith("lbry://", ignoreCase = true)) {
            return ""
        }
        val body = uri.removePrefix("lbry://").removePrefix("LBRY://")
        if (!body.startsWith("@")) {
            return ""
        }
        val end = body.indexOfAny(charArrayOf(':', '#', '/', '?')).let { if (it < 0) body.length else it }
        val handle = body.substring(0, end).trim()
        return if (CHANNEL_HANDLE_REGEX.matches(handle)) handle else ""
    }

    private fun sameChannelId(leftRaw: String, rightRaw: String): Boolean {
        val left = normalizeClaimIdLoose(leftRaw)
        val right = normalizeClaimIdLoose(rightRaw)
        if (left.isBlank() || right.isBlank()) {
            return false
        }
        return claimIdsMatch(left, right)
    }

    private fun claimIdsMatch(leftRaw: String, rightRaw: String): Boolean {
        val left = normalizeClaimIdLoose(leftRaw)
        val right = normalizeClaimIdLoose(rightRaw)
        if (left.isBlank() || right.isBlank()) {
            return false
        }
        return left == right || left.startsWith(right) || right.startsWith(left)
    }

    private fun extractFollowingChannelIds(payload: Any?): List<String> {
        if (payload is String) {
            return extractFollowingChannelIds(parseMaybeJson(payload))
        }
        val rows: List<Any?> = when (payload) {
            is JSONArray -> jsonArrayToList(payload)
            is JSONObject -> when {
                payload.opt("subscriptions") is JSONArray -> jsonArrayToList(payload.optJSONArray("subscriptions") ?: JSONArray())
                payload.opt("subscriptions") is JSONObject -> jsonObjectKeys(payload.optJSONObject("subscriptions"))
                payload.opt("following") is JSONArray -> jsonArrayToList(payload.optJSONArray("following") ?: JSONArray())
                payload.opt("following") is JSONObject -> jsonObjectKeys(payload.optJSONObject("following"))
                payload.opt("items") is JSONArray -> jsonArrayToList(payload.optJSONArray("items") ?: JSONArray())
                payload.opt("claim_ids") is JSONArray -> jsonArrayToList(payload.optJSONArray("claim_ids") ?: JSONArray())
                payload.opt("claimIds") is JSONArray -> jsonArrayToList(payload.optJSONArray("claimIds") ?: JSONArray())
                payload.opt("channels") is JSONArray -> jsonArrayToList(payload.optJSONArray("channels") ?: JSONArray())
                else -> emptyList()
            }
            else -> emptyList()
        }

        val output = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        rows.forEach { row ->
            val candidate = when (row) {
                is JSONObject -> firstNonBlank(
                    row.optString("claim_id"),
                    row.optString("claimId"),
                    row.optString("channel_id"),
                    row.optString("channelId"),
                    row.optString("id"),
                    row.optString("permanent_url"),
                    row.optString("canonical_url"),
                    row.optString("short_url"),
                    row.optString("uri"),
                    row.optString("url"),
                )
                null -> ""
                else -> row.toString()
            }
            val strict = normalizeClaimId(candidate)
            val loose = normalizeClaimIdLoose(candidate)
            val normalized = strict.ifBlank { loose }
            if (normalized.isNotBlank() && seen.add(normalized.lowercase())) {
                output += normalized
            }
        }
        return output
    }

    private fun jsonArrayToList(array: JSONArray): List<Any?> =
        (0 until array.length()).map { index -> array.opt(index) }

    private fun jsonObjectKeys(obj: JSONObject?): List<String> {
        if (obj == null) {
            return emptyList()
        }
        val keys = mutableListOf<String>()
        val iter = obj.keys()
        while (iter.hasNext()) {
            keys += iter.next()
        }
        return keys
    }

    suspend fun hasWatchLaterVideos(): Boolean {
        val token = runCatching { authRepository.getAuthToken() }.getOrDefault("")
        if (token.isBlank()) {
            return false
        }
        val collection = runCatching { getWatchLaterCollectionData(token) }
            .getOrDefault(WatchLaterCollection())
        return collection.claimRefs.isNotEmpty() ||
            collection.resolvedClaims.isNotEmpty() ||
            collection.itemCount > 0
    }

    private suspend fun loadWatchLaterVideos(page: Int, pageSize: Int): List<VideoClaim> {
        val token = runCatching { authRepository.getAuthToken() }.getOrDefault("")
        if (token.isBlank()) {
            return emptyList()
        }

        val collection = runCatching { getWatchLaterCollectionData(token) }
            .getOrDefault(WatchLaterCollection())
        val allRefs = collection.claimRefs
        val start = ((page - 1).coerceAtLeast(0)) * pageSize
        val end = (start + pageSize).coerceAtMost(allRefs.size)
        val pageRefs = if (start in 0 until end) allRefs.subList(start, end) else emptyList()

        if (pageRefs.isEmpty()) {
            if (collection.resolvedClaims.isEmpty()) {
                return emptyList()
            }
            val normalizedResolved = normalizeClaims(JSONArray(collection.resolvedClaims))
            return normalizedResolved.drop(start).take(pageSize)
        }

        val pageClaimIds = mutableListOf<String>()
        val pageUris = mutableListOf<String>()
        pageRefs.forEach { ref ->
            val claimId = normalizeClaimId(ref)
            when {
                claimId.isNotBlank() -> pageClaimIds += claimId
                normalizeUriKey(ref).isNotBlank() -> pageUris += ref
            }
        }

        val rows = mutableListOf<JSONObject>()
        if (pageClaimIds.isNotEmpty()) {
            val claimResult = runCatching {
                api.callSdk(
                    method = "claim_search",
                    params = JSONObject()
                        .put("no_totals", true)
                        .put("page", 1)
                        .put("page_size", pageClaimIds.size)
                        .put("claim_ids", JSONArray(pageClaimIds)),
                    authToken = token,
                )
            }.getOrNull()
            val claimItems = claimResult?.optJSONArray("items") ?: JSONArray()
            for (i in 0 until claimItems.length()) {
                claimItems.optJSONObject(i)?.let(rows::add)
            }
        }
        if (pageUris.isNotEmpty()) {
            pageUris.forEach { uri ->
                runCatching {
                    api.callSdk(
                        method = "get",
                        params = JSONObject().put("uri", uri),
                        authToken = token,
                    )
                }.getOrNull()?.let(rows::add)
            }
        }

        val ordered = reorderClaimsByRefs(rows, pageRefs)
        if (ordered.isNotEmpty()) {
            return normalizeClaims(JSONArray(ordered)).take(pageSize)
        }

        val fallbackRows = reorderClaimsByRefs(collection.resolvedClaims, pageRefs)
        return normalizeClaims(JSONArray(fallbackRows)).take(pageSize)
    }

    private suspend fun getWatchLaterCollectionData(authToken: String): WatchLaterCollection {
        val sharedCollection = runCatching {
            getWatchLaterFromPreference(authToken = authToken, key = "shared")
        }.getOrDefault(WatchLaterCollection())
        if (hasWatchLaterCollectionData(sharedCollection)) {
            return sharedCollection
        }

        val localCollection = runCatching {
            getWatchLaterFromPreference(authToken = authToken, key = "local")
        }.getOrDefault(WatchLaterCollection())
        if (hasWatchLaterCollectionData(localCollection)) {
            return localCollection
        }

        val syncCollection = runCatching {
            getWatchLaterFromSync(authToken)
        }.getOrDefault(WatchLaterCollection())
        if (hasWatchLaterCollectionData(syncCollection)) {
            return syncCollection
        }

        val collectionList = runCatching {
            api.callSdk(
                method = "collection_list",
                params = JSONObject()
                    .put("page", 1)
                    .put("page_size", 2047)
                    .put("resolve", true),
                authToken = authToken,
            )
        }.getOrNull()
        val listedCollection = extractWatchLaterCollection(collectionList)
        if (hasWatchLaterCollectionData(listedCollection)) {
            return listedCollection
        }

        return WatchLaterCollection()
    }

    private suspend fun getWatchLaterFromPreference(authToken: String, key: String): WatchLaterCollection {
        val payload = api.callSdk(
            method = "preference_get",
            params = JSONObject().put("key", key),
            authToken = authToken,
        )
        return extractWatchLaterFromSharedCandidates(payload)
    }

    private suspend fun getWatchLaterFromSync(authToken: String): WatchLaterCollection {
        val syncHashPayload = runCatching {
            api.callSdk(
                method = "sync_hash",
                params = JSONObject(),
                authToken = authToken,
            )
        }.getOrNull() ?: return WatchLaterCollection()

        val syncHash = extractSyncHash(syncHashPayload)
        if (syncHash.isBlank()) {
            return WatchLaterCollection()
        }

        val payload = runCatching {
            api.callRoot(
                resource = "sync",
                action = "get",
                params = mapOf(
                    "auth_token" to authToken,
                    "hash" to syncHash,
                ),
                method = "POST",
            )
        }.getOrNull()

        return extractWatchLaterFromSharedCandidates(payload)
    }

    private fun extractWatchLaterFromSharedCandidates(payload: Any?): WatchLaterCollection {
        val aggregateRefs = mutableListOf<String>()
        val aggregateResolved = mutableListOf<JSONObject>()
        var aggregateCount = 0

        fun merge(collection: WatchLaterCollection) {
            if (!hasWatchLaterCollectionData(collection)) {
                return
            }
            aggregateRefs += collection.claimRefs
            aggregateResolved += collection.resolvedClaims
            aggregateCount = maxOf(aggregateCount, collection.itemCount)
        }

        merge(extractWatchLaterCollection(payload))
        merge(extractWatchLaterCollection(unwrapSharedValue(payload, depth = 0)))

        val root = payload as? JSONObject
        if (root != null) {
            val specificCandidates = listOf(
                root.opt("value"),
                root.opt("shared"),
                root.optJSONObject("data")?.opt("value"),
                root.optJSONObject("data")?.opt("shared"),
                root.optJSONObject("result")?.opt("value"),
                root.optJSONObject("result")?.opt("shared"),
            )
            specificCandidates.forEach { candidate ->
                merge(extractWatchLaterCollection(candidate))
                merge(extractWatchLaterCollection(unwrapSharedValue(candidate, depth = 0)))
            }

            walkJson(
                value = unwrapSharedValue(root, depth = 0),
                depth = 0,
                seen = mutableSetOf(),
            ) { node ->
                if (node !is JSONObject) {
                    return@walkJson
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!isWatchLaterToken(key)) {
                        continue
                    }
                    val wrapped = JSONObject().put("watchlater", node.opt(key))
                    merge(extractWatchLaterCollection(wrapped))
                    merge(extractWatchLaterCollection(node.opt(key)))
                }
            }
        }

        return WatchLaterCollection(
            claimRefs = uniqueWatchLaterRefs(aggregateRefs),
            resolvedClaims = aggregateResolved,
            itemCount = aggregateCount,
        )
    }

    private fun walkJson(
        value: Any?,
        depth: Int,
        seen: MutableSet<Int>,
        visit: (Any?) -> Unit,
    ) {
        if (value == null || value == JSONObject.NULL || depth > 8) {
            return
        }
        visit(value)

        when (value) {
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    walkJson(value.opt(i), depth + 1, seen, visit)
                }
            }
            is JSONObject -> {
                val identity = System.identityHashCode(value)
                if (!seen.add(identity)) {
                    return
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    walkJson(value.opt(key), depth + 1, seen, visit)
                }
            }
        }
    }

    private fun unwrapSharedValue(rawValue: Any?, depth: Int): Any? {
        if (rawValue == null || rawValue == JSONObject.NULL || depth > 6) {
            return rawValue
        }
        return when (rawValue) {
            is String -> {
                val parsed = parseMaybeJson(rawValue)
                if (parsed == null) rawValue else unwrapSharedValue(parsed, depth + 1)
            }
            is JSONObject -> {
                val fromValue = unwrapSharedValue(rawValue.opt("value"), depth + 1)
                if (fromValue is JSONObject || fromValue is JSONArray) {
                    return fromValue
                }
                val fromShared = unwrapSharedValue(rawValue.opt("shared"), depth + 1)
                if (fromShared is JSONObject || fromShared is JSONArray) {
                    return fromShared
                }
                rawValue
            }
            else -> rawValue
        }
    }

    private fun parseMaybeJson(raw: String): Any? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.length < 2) {
            return null
        }
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractSyncHash(payload: Any?): String {
        when (payload) {
            is String -> return payload.trim()
            is JSONObject -> {
                val direct = firstNonBlank(
                    payload.optString("hash"),
                    payload.optString("sync_hash"),
                )
                if (direct.isNotBlank()) {
                    return direct
                }
                val fromData = payload.optJSONObject("data")
                val fromValue = payload.optJSONObject("value")
                return firstNonBlank(
                    fromData?.optString("hash").orEmpty(),
                    fromData?.optString("sync_hash").orEmpty(),
                    fromValue?.optString("hash").orEmpty(),
                    fromValue?.optString("sync_hash").orEmpty(),
                )
            }
            else -> return ""
        }
    }

    private fun extractWatchLaterCollection(payload: Any?): WatchLaterCollection {
        val claimRefs = mutableListOf<String>()
        val resolvedClaims = mutableListOf<JSONObject>()
        val candidates = mutableListOf<Any?>()
        var watchLaterCount = 0

        fun appendCandidate(value: Any?) {
            when (value) {
                null, JSONObject.NULL -> Unit
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        appendCandidate(value.opt(i))
                    }
                }
                is String -> {
                    val parsed = parseMaybeJson(value)
                    if (parsed != null) {
                        appendCandidate(parsed)
                    }
                }
                else -> candidates += value
            }
        }

        val data = payload as? JSONObject
        appendCandidate(data?.opt("watchlater"))
        appendCandidate(data?.opt("watchLater"))
        appendCandidate(data?.opt("watch_later"))
        appendCandidate(data?.optJSONObject("builtinCollections")?.opt("watchlater"))
        appendCandidate(data?.optJSONObject("builtinCollections")?.opt("watchLater"))
        appendCandidate(data?.optJSONObject("builtinCollections")?.opt("watch_later"))
        appendCandidate(data?.optJSONObject("builtin_collections")?.opt("watchlater"))
        appendCandidate(data?.optJSONObject("builtin_collections")?.opt("watchLater"))
        appendCandidate(data?.optJSONObject("builtin_collections")?.opt("watch_later"))
        appendCandidate(data?.optJSONObject("builtin")?.opt("watchlater"))
        appendCandidate(data?.optJSONObject("builtin")?.opt("watchLater"))
        appendCandidate(data?.optJSONObject("builtin")?.opt("watch_later"))
        appendCandidate(data?.opt("items"))
        appendCandidate(data?.opt("collections"))
        appendCandidate(data?.optJSONObject("data")?.opt("watchlater"))
        appendCandidate(data?.optJSONObject("data")?.opt("watchLater"))
        appendCandidate(data?.optJSONObject("data")?.opt("watch_later"))
        appendCandidate(data?.optJSONObject("data")?.optJSONObject("builtinCollections")?.opt("watchlater"))
        appendCandidate(data?.optJSONObject("data")?.optJSONObject("builtinCollections")?.opt("watchLater"))
        appendCandidate(data?.optJSONObject("data")?.optJSONObject("builtinCollections")?.opt("watch_later"))
        appendCandidate(data?.optJSONObject("data")?.optJSONObject("builtin_collections")?.opt("watchlater"))
        appendCandidate(data?.optJSONObject("data")?.optJSONObject("builtin_collections")?.opt("watchLater"))
        appendCandidate(data?.optJSONObject("data")?.optJSONObject("builtin_collections")?.opt("watch_later"))
        appendCandidate(data?.optJSONObject("data")?.optJSONObject("builtin")?.opt("watchlater"))
        appendCandidate(data?.optJSONObject("data")?.optJSONObject("builtin")?.opt("watchLater"))
        appendCandidate(data?.optJSONObject("data")?.optJSONObject("builtin")?.opt("watch_later"))
        appendCandidate(data?.optJSONObject("result")?.opt("watchlater"))
        appendCandidate(data?.optJSONObject("result")?.opt("watchLater"))
        appendCandidate(data?.optJSONObject("result")?.opt("watch_later"))
        appendCandidate(data?.optJSONObject("result")?.optJSONObject("builtinCollections")?.opt("watchlater"))
        appendCandidate(data?.optJSONObject("result")?.optJSONObject("builtinCollections")?.opt("watchLater"))
        appendCandidate(data?.optJSONObject("result")?.optJSONObject("builtinCollections")?.opt("watch_later"))
        appendCandidate(data?.optJSONObject("result")?.optJSONObject("builtin_collections")?.opt("watchlater"))
        appendCandidate(data?.optJSONObject("result")?.optJSONObject("builtin_collections")?.opt("watchLater"))
        appendCandidate(data?.optJSONObject("result")?.optJSONObject("builtin_collections")?.opt("watch_later"))
        appendCandidate(data?.optJSONObject("result")?.optJSONObject("builtin")?.opt("watchlater"))
        appendCandidate(data?.optJSONObject("result")?.optJSONObject("builtin")?.opt("watchLater"))
        appendCandidate(data?.optJSONObject("result")?.optJSONObject("builtin")?.opt("watch_later"))
        appendCandidate(data?.optJSONObject("data")?.opt("items"))
        appendCandidate(data?.optJSONObject("data")?.opt("collections"))
        appendCandidate(data?.optJSONObject("result")?.opt("items"))
        appendCandidate(data?.optJSONObject("result")?.opt("collections"))

        if (isWatchLaterCollectionEntry(data)) {
            watchLaterCount = maxOf(watchLaterCount, getWatchLaterEntryCount(data))
            collectWatchLaterItems(data, claimRefs, resolvedClaims, depth = 0)
        }
        val dataNode = data?.optJSONObject("data")
        if (isWatchLaterCollectionEntry(dataNode)) {
            watchLaterCount = maxOf(watchLaterCount, getWatchLaterEntryCount(dataNode))
            collectWatchLaterItems(dataNode, claimRefs, resolvedClaims, depth = 0)
        }
        val resultNode = data?.optJSONObject("result")
        if (isWatchLaterCollectionEntry(resultNode)) {
            watchLaterCount = maxOf(watchLaterCount, getWatchLaterEntryCount(resultNode))
            collectWatchLaterItems(resultNode, claimRefs, resolvedClaims, depth = 0)
        }

        candidates.forEach { candidate ->
            val candidateObject = candidate as? JSONObject ?: return@forEach
            if (isWatchLaterCollectionEntry(candidateObject)) {
                watchLaterCount = maxOf(watchLaterCount, getWatchLaterEntryCount(candidateObject))
                collectWatchLaterItems(candidateObject, claimRefs, resolvedClaims, depth = 0)
            }
        }

        if (claimRefs.isEmpty() && resolvedClaims.isEmpty() && candidates.size == 1) {
            val fallback = candidates.firstOrNull() as? JSONObject
            if (fallback != null) {
                watchLaterCount = maxOf(watchLaterCount, getWatchLaterEntryCount(fallback))
                collectWatchLaterItems(fallback, claimRefs, resolvedClaims, depth = 0)
            }
        }

        return WatchLaterCollection(
            claimRefs = uniqueWatchLaterRefs(claimRefs),
            resolvedClaims = resolvedClaims,
            itemCount = watchLaterCount,
        )
    }

    private fun collectWatchLaterItems(
        value: Any?,
        claimRefs: MutableList<String>,
        resolvedClaims: MutableList<JSONObject>,
        depth: Int,
    ) {
        if (value == null || value == JSONObject.NULL || depth > 8) {
            return
        }

        when (value) {
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    collectWatchLaterItems(value.opt(i), claimRefs, resolvedClaims, depth + 1)
                }
            }
            is String -> {
                val claimId = normalizeClaimId(value)
                if (claimId.isNotBlank()) {
                    claimRefs += claimId
                } else if (normalizeUriKey(value).isNotBlank()) {
                    claimRefs += value
                }
            }
            is JSONObject -> {
                val claimId = normalizeClaimId(
                    firstNonBlank(
                        value.optString("claim_id"),
                        value.optString("claimId"),
                        value.optString("id"),
                    )
                )
                if (claimId.isNotBlank()) {
                    claimRefs += claimId
                } else {
                    val uri = firstNonBlank(
                        value.optString("uri"),
                        value.optString("canonical_url"),
                        value.optString("permanent_url"),
                        value.optString("short_url"),
                    )
                    if (normalizeUriKey(uri).isNotBlank()) {
                        claimRefs += uri
                    }
                }

                if (looksLikeResolvedClaim(value)) {
                    resolvedClaims += value
                }

                val nestedValues = listOf(
                    value.opt("claim_refs"),
                    value.opt("claimRefs"),
                    value.opt("items"),
                    value.opt("claims"),
                    value.opt("claim"),
                    value.optJSONObject("value")?.opt("items"),
                    value.optJSONObject("value")?.opt("claims"),
                    value.optJSONObject("value")?.opt("claim"),
                    value.optJSONObject("value"),
                )
                nestedValues.forEach { nested ->
                    collectWatchLaterItems(nested, claimRefs, resolvedClaims, depth + 1)
                }
            }
        }
    }

    private fun reorderClaimsByRefs(claims: List<JSONObject>, refs: List<String>): List<JSONObject> {
        if (claims.isEmpty() || refs.isEmpty()) {
            return claims
        }

        val byId = mutableMapOf<String, JSONObject>()
        val byUri = mutableMapOf<String, JSONObject>()
        claims.forEach { claim ->
            val claimId = normalizeClaimId(claim.optString("claim_id"))
            if (claimId.isNotBlank() && !byId.containsKey(claimId)) {
                byId[claimId] = claim
            }
            listOf(
                claim.optString("canonical_url"),
                claim.optString("permanent_url"),
                claim.optString("short_url"),
            ).forEach { uri ->
                val key = normalizeUriKey(uri)
                if (key.isNotBlank() && !byUri.containsKey(key)) {
                    byUri[key] = claim
                }
            }
        }

        val used = mutableSetOf<String>()
        val ordered = mutableListOf<JSONObject>()
        refs.forEach { ref ->
            val claimId = normalizeClaimId(ref)
            val uriKey = if (claimId.isBlank()) normalizeUriKey(ref) else ""
            val key = if (claimId.isNotBlank()) claimId else uriKey
            if (key.isBlank() || used.contains(key)) {
                return@forEach
            }
            val match = if (claimId.isNotBlank()) byId[claimId] else byUri[uriKey]
            if (match != null) {
                ordered += match
                used += key
            }
        }
        claims.forEach { claim ->
            val claimId = normalizeClaimId(claim.optString("claim_id"))
            val uriKey = normalizeUriKey(
                firstNonBlank(
                    claim.optString("canonical_url"),
                    claim.optString("permanent_url"),
                    claim.optString("short_url"),
                )
            )
            if ((claimId.isNotBlank() && used.contains(claimId)) || (uriKey.isNotBlank() && used.contains(uriKey))) {
                return@forEach
            }
            ordered += claim
        }
        return ordered
    }

    private fun uniqueWatchLaterRefs(refs: List<String>): List<String> {
        val output = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        refs.forEach { ref ->
            val trimmed = ref.trim()
            if (trimmed.isBlank()) {
                return@forEach
            }
            val key = normalizeClaimId(trimmed).ifBlank { trimmed.lowercase() }
            if (seen.contains(key)) {
                return@forEach
            }
            seen += key
            output += trimmed
        }
        return output
    }

    private fun hasWatchLaterCollectionData(collection: WatchLaterCollection): Boolean =
        collection.claimRefs.isNotEmpty() ||
            collection.resolvedClaims.isNotEmpty() ||
            collection.itemCount > 0

    private fun isWatchLaterCollectionEntry(value: JSONObject?): Boolean {
        if (value == null) {
            return false
        }
        val tokens = listOf(
            value.optString("id"),
            value.optString("name"),
            value.optString("title"),
            value.optString("key"),
            value.optString("collection_id"),
            value.optString("collectionId"),
            value.optString("collection_name"),
            value.optString("collectionName"),
            value.optJSONObject("value")?.optString("name").orEmpty(),
            value.optJSONObject("value")?.optString("title").orEmpty(),
        ).map(::normalizeKeyToken)
        return tokens.any { it == "watchlater" }
    }

    private fun getWatchLaterEntryCount(value: JSONObject?): Int {
        if (value == null) {
            return 0
        }
        val countCandidates = listOf(
            value.opt("itemCount"),
            value.opt("item_count"),
            value.opt("count"),
            value.opt("size"),
            value.opt("length"),
            value.optJSONObject("value")?.opt("itemCount"),
            value.optJSONObject("value")?.opt("item_count"),
            value.optJSONObject("value")?.opt("count"),
        )
        countCandidates.forEach { candidate ->
            when (candidate) {
                is Number -> return candidate.toInt().coerceAtLeast(0)
                is String -> candidate.toIntOrNull()?.let { return it.coerceAtLeast(0) }
            }
        }
        return 0
    }

    private fun looksLikeResolvedClaim(value: JSONObject): Boolean {
        val claimId = normalizeClaimId(value.optString("claim_id"))
        if (claimId.isBlank()) {
            return false
        }
        val maybeValue = value.optJSONObject("value")
        return maybeValue != null || value.has("canonical_url") || value.has("name")
    }

    private fun normalizeClaimId(value: String): String {
        val candidate = value.trim().lowercase()
        if (candidate.isBlank()) {
            return ""
        }
        CLAIM_ID_REGEX.find(candidate)?.let { return it.value }
        return ""
    }

    private fun normalizeClaimIdLoose(value: String): String {
        val candidate = value.trim().lowercase()
        if (candidate.isBlank()) {
            return ""
        }
        return if (CLAIM_ID_LOOSE_REGEX.matches(candidate)) candidate else ""
    }

    private fun normalizeUriKey(value: String): String {
        val candidate = value.trim()
        if (candidate.startsWith("lbry://", ignoreCase = true)) {
            return candidate.lowercase()
        }
        return ""
    }

    private fun normalizeKeyToken(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun isWatchLaterToken(value: String): Boolean =
        normalizeKeyToken(value) == "watchlater"

    private fun normalizeClaims(items: JSONArray): List<VideoClaim> =
        (0 until items.length()).mapNotNull { idx ->
            val obj = items.optJSONObject(idx) ?: return@mapNotNull null
            val claimId = obj.optString("claim_id")
            if (claimId.isBlank()) return@mapNotNull null

            val value = obj.optJSONObject("value")
            val signingChannel = obj.optJSONObject("signing_channel")
            val signingChannelValue = signingChannel?.optJSONObject("value")
            val txid = obj.optString("txid").trim()
            val nout = parseNout(obj.opt("nout"))
            val outpoint = firstNonBlank(
                obj.optString("outpoint").trim(),
                if (txid.isNotBlank() && nout.isNotBlank()) "$txid:$nout" else "",
            )

            VideoClaim(
                claimId = claimId,
                title = value?.optString("title").orEmpty()
                    .ifBlank { obj.optString("title") }
                    .ifBlank { obj.optString("name") },
                canonicalUrl = obj.optString("canonical_url")
                    .ifBlank { obj.optString("short_url") }
                    .ifBlank { obj.optString("permanent_url") },
                normalizedName = obj.optString("normalized_name")
                    .ifBlank { obj.optString("name") },
                channelName = signingChannelValue?.optString("title").orEmpty()
                    .ifBlank { signingChannel?.optString("title").orEmpty() }
                    .ifBlank { signingChannel?.optString("name").orEmpty() }
                    .ifBlank { "Unknown channel" },
                channelClaimId = firstNonBlank(
                    signingChannel?.optString("claim_id").orEmpty(),
                    signingChannel?.optString("claimId").orEmpty(),
                    obj.optString("channel_id"),
                ),
                channelCanonicalUrl = firstNonBlank(
                    signingChannel?.optString("canonical_url").orEmpty(),
                    signingChannel?.optString("permanent_url").orEmpty(),
                    signingChannel?.optString("short_url").orEmpty(),
                ),
                channelAvatarUrl = normalizeThumbnail(
                    firstNonBlank(
                        signingChannelValue?.optJSONObject("thumbnail")?.optString("url").orEmpty(),
                        signingChannelValue?.optJSONObject("cover")?.optString("url").orEmpty(),
                    )
                ),
                thumbnailUrl = normalizeThumbnail(
                    value?.optJSONObject("thumbnail")?.optString("url").orEmpty()
                ),
                durationSec = parseDurationSeconds(
                    value?.optJSONObject("video")?.opt("duration"),
                    value?.optJSONObject("audio")?.opt("duration"),
                    value?.opt("duration"),
                    obj.opt("duration"),
                ),
                releaseTime = pickLong(
                    value?.opt("release_time"),
                    obj.opt("release_time"),
                    obj.opt("timestamp"),
                    obj.optJSONObject("meta")?.opt("creation_timestamp"),
                ),
                sourceMediaType = firstNonBlank(
                    value?.optJSONObject("source")?.optString("media_type").orEmpty(),
                    value?.optJSONObject("source")?.optString("mediaType").orEmpty(),
                ),
                sourceSdHash = firstNonBlank(
                    value?.optJSONObject("source")?.optString("sd_hash").orEmpty(),
                    value?.optJSONObject("source")?.optString("sdHash").orEmpty(),
                ),
                sourceHash = firstNonBlank(
                    value?.optJSONObject("source")?.optString("source").orEmpty(),
                    value?.optJSONObject("source")?.optString("hash").orEmpty(),
                    value?.optJSONObject("source")?.optString("source_hash").orEmpty(),
                    value?.optJSONObject("source")?.optString("sourceHash").orEmpty(),
                ),
                txid = txid,
                nout = nout,
                outpoint = outpoint,
            )
        }

    private fun parseNout(raw: Any?): String {
        return when (raw) {
            null, JSONObject.NULL -> ""
            is Number -> raw.toInt().toString()
            else -> raw.toString().trim()
        }
    }

    private fun extractCategories(payload: Any?): List<Category> {
        val sources = mutableListOf<JSONObject>()

        when (payload) {
            is JSONObject -> {
                val data = payload.optJSONObject("data")
                val enNode = data?.optJSONObject("en")
                val enCategoriesObject = enNode?.optJSONObject("categories")
                val enCategoriesArray = enNode?.optJSONArray("categories")
                if (enCategoriesObject != null) {
                    sources += parseCategoryObject(enCategoriesObject)
                } else if (enCategoriesArray != null) {
                    sources += parseCategoryArray(enCategoriesArray)
                } else {
                    val categoriesObj = data?.optJSONObject("categories")
                    val categoriesArr = data?.optJSONArray("categories")
                    when {
                        categoriesObj != null -> sources += parseCategoryObject(categoriesObj)
                        categoriesArr != null -> sources += parseCategoryArray(categoriesArr)
                        payload.optJSONArray("categories") != null -> {
                            sources += parseCategoryArray(payload.optJSONArray("categories") ?: JSONArray())
                        }
                        data?.optJSONArray("data") != null -> {
                            sources += parseCategoryArray(data.optJSONArray("data") ?: JSONArray())
                        }
                        data != null -> sources += parseCategoryArraysFromObject(data)
                    }
                }
            }
            is JSONArray -> sources += parseCategoryArray(payload)
        }

        val categories = sources.mapIndexed { index, raw -> mapCategory(raw, "category-$index") }
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .sortedBy { it.sortOrder }

        return prioritizeCategories(categories)
    }

    private fun parseCategoryObject(obj: JSONObject): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            when (value) {
                is JSONObject -> {
                    val entry = JSONObject(value.toString())
                    entry.put("__fallbackKey", key)
                    results += entry
                }
                is JSONArray -> {
                    val wrapper = JSONObject()
                    wrapper.put("__fallbackKey", key)
                    wrapper.put("channel_ids", value)
                    results += wrapper
                }
            }
        }
        return results
    }

    private fun parseCategoryArraysFromObject(obj: JSONObject): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.optJSONArray(key) ?: continue
            val wrapper = JSONObject()
                .put("__fallbackKey", key)
                .put("channel_ids", value)
            results += wrapper
        }
        return results
    }

    private fun parseCategoryArray(array: JSONArray): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            results += obj
        }
        return results
    }

    private fun mapCategory(raw: JSONObject, fallbackId: String): Category {
        val id = raw.optString("id")
            .ifBlank { raw.optString("name") }
            .ifBlank { raw.optString("__fallbackKey") }
            .ifBlank { fallbackId }
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        val titleRaw = raw.optString("displayName")
            .ifBlank { raw.optString("title") }
            .ifBlank { raw.optString("label") }
            .ifBlank { raw.optString("name") }
            .ifBlank { raw.optJSONObject("displayNames")?.optString("en").orEmpty() }
            .ifBlank { id }

        val title = normalizeCategoryTitle(titleRaw, id)

        val channelIds = parseStringArray(
            firstPresentValue(
                raw.opt("channel_ids"),
                raw.opt("channelIds"),
                raw.opt("channels"),
                raw.opt("ids"),
                raw.opt("value"),
            )
        )
        val excludedChannelIds = parseStringArray(
            firstPresentValue(
                raw.opt("excluded_channel_ids"),
                raw.opt("excludedChannelIds"),
                raw.opt("excludeChannelIds"),
                raw.opt("excludedChannels"),
            )
        )
        val tags = parseStringArray(
            firstPresentValue(
                raw.opt("tags"),
                raw.opt("any_tags"),
                raw.opt("anyTags"),
            )
        )

        val sortOrder = when {
            raw.has("sortOrder") -> raw.optInt("sortOrder", 999)
            raw.has("sort_order") -> raw.optInt("sort_order", 999)
            else -> 999
        }

        val orderBy = if (id.contains("trend") || id.contains("popular") || id.contains("hot")) {
            listOf("trending_group", "trending_mixed")
        } else {
            listOf("release_time")
        }

        return Category(
            id = id,
            title = title,
            channelIds = channelIds.take(2048),
            excludedChannelIds = excludedChannelIds.take(2048),
            tags = tags.take(12),
            orderBy = orderBy,
            sortOrder = sortOrder,
        )
    }

    private fun parseStringArray(value: Any?): List<String> {
        return when (value) {
            null -> emptyList()
            is JSONArray -> (0 until value.length()).mapNotNull { idx ->
                when (val entry = value.opt(idx)) {
                    null -> null
                    is JSONObject -> {
                        listOf(
                            entry.optString("claim_id"),
                            entry.optString("claimId"),
                            entry.optString("channel_id"),
                            entry.optString("channelId"),
                            entry.optString("id"),
                        ).firstOrNull { it.isNotBlank() }
                    }
                    else -> entry.toString().takeIf { it.isNotBlank() }
                }
            }
            is JSONObject -> {
                val list = mutableListOf<String>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (value.optBoolean(key, false)) {
                        list += key
                    }
                }
                list
            }
            is String -> listOf(value).filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun firstPresentValue(vararg values: Any?): Any? =
        values.firstOrNull { candidate ->
            candidate != null && candidate != JSONObject.NULL
        }

    private fun normalizeCategoryTitle(raw: String, fallbackId: String): String {
        val base = (if (raw.isBlank()) fallbackId else raw).trim()
        val compact = base.lowercase().replace(Regex("[^a-z0-9]"), "")
        val compactMap = mapOf(
            "popculture" to "Pop Culture",
            "wildwest" to "Wild West",
            "newsandpolitics" to "News & Politics",
            "scienceandtechnology" to "Science & Technology",
            "learningandeducation" to "Learning & Education",
            "artsandentertainment" to "Arts & Entertainment",
        )
        compactMap[compact]?.let { return it }

        return base
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .joinToString(" ") { token -> token.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    private fun prioritizeCategories(categories: List<Category>): List<Category> {
        val filtered = categories.filterNot { it.id == "explore" }
        if (filtered.isEmpty()) return categories

        return filtered.sortedWith(
            compareByDescending<Category> { it.id == "featured" }
                .thenBy { it.sortOrder }
        )
    }

    private fun fallbackCategories(): List<Category> = listOf(
        Category(
            id = "home",
            title = "Home",
            tags = listOf("featured"),
            orderBy = listOf("release_time"),
            sortOrder = 0,
        ),
        Category(
            id = "trending",
            title = "Trending",
            tags = listOf("trending"),
            orderBy = listOf("trending_group", "trending_mixed"),
            sortOrder = 1,
        ),
        Category(
            id = "gaming",
            title = "Gaming",
            tags = listOf("gaming"),
            orderBy = listOf("release_time"),
            sortOrder = 2,
        ),
        Category(
            id = "news",
            title = "News & Politics",
            tags = listOf("news"),
            orderBy = listOf("release_time"),
            sortOrder = 3,
        ),
    )

    private fun buildCategoryQuery(category: Category, page: Int, pageSize: Int): JSONObject {
        val now = System.currentTimeMillis() / 1000
        val params = JSONObject()
            .put("claim_type", JSONArray().put("stream"))
            .put("stream_types", JSONArray().put("video"))
            .put("has_source", true)
            .put("no_totals", true)
            .put("page", page)
            .put("page_size", pageSize)
            .put("order_by", JSONArray(category.orderBy))
            .put("fee_amount", "<=0")
            .put("not_tags", JSONArray(NSFW_TAGS))
            .put("release_time", "<$now")

        if (category.channelIds.isNotEmpty()) {
            params.put("channel_ids", JSONArray(category.channelIds.take(180)))
            params.put("limit_claims_per_channel", 5)
        } else if (category.tags.isNotEmpty()) {
            params.put("any_tags", JSONArray(category.tags.take(8)))
        }

        if (category.excludedChannelIds.isNotEmpty()) {
            params.put("not_channel_ids", JSONArray(category.excludedChannelIds.take(2048)))
        }

        return params
    }

    private fun normalizeThumbnail(rawUrl: String): String {
        if (rawUrl.isBlank()) {
            return ""
        }
        val normalized = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            else -> rawUrl
        }
        if (!normalized.startsWith("http", ignoreCase = true)) {
            return normalized
        }
        if (normalized.contains("thumbnails.odycdn.com/optimize/", ignoreCase = true)) {
            return normalized
        }
        return "$IMAGE_PROCESSOR_BASE$normalized"
    }

    private fun pickLong(vararg values: Any?): Long {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    private fun parseDurationSeconds(vararg values: Any?): Int {
        values.forEach { value ->
            val normalized = parseDurationSeconds(value)
            if (normalized > 0) {
                return normalized
            }
        }
        return 0
    }

    private fun parseDurationSeconds(value: Any?): Int {
        return when (value) {
            is Number -> value.toInt().coerceAtLeast(0)
            is String -> {
                val trimmed = value.trim()
                if (trimmed.isBlank()) {
                    0
                } else if (trimmed.contains(':')) {
                    val parts = trimmed.split(":")
                    var total = 0
                    var multiplier = 1
                    for (idx in parts.lastIndex downTo 0) {
                        val partValue = parts[idx].toIntOrNull() ?: return 0
                        total += partValue * multiplier
                        multiplier *= 60
                    }
                    total.coerceAtLeast(0)
                } else {
                    trimmed.toIntOrNull()?.coerceAtLeast(0) ?: 0
                }
            }
            else -> 0
        }
    }

    private companion object {
        const val FRONTPAGE_URL = "https://odysee.com/${'$'}/api/content/v2/get?format=roku"
        const val LIGHTHOUSE_API = "https://lighthouse.odysee.tv/search"
        const val LIGHTHOUSE_ALT = "https://recsys.odysee.tv/search"
        const val IMAGE_PROCESSOR_BASE = "https://thumbnails.odycdn.com/optimize/s:390:220/quality:85/plain/"
        const val VIDEO_API_BASE = "https://player.odycdn.com"
        const val LEGACY_VIDEO_API_BASE = "https://cdn.lbryplayer.xyz"
        val CLAIM_ID_REGEX = Regex("[0-9a-f]{40}")
        val CLAIM_ID_LOOSE_REGEX = Regex("[0-9a-f]{1,40}")
        val URI_CLAIM_ID_REGEX = Regex("[:#]([0-9a-f]{4,40})")
        val CHANNEL_HANDLE_REGEX = Regex("@[a-zA-Z0-9._-]+")

        val NSFW_TAGS = listOf(
            "porn", "porno", "nsfw", "mature", "xxx", "sex", "creampie", "blowjob",
            "handjob", "vagina", "boobs", "big boobs", "big dick", "pussy", "cumshot",
            "anal", "hard fucking", "ass", "fuck", "hentai",
        )
    }
}
