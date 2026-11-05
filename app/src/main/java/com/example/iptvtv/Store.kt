package com.example.iptvtv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(
    val id: String,
    var name: String,
    var source: String?,
    var channels: List<Channel> = emptyList()
)

data class Tab(val id: String, val title: String)

/** Guarda las listas (una por archivo) y los favoritos en el almacenamiento interno de la app. */
class Store(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    val playlists = ArrayList<Playlist>()
    val favorites = ArrayList<Channel>()
    private val favUrls = HashSet<String>()

    private fun plFile(id: String) = File(ctx.filesDir, "pl_$id.m3u")
    private fun favFile() = File(ctx.filesDir, "favorites.m3u")

    fun load() {
        playlists.clear(); favorites.clear(); favUrls.clear()
        val meta = prefs.getString("playlists", null)
        if (meta != null) {
            try {
                val arr = JSONArray(meta)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getString("id")
                    val f = plFile(id)
                    val ch = if (f.exists()) M3uParser.parse(f.readText()) else emptyList()
                    val src = o.optString("source", "").ifEmpty { null }
                    playlists.add(Playlist(id, o.getString("name"), src, ch))
                }
            } catch (e: Exception) { /* lista dañada: se ignora */ }
        } else {
            migrateV1()
        }
        val ff = favFile()
        if (ff.exists()) {
            try {
                favorites.addAll(M3uParser.parse(ff.readText()))
                favorites.forEach { favUrls.add(it.url) }
            } catch (e: Exception) { }
        }
    }

    /** La versión 1 guardaba una sola lista en last.m3u: se conserva como «Mi lista». */
    private fun migrateV1() {
        val old = File(ctx.filesDir, "last.m3u")
        if (!old.exists()) return
        try {
            val ch = M3uParser.parse(old.readText())
            if (ch.isNotEmpty()) {
                val p = Playlist(newId(), "Mi lista", null, ch)
                playlists.add(p)
                writePlaylist(p.id, p.channels)
                saveMeta()
            }
            old.delete()
        } catch (e: Exception) { }
    }

    fun newId(): String = System.currentTimeMillis().toString(36) + playlists.size

    fun saveMeta() {
        val arr = JSONArray()
        for (p in playlists) {
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("source", p.source ?: ""))
        }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    fun writePlaylist(id: String, channels: List<Channel>) {
        plFile(id).writeText(M3uParser.toM3u(channels))
    }

    fun deleteFile(id: String) {
        plFile(id).delete()
    }

    fun isFav(url: String) = favUrls.contains(url)

    /** @return true si quedó como favorito, false si se quitó. */
    fun toggleFav(c: Channel): Boolean {
        val i = favorites.indexOfFirst { it.url == c.url }
        val added = i < 0
        if (added) { favorites.add(c); favUrls.add(c.url) }
        else { favorites.removeAt(i); favUrls.remove(c.url) }
        saveFavorites()
        return added
    }

    fun clearFavorites() {
        favorites.clear(); favUrls.clear(); saveFavorites()
    }

    private fun saveFavorites() {
        try { favFile().writeText(M3uParser.toM3u(favorites)) } catch (e: Exception) { }
    }
}
