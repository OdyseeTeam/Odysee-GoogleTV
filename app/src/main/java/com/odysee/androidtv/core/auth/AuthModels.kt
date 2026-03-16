package com.odysee.androidtv.core.auth

data class AuthSession(
    val authToken: String,
    val userId: String = "",
)

data class AuthUser(
    val email: String,
    val hasVerifiedEmail: Boolean,
    val defaultChannelClaimId: String = "",
    val defaultChannelName: String = "",
    val defaultChannelUri: String = "",
    val avatarUrl: String = "",
)

data class AuthChannel(
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String = "",
    val channelCanonicalUrl: String = "",
    val channelHandle: String = "",
    val channelVideoCount: Int? = null,
)
