package com.odysee.androidtv.feature.discover.model

data class Category(
    val id: String,
    val title: String,
    val channelIds: List<String> = emptyList(),
    val excludedChannelIds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val orderBy: List<String> = listOf("release_time"),
    val sortOrder: Int = 999,
)

data class VideoClaim(
    val claimId: String,
    val title: String,
    val canonicalUrl: String,
    val normalizedName: String = "",
    val channelName: String,
    val channelClaimId: String = "",
    val channelCanonicalUrl: String = "",
    val channelAvatarUrl: String = "",
    val thumbnailUrl: String,
    val durationSec: Int,
    val releaseTime: Long,
    val sourceMediaType: String = "",
    val sourceSdHash: String = "",
    val sourceHash: String = "",
    val txid: String = "",
    val nout: String = "",
    val outpoint: String = "",
)
