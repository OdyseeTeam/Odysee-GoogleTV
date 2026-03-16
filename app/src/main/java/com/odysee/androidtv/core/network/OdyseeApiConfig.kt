package com.odysee.androidtv.core.network

data class OdyseeApiConfig(
    val rootApiBase: String = "https://api.odysee.com",
    val rootApiFallbackBase: String = "https://api.lbry.com",
    val queryApiBase: String = "https://api.na-backend.odysee.com",
    val videoApiBase: String = "https://player.odycdn.com",
    val legacyVideoApiBase: String = "https://cdn.lbryplayer.xyz",
    val requestTimeoutMs: Long = 30_000,
) {
    val queryProxyUrl: String
        get() = "$queryApiBase/api/v1/proxy"
}
