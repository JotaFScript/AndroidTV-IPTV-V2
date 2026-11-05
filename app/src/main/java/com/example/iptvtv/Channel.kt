package com.example.iptvtv

data class Channel(
    val name: String,
    val url: String,
    val group: String? = null,
    val logo: String? = null
)
