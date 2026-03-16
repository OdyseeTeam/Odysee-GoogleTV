package com.odysee.androidtv.core.auth

import com.odysee.androidtv.core.network.ApiException
import com.odysee.androidtv.core.network.OdyseeApiClient
import org.json.JSONArray
import org.json.JSONObject

class AuthRepository(
    private val api: OdyseeApiClient,
    private val store: AuthSessionStore,
) {
    suspend fun ensureAnonymousAuth(forceRefresh: Boolean = false): String {
        if (forceRefresh) {
            store.clear()
        }

        if (!forceRefresh) {
            store.load()?.authToken?.takeIf { it.isNotBlank() }?.let { return it }
        }

        val payload = api.callRoot(
            resource = "user",
            action = "new",
            params = mapOf(
                "auth_token" to "",
                "language" to "en",
            ),
            method = "POST",
        ) as? JSONObject ?: throw ApiException("Invalid /user/new response")

        val authToken = payload.optString("auth_token").orEmpty()
        if (authToken.isBlank()) {
            throw ApiException("auth_token was not set in /user/new response")
        }

        store.save(
            AuthSession(
                authToken = authToken,
                userId = payload.opt("id")?.toString().orEmpty(),
            )
        )

        return authToken
    }

    suspend fun requestMagicLink(emailRaw: String) {
        val email = emailRaw.trim().lowercase()
        if (!email.contains("@")) {
            throw ApiException("Enter a valid email")
        }

        val authToken = ensureAnonymousAuth(false)
        val existsData = api.callRoot(
            resource = "user",
            action = "exists",
            params = mapOf(
                "auth_token" to authToken,
                "email" to email,
            ),
            method = "POST",
        )

        val exists = parseExists(existsData)
        if (!exists) {
            throw ApiException("No account found for this email")
        }

        sendMagicLinkEmail(authToken, email)
    }

    suspend fun signInWithPassword(emailRaw: String, passwordRaw: String): AuthUser {
        val email = emailRaw.trim().lowercase()
        val password = passwordRaw
        if (!email.contains("@")) {
            throw ApiException("Enter a valid email")
        }
        if (password.isBlank()) {
            throw ApiException("Enter your password")
        }

        val authToken = ensureAnonymousAuth(false)
        val payload = api.callRoot(
            resource = "user",
            action = "signin",
            params = mapOf(
                "auth_token" to authToken,
                "email" to email,
                "password" to password,
            ),
            method = "POST",
        ) as? JSONObject

        if (payload != null) {
            persistSessionFromUserPayload(payload, fallbackAuthToken = authToken)
        }

        return checkSignedInUser() ?: throw ApiException("Could not load account details after sign in")
    }

    suspend fun checkSignedInUser(): AuthUser? {
        val authToken = ensureAnonymousAuth(false)

        val payload = api.callRoot(
            resource = "user",
            action = "me",
            params = mapOf("auth_token" to authToken),
            method = "POST",
        ) as? JSONObject ?: return null

        persistSessionFromUserPayload(payload, fallbackAuthToken = authToken)

        val data = pickBestUserObject(payload)
        val email = firstNonBlank(
            data.optString("primary_email"),
            data.optString("email"),
            data.optString("user_email"),
        )

        val hasVerifiedEmail = toBool(
            data.opt("has_verified_email"),
            data.opt("hasVerifiedEmail"),
            data.opt("email_verified"),
            data.opt("emailVerified"),
            data.opt("is_email_verified"),
            data.opt("isEmailVerified"),
        )
        val isAuthenticated = hasVerifiedEmail || (
            toBool(data.opt("is_authenticated"), data.opt("authenticated")) &&
                email.isNotBlank() &&
                !data.has("has_verified_email") &&
                !data.has("hasVerifiedEmail")
            )

        if (email.isBlank() || !isAuthenticated) {
            return null
        }

        val activeChannel = firstNonBlank(
            data.optString("active_channel_claim"),
            data.optString("default_channel_claim_id"),
            data.optString("defaultChannelClaimId"),
            data.optString("primary_channel_claim_id"),
            data.optString("primaryChannelClaimId"),
            data.optString("channel_claim_id"),
            data.optString("channelClaimId"),
            data.optJSONObject("default_channel")?.optString("claim_id").orEmpty(),
            data.optJSONObject("default_channel")?.optString("claimId").orEmpty(),
            data.optJSONObject("primary_channel")?.optString("claim_id").orEmpty(),
            data.optJSONObject("primary_channel")?.optString("claimId").orEmpty(),
            data.optJSONObject("channel")?.optString("claim_id").orEmpty(),
            data.optJSONObject("channel")?.optString("claimId").orEmpty(),
        )

        val defaultChannelName = firstNonBlank(
            data.optString("default_channel_title"),
            data.optString("defaultChannelTitle"),
            data.optString("channel_name"),
            data.optString("channelName"),
            data.optString("primary_channel_title"),
            data.optString("primaryChannelTitle"),
            data.optJSONObject("default_channel")?.optJSONObject("value")?.optString("title").orEmpty(),
            data.optJSONObject("primary_channel")?.optJSONObject("value")?.optString("title").orEmpty(),
            data.optJSONObject("channel")?.optJSONObject("value")?.optString("title").orEmpty(),
            data.optJSONObject("default_channel")?.optString("name").orEmpty(),
            data.optJSONObject("primary_channel")?.optString("name").orEmpty(),
            data.optJSONObject("channel")?.optString("name").orEmpty(),
            email,
        )
        val defaultChannelUri = firstNonBlank(
            data.optString("default_channel_url"),
            data.optString("defaultChannelUrl"),
            data.optString("default_channel_uri"),
            data.optString("defaultChannelUri"),
            data.optString("primary_channel_url"),
            data.optString("primaryChannelUrl"),
            data.optString("primary_channel_uri"),
            data.optString("primaryChannelUri"),
            data.optString("channel_url"),
            data.optString("channelUrl"),
            data.optString("channel_uri"),
            data.optString("channelUri"),
            data.optJSONObject("default_channel")?.optString("canonical_url").orEmpty(),
            data.optJSONObject("default_channel")?.optString("permanent_url").orEmpty(),
            data.optJSONObject("default_channel")?.optString("short_url").orEmpty(),
            data.optJSONObject("primary_channel")?.optString("canonical_url").orEmpty(),
            data.optJSONObject("primary_channel")?.optString("permanent_url").orEmpty(),
            data.optJSONObject("primary_channel")?.optString("short_url").orEmpty(),
            data.optJSONObject("channel")?.optString("canonical_url").orEmpty(),
            data.optJSONObject("channel")?.optString("permanent_url").orEmpty(),
            data.optJSONObject("channel")?.optString("short_url").orEmpty(),
        )
        val avatarFromMe = normalizeThumbnail(
            firstNonBlank(
                data.optString("picture"),
                data.optJSONObject("picture")?.optString("url").orEmpty(),
                data.optString("avatar_url"),
                data.optString("avatarUrl"),
                data.optString("thumbnail_url"),
                data.optString("thumbnailUrl"),
                data.optString("channel_thumbnail_url"),
                data.optString("channelThumbnailUrl"),
                data.optString("channel_avatar_url"),
                data.optString("channelAvatarUrl"),
                data.optString("default_channel_thumbnail_url"),
                data.optString("defaultChannelThumbnailUrl"),
                data.optString("primary_channel_thumbnail_url"),
                data.optString("primaryChannelThumbnailUrl"),
                data.optJSONObject("thumbnail")?.optString("url").orEmpty(),
                data.optJSONObject("claim")?.optJSONObject("value")?.optJSONObject("thumbnail")?.optString("url").orEmpty(),
                data.optJSONObject("default_channel")?.optString("thumbnail_url").orEmpty(),
                data.optJSONObject("primary_channel")?.optString("thumbnail_url").orEmpty(),
                data.optJSONObject("channel")?.optString("thumbnail_url").orEmpty(),
                data.optJSONObject("default_channel")?.optJSONObject("value")?.optJSONObject("thumbnail")?.optString("url").orEmpty(),
                data.optJSONObject("default_channel")?.optJSONObject("value")?.optJSONObject("cover")?.optString("url").orEmpty(),
                data.optJSONObject("primary_channel")?.optJSONObject("value")?.optJSONObject("thumbnail")?.optString("url").orEmpty(),
                data.optJSONObject("primary_channel")?.optJSONObject("value")?.optJSONObject("cover")?.optString("url").orEmpty(),
                data.optJSONObject("channel")?.optJSONObject("value")?.optJSONObject("thumbnail")?.optString("url").orEmpty(),
                data.optJSONObject("channel")?.optJSONObject("value")?.optJSONObject("cover")?.optString("url").orEmpty(),
                data.optJSONObject("primary_channel")?.optJSONObject("thumbnail")?.optString("url").orEmpty(),
                data.optJSONObject("default_channel")?.optJSONObject("thumbnail")?.optString("url").orEmpty(),
                data.optJSONObject("channel")?.optJSONObject("thumbnail")?.optString("url").orEmpty(),
            )
        )
        val avatarUrl = if (avatarFromMe.isNotBlank()) {
            avatarFromMe
        } else {
            resolveDefaultChannelAvatar(
                authToken = authToken,
                channelIdRaw = activeChannel,
                channelUriRaw = defaultChannelUri,
                channelNameRaw = defaultChannelName,
            )
        }

        return AuthUser(
            email = email,
            hasVerifiedEmail = hasVerifiedEmail,
            defaultChannelClaimId = activeChannel,
            defaultChannelName = defaultChannelName,
            defaultChannelUri = defaultChannelUri,
            avatarUrl = avatarUrl,
        )
    }

    suspend fun listMyChannels(): List<AuthChannel> {
        val authToken = ensureAnonymousAuth(false)
        if (authToken.isBlank()) {
            return emptyList()
        }
        val payload = runCatching {
            api.callSdk(
                method = "channel_list",
                params = JSONObject()
                    .put("page", 1)
                    .put("page_size", 50)
                    .put("resolve", true),
                authToken = authToken,
            )
        }.getOrNull() ?: return emptyList()

        val rows = when {
            payload.optJSONArray("items") != null -> payload.optJSONArray("items") ?: JSONArray()
            payload.optJSONArray("channels") != null -> payload.optJSONArray("channels") ?: JSONArray()
            else -> JSONArray()
        }

        val seen = mutableSetOf<String>()
        val channels = mutableListOf<AuthChannel>()
        for (i in 0 until rows.length()) {
            val channel = extractChannelContext(rows.optJSONObject(i)) ?: continue
            if (!seen.add(channel.channelId)) {
                continue
            }
            channels += channel
        }
        return channels
    }

    suspend fun setDefaultChannel(channelIdRaw: String) {
        val normalizedChannelId = normalizeClaimId(channelIdRaw)
        if (normalizedChannelId.isBlank()) {
            throw ApiException("Invalid channel")
        }

        val authToken = ensureAnonymousAuth(false)
        if (authToken.isBlank()) {
            throw ApiException("Unable to initialize auth token")
        }

        runCatching {
            setDefaultChannelForPreferenceKey(authToken = authToken, keyName = "shared", channelId = normalizedChannelId)
        }.getOrElse {
            setDefaultChannelForPreferenceKey(authToken = authToken, keyName = "local", channelId = normalizedChannelId)
        }
    }

    private fun persistSessionFromUserPayload(payload: JSONObject, fallbackAuthToken: String = "") {
        val data = pickBestUserObject(payload)
        val authToken = firstNonBlank(
            data.optString("auth_token"),
            payload.optString("auth_token"),
            fallbackAuthToken,
        )
        if (authToken.isBlank()) {
            return
        }

        val userId = firstNonBlank(
            anyToString(data.opt("id")),
            anyToString(payload.opt("id")),
            store.load()?.userId.orEmpty(),
        )
        store.save(
            AuthSession(
                authToken = authToken,
                userId = userId,
            )
        )
    }

    suspend fun signOut() {
        val authToken = store.load()?.authToken ?: return
        try {
            api.callRoot(
                resource = "user",
                action = "signout",
                params = mapOf("auth_token" to authToken),
                method = "POST",
            )
        } finally {
            store.clear()
            ensureAnonymousAuth(forceRefresh = true)
        }
    }

    suspend fun getAuthToken(): String = ensureAnonymousAuth(false)

    suspend fun getUserId(): String {
        ensureAnonymousAuth(false)
        return store.load()?.userId.orEmpty()
    }

    private suspend fun sendMagicLinkEmail(authToken: String, email: String) {
        val resendParams = mapOf(
            "auth_token" to authToken,
            "email" to email,
            "only_if_expired" to "true",
        )

        try {
            api.callRoot(
                resource = "user_email",
                action = "resend_token",
                params = resendParams,
                method = "POST",
            )
        } catch (e: Exception) {
            // Mimic web/tizen fallback: try /user/signin without password, then resend.
            runCatching {
                api.callRoot(
                    resource = "user",
                    action = "signin",
                    params = mapOf(
                        "auth_token" to authToken,
                        "email" to email,
                    ),
                    method = "POST",
                )
            }

            api.callRoot(
                resource = "user_email",
                action = "resend_token",
                params = resendParams,
                method = "POST",
            )
        }
    }

    private fun parseExists(payload: Any?): Boolean {
        return when (payload) {
            is Boolean -> payload
            is JSONObject -> {
                when {
                    payload.has("exists") -> payload.optBoolean("exists", false)
                    payload.has("is_valid") -> payload.optBoolean("is_valid", false)
                    payload.has("has_password") -> true
                    payload.has("primary_email") || payload.has("email") -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun pickBestUserObject(payload: JSONObject): JSONObject {
        val nestedData = payload.optJSONObject("data")
        val nestedResult = payload.optJSONObject("result")
        val candidates = listOfNotNull(
            payload,
            payload.optJSONObject("user"),
            nestedData,
            nestedData?.optJSONObject("user"),
            nestedResult,
            nestedResult?.optJSONObject("user"),
            payload.optJSONObject("me"),
        )

        var best = payload
        var bestScore = Int.MIN_VALUE
        candidates.forEachIndexed { index, candidate ->
            val score = userObjectScore(candidate) + if (index > 0) 1 else 0
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun anyToString(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> ""
            else -> value.toString()
        }
    }

    private fun userObjectScore(candidate: JSONObject): Int {
        var score = 0

        val email = firstNonBlank(
            candidate.optString("primary_email"),
            candidate.optString("email"),
            candidate.optString("user_email"),
        )
        if (email.isNotBlank()) {
            score += 4
        }

        if (
            candidate.has("has_verified_email") ||
            candidate.has("hasVerifiedEmail") ||
            candidate.has("is_authenticated") ||
            candidate.has("authenticated")
        ) {
            score += 3
        }

        if (
            candidate.has("default_channel") ||
            candidate.has("primary_channel") ||
            candidate.has("channel")
        ) {
            score += 3
        }

        if (
            hasAnyNonBlank(
                candidate.optString("default_channel_claim_id"),
                candidate.optString("primary_channel_claim_id"),
                candidate.optString("channel_claim_id"),
            )
        ) {
            score += 2
        }

        if (
            hasAnyNonBlank(
                candidate.optString("picture"),
                candidate.optString("avatar_url"),
                candidate.optString("thumbnail_url"),
                candidate.optString("channel_avatar_url"),
            ) ||
            candidate.optJSONObject("thumbnail") != null ||
            candidate.optJSONObject("picture") != null
        ) {
            score += 3
        }

        return score
    }

    private fun hasAnyNonBlank(vararg values: String): Boolean =
        values.any { it.isNotBlank() }

    private suspend fun resolveDefaultChannelAvatar(
        authToken: String,
        channelIdRaw: String,
        channelUriRaw: String,
        channelNameRaw: String,
    ): String {
        val channelId = normalizeClaimId(channelIdRaw)
        val channelUri = channelUriRaw.trim()
        val channelName = channelNameRaw.trim()

        if (channelId.isBlank() && channelUri.isBlank() && channelName.isBlank()) {
            return ""
        }

        if (channelId.isNotBlank()) {
            val byId = runCatching {
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
            }.getOrNull()
            val claim = byId?.optJSONArray("items")?.optJSONObject(0)
            val avatar = extractAvatarFromClaim(claim)
            if (avatar.isNotBlank()) {
                return avatar
            }
        }

        var uri = channelUri
        if (uri.isBlank() && channelName.isNotBlank()) {
            val normalizedName = if (channelName.startsWith("@")) channelName else "@$channelName"
            uri = "lbry://$normalizedName"
        }
        if (uri.isBlank()) {
            return ""
        }

        val byUri = runCatching {
            api.callSdk(
                method = "get",
                params = JSONObject().put("uri", uri),
                authToken = authToken,
            )
        }.getOrNull()

        return extractAvatarFromClaim(byUri)
    }

    private fun extractAvatarFromClaim(claim: JSONObject?): String {
        if (claim == null) {
            return ""
        }
        return normalizeThumbnail(
            firstNonBlank(
                claim.optJSONObject("value")?.optJSONObject("thumbnail")?.optString("url").orEmpty(),
                claim.optJSONObject("value")?.optJSONObject("cover")?.optString("url").orEmpty(),
                claim.optString("thumbnail_url"),
                claim.optString("thumbnailUrl"),
                claim.optJSONObject("thumbnail")?.optString("url").orEmpty(),
                claim.optString("cover_url"),
                claim.optString("coverUrl"),
            )
        )
    }

    private suspend fun setDefaultChannelForPreferenceKey(
        authToken: String,
        keyName: String,
        channelId: String,
    ) {
        val current = runCatching {
            api.callSdk(
                method = "preference_get",
                params = JSONObject().put("key", keyName),
                authToken = authToken,
            )
        }.getOrElse {
            JSONObject()
        }

        val envelope = parsePreferenceEnvelope(current, keyName).apply {
            val value = optJSONObject("value") ?: JSONObject().also { put("value", it) }
            val settings = value.optJSONObject("settings") ?: JSONObject().also { value.put("settings", it) }
            settings.put("active_channel_claim", channelId)
            if (!has("type")) put("type", "object")
            if (!has("version")) put("version", "0.1")
        }

        api.callSdk(
            method = "preference_set",
            params = JSONObject()
                .put("key", keyName)
                .put("value", envelope.toString()),
            authToken = authToken,
        )
    }

    private fun parsePreferenceEnvelope(payload: JSONObject, keyName: String): JSONObject {
        val keyed = payload.opt(keyName)
        val base = when (keyed) {
            null, JSONObject.NULL -> payload
            is JSONObject -> keyed
            is String -> parseJsonObject(keyed) ?: JSONObject()
            else -> JSONObject()
        }

        val envelope = JSONObject(base.toString())
        val rawValue = envelope.opt("value")
        val parsedValue = when (rawValue) {
            is JSONObject -> rawValue
            is String -> parseJsonObject(rawValue) ?: JSONObject()
            else -> JSONObject()
        }
        envelope.put("value", parsedValue)
        return envelope
    }

    private fun parseJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) {
            return null
        }
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private fun extractChannelContext(row: JSONObject?): AuthChannel? {
        if (row == null) {
            return null
        }
        val channelId = normalizeClaimId(
            firstNonBlank(
                row.optString("claim_id"),
                row.optString("claimId"),
                row.optString("channel_id"),
                row.optString("channelId"),
                row.optString("id"),
            )
        )
        if (channelId.isBlank()) {
            return null
        }

        val value = row.optJSONObject("value")
        val channelName = firstNonBlank(
            value?.optString("title").orEmpty(),
            row.optString("title"),
            row.optString("name"),
            row.optString("normalized_name"),
        ).ifBlank { "Channel" }

        val channelCanonicalUrl = firstNonBlank(
            row.optString("canonical_url"),
            row.optString("permanent_url"),
            row.optString("short_url"),
            row.optString("url"),
            row.optString("uri"),
        )

        val channelAvatarUrl = firstNonBlank(
            value?.optJSONObject("thumbnail")?.optString("url").orEmpty(),
            value?.optJSONObject("cover")?.optString("url").orEmpty(),
            row.optString("thumbnail_url"),
            row.optString("avatar_url"),
            row.optString("cover_url"),
        )

        val handle = firstNonBlank(
            row.optString("normalized_name"),
            row.optString("name"),
        )

        val videoCount = listOf(
            row.opt("claims_in_channel"),
            row.opt("claimsInChannel"),
            row.opt("claims_count"),
            row.opt("claimsCount"),
            row.opt("items_count"),
            row.opt("itemsCount"),
        ).firstNotNullOfOrNull { candidate ->
            when (candidate) {
                is Number -> candidate.toInt().takeIf { it >= 0 }
                is String -> candidate.toIntOrNull()?.takeIf { it >= 0 }
                else -> null
            }
        }

        return AuthChannel(
            channelId = channelId,
            channelName = channelName,
            channelAvatarUrl = normalizeThumbnail(channelAvatarUrl),
            channelCanonicalUrl = channelCanonicalUrl,
            channelHandle = handle,
            channelVideoCount = videoCount,
        )
    }

    private fun normalizeClaimId(value: String): String {
        val candidate = value.trim().lowercase()
        if (candidate.isBlank()) {
            return ""
        }
        val match = CLAIM_ID_REGEX.find(candidate) ?: return ""
        return match.value
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

    private fun toBool(vararg values: Any?): Boolean {
        values.forEach { value ->
            when (value) {
                null, JSONObject.NULL -> Unit
                is Boolean -> return value
                is Number -> return value.toInt() != 0
                is String -> {
                    val normalized = value.trim().lowercase()
                    when (normalized) {
                        "true", "1", "yes" -> return true
                        "false", "0", "no", "" -> return false
                        else -> return normalized.isNotEmpty()
                    }
                }
                else -> return true
            }
        }
        return false
    }

    private companion object {
        const val IMAGE_PROCESSOR_BASE = "https://thumbnails.odycdn.com/optimize/s:390:220/quality:85/plain/"
        val CLAIM_ID_REGEX = Regex("[0-9a-f]{40}")
    }
}
