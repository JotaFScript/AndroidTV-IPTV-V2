package com.example.iptvtv

object M3uParser {

    private val attrRegex = Regex("([\\w-]+)=\"([^\"]*)\"")

    /** Interpreta una lista M3U/M3U8 (con o sin #EXTINF) y devuelve los canales. */
    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var group: String? = null
        var logo: String? = null

        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attrRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    group = attrs["group-title"]
                    logo = attrs["tvg-logo"]
                    name = line.substringAfterLast(',', "").trim()
                        .ifEmpty { attrs["tvg-name"] }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    group = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                line.contains("://") -> {
                    out.add(
                        Channel(
                            name?.takeIf { it.isNotBlank() } ?: "Canal ${out.size + 1}",
                            line, group, logo
                        )
                    )
                    name = null; group = null; logo = null
                }
            }
        }
        return out
    }

    fun toM3u(list: List<Channel>): String = buildString {
        append("#EXTM3U\n")
        for (c in list) {
            append("#EXTINF:-1")
            c.logo?.let { append(" tvg-logo=\"${it.replace('"', '\'')}\"") }
            c.group?.let { append(" group-title=\"${it.replace('"', '\'')}\"") }
            append(',').append(c.name.replace('\n', ' ')).append('\n')
            append(c.url).append('\n')
        }
    }
}
