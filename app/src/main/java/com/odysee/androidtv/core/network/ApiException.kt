package com.odysee.androidtv.core.network

class ApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
