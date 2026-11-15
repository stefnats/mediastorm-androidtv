package com.strmr.tv.api

import okhttp3.OkHttpClient

/** An uncertain creation response must not replay the mutation or follow a redirect. */
internal fun OkHttpClient.withoutAutomaticReplay(): OkHttpClient = newBuilder()
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
