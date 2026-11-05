package com.example.iptvtv

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        const val UA = "VLC/3.0.18 LibVLC/3.0.18"
        const val REQ_STORAGE = 1
        const val FAV = "fav"      // id de la pestaña de favoritos
        const val OWN = "own"      // id de la lista «Mis canales» (canales sueltos)
    }

    private lateinit var store: Store
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var listView: RecyclerView
    private lateinit var tabsView: RecyclerView
    private lateinit var info: TextView
    private lateinit var empty: TextView
    private lateinit var btnUrl: Button
    private lateinit var btnFile: Button
    private lateinit var btnOptions: Button
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var tabAdapter: TabAdapter

    private var loaded = false
    private var pendingUri: Uri? = null
    private var pendingPath: String? = null

    private var selectedTab = FAV            // pestaña que se está viendo
    private var shown: List<Channel> = emptyList()

    private var queue: List<Channel> = emptyList()   // lista sobre la que se cambia de canal (↑↓)
    private var queueTab = FAV
    private var current = -1
    private var playingUrl: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("iptv", MODE_PRIVATE) }
    private val hideInfo = Runnable { info.visibility = View.GONE }
    private val retry = Runnable { if (current >= 0) player.prepare() }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) { }
                readUri(uri)
            }
        }

    // ------------------------------------------------------------ Inicio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = Store(this)
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        listView = findViewById(R.id.list)
        tabsView = findViewById(R.id.tabsView)
        info = findViewById(R.id.info)
        empty = findViewById(R.id.empty)
        btnUrl = findViewById(R.id.btnUrl)
        btnFile = findViewById(R.id.btnFile)
        btnOptions = findViewById(R.id.btnOptions)

        channelAdapter = ChannelAdapter(
            isFav = { store.isFav(it.url) },
            onPlay = { pos -> playFromShown(pos) },
            onToggleFav = { pos -> toggleFavAt(pos) }
        )
        tabAdapter = TabAdapter { id -> selectTab(id) }

        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = channelAdapter
        listView.itemAnimator = null
        tabsView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        tabsView.adapter = tabAdapter
        tabsView.itemAnimator = null

        btnUrl.setOnClickListener { askUrl() }
        btnFile.setOnClickListener { openPicker() }
        btnOptions.setOnClickListener { showOptions() }

        initPlayer()

        // Las listas se leen fuera del hilo principal para no bloquear TVs lentos.
        io.execute {
            store.load()
            runOnUiThread {
                loaded = true
                val saved = prefs.getString("tab", null)
                selectedTab = when {
                    saved != null && tabExists(saved) -> saved
                    store.playlists.isNotEmpty() -> store.playlists[0].id
                    else -> FAV
                }
                refreshTabs()
                showTab()
                val data = pendingUri ?: intent?.data
                pendingUri = null
                if (data != null) handleUri(data) else restoreLast()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val d = intent?.data ?: return
        if (loaded) handleUri(d) else pendingUri = d
    }

    // ------------------------------------------------------------ Player

    private fun initPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        player = ExoPlayer.Builder(
            this,
            DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        )
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showInfo("Error: ${error.errorCodeName} (reintentando…)", 6000)
                handler.removeCallbacks(retry)
                handler.postDelayed(retry, 5000)
            }
        })
        playerView.player = player
    }

    private fun playIndex(i: Int) {
        if (i !in queue.indices) return
        current = i
        val ch = queue[i]
        playingUrl = ch.url
        handler.removeCallbacks(retry)
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare()
        player.playWhenReady = true
        channelAdapter.setPlaying(ch.url)
        prefs.edit().putString("qtab", queueTab).putString("lastUrl", ch.url).apply()
        showInfo("${i + 1}. ${ch.name}" + if (store.isFav(ch.url)) "  (favorito)" else "")
    }

    private fun change(delta: Int) {
        if (queue.isEmpty()) return
        playIndex((current + delta + queue.size) % queue.size)
    }

    private fun playFromShown(pos: Int) {
        if (pos !in shown.indices) return
        queue = shown
        queueTab = selectedTab
        playIndex(pos)
        hideOverlay()
    }

    private fun restoreLast() {
        val qt = prefs.getString("qtab", null)
        val url = prefs.getString("lastUrl", null)
        if (qt != null && url != null && tabExists(qt)) {
            val l = channelsOf(qt)
            val i = l.indexOfFirst { it.url == url }
            if (i >= 0) {
                queue = l; queueTab = qt
                playIndex(i)
                return
            }
        }
        showOverlay()
    }

    // ------------------------------------------------------------ Pestañas y listas

    private fun tabExists(id: String) = id == FAV || store.playlists.any { it.id == id }

    private fun channelsOf(id: String): List<Channel> =
        if (id == FAV) store.favorites.toList()
        else store.playlists.firstOrNull { it.id == id }?.channels ?: emptyList()

    private fun refreshTabs() {
        val t = ArrayList<Tab>()
        t.add(Tab(FAV, "Favoritos (${store.favorites.size})"))
        store.playlists.forEach { t.add(Tab(it.id, "${it.name} (${it.channels.size})")) }
        tabAdapter.submit(t, selectedTab)
    }

    private fun showTab() {
        shown = channelsOf(selectedTab)
        channelAdapter.submit(shown)
        updateEmpty()
    }

    private fun updateEmpty() {
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            selectedTab == FAV ->
                "Aún no tienes favoritos.\nPulsa el corazón o la estrella de un canal para añadirlo."
            store.playlists.isEmpty() ->
                "Pulsa «+ URL / ruta» o «+ Archivo» para añadir tu primera lista."
            else -> "Esta lista no tiene canales."
        }
    }

    private fun selectTab(id: String) {
        if (id == selectedTab) return
        selectedTab = id
        prefs.edit().putString("tab", id).apply()
        tabAdapter.setSelected(id)
        showTab()
    }

    // ------------------------------------------------------------ Favoritos

    private fun toggleFavAt(pos: Int) {
        val c = shown.getOrNull(pos) ?: return
        val added = store.toggleFav(c)
        refreshTabs()
        if (selectedTab == FAV && !added) {
            shown = shown.toMutableList().also { it.removeAt(pos) }
            channelAdapter.removeAt(pos)
            updateEmpty()
            val np = minOf(pos, shown.size - 1)
            if (np >= 0) {
                listView.post {
                    listView.findViewHolderForAdapterPosition(np)?.itemView
                        ?.findViewById<View>(R.id.btnHeart)?.requestFocus()
                }
            } else btnUrl.requestFocus()
        } else {
            channelAdapter.refresh(pos)
        }
    }

    private fun toggleCurrentFav() {
        val ch = queue.getOrNull(current) ?: return
        val added = store.toggleFav(ch)
        refreshTabs()
        showTab()
        showInfo(
            (if (added) "Añadido a favoritos: " else "Quitado de favoritos: ") + ch.name,
            2500
        )
    }

    // ------------------------------------------------------------ Entradas (URL, archivo, ruta)

    private fun askUrl() {
        val et = EditText(this).apply {
            hint = "https://servidor/lista.m3u  o  canal.m3u8  o  /storage/lista.m3u"
            setText("https://")
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Añadir lista o canal (URL o ruta)")
            .setView(et)
            .setPositiveButton("Añadir") { _, _ -> openInput(et.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openPicker() {
        try {
            pickFile.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            toast("Este equipo no tiene explorador de archivos. Usa «+ URL / ruta» y escribe la ruta (ej. /storage/xxxx/lista.m3u)")
        }
    }

    private fun handleUri(uri: Uri) {
        val s = uri.toString()
        if (s.startsWith("http://") || s.startsWith("https://")) openInput(s) else readUri(uri)
    }

    /** URL de lista, URL de canal directo o ruta local. */
    private fun openInput(raw: String) {
        val s = raw.trim()
        if (s.isEmpty() || s == "http://" || s == "https://") return
        when {
            s.startsWith("/") -> readPath(s)
            s.startsWith("file://") -> readPath(Uri.parse(s).path ?: return)
            s.startsWith("http://") || s.startsWith("https://") -> io.execute {
                val text = try { fetchPlaylistOrNull(s) } catch (e: Exception) { null }
                if (text != null) importText(text, s, nameFromSource(s))
                else runOnUiThread { addDirectChannel(s) }
            }
            !s.contains("://") -> openInput((if (s.first().isDigit()) "http://" else "https://") + s)
            else -> addDirectChannel(s) // rtmp://, udp://, rtsp://… (se intenta tal cual)
        }
    }

    private fun readUri(uri: Uri) = io.execute {
        try {
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            importText(text, uri.toString(), displayName(uri) ?: nameFromSource(uri.toString()))
        } catch (e: Exception) {
            toast("No se pudo leer el archivo")
        }
    }

    private fun readPath(path: String) {
        if (Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPath = path
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE
            )
            return
        }
        io.execute {
            try { importText(File(path).readText(), path, nameFromSource(path)) }
            catch (e: Exception) { toast("No se pudo leer: $path") }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val p = pendingPath
        pendingPath = null
        if (requestCode == REQ_STORAGE && p != null) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) readPath(p)
            else toast("Permiso de almacenamiento denegado")
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0)?.substringBeforeLast('.') else null }
    } catch (e: Exception) { null }

    private fun nameFromSource(s: String): String {
        val base = s.substringBefore('?').trimEnd('/').substringAfterLast('/')
            .substringAfterLast(':').substringBeforeLast('.')
        val host = if (s.startsWith("http")) Uri.parse(s).host?.substringBefore('.') else null
        return when {
            base.isBlank() -> host ?: "Lista ${store.playlists.size + 1}"
            base.length <= 3 && host != null -> "$host $base"
            else -> base
        }
    }

    /** Descarga el inicio de la URL: si es una lista M3U la devuelve; si es un stream devuelve null. */
    private fun fetchPlaylistOrNull(url0: String): String? {
        var url = url0
        for (hop in 0..5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", UA)
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.instanceFollowRedirects = false // se siguen a mano (también http -> https)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return null
                    url = URL(URL(url), loc).toString()
                    continue
                }
                val ins = BufferedInputStream(c.inputStream)
                val buf = ByteArray(4096)
                val n = ins.read(buf)
                if (n <= 0) return null
                val head = String(buf, 0, n, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
                if (!head.startsWith("#EXTM3U") || !head.contains("#EXTINF") || head.contains("#EXT-X-"))
                    return null
                val out = ByteArrayOutputStream()
                out.write(buf, 0, n)
                ins.copyTo(out)
                return out.toString("UTF-8")
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    /** Convierte el texto en canales y lo agrega como lista nueva (o actualiza la que tenga el mismo origen). */
    private fun importText(text: String, source: String, name: String) {
        val l = M3uParser.parse(text)
        runOnUiThread {
            if (l.isEmpty()) toast("No se encontraron canales en la lista")
            else addOrUpdatePlaylist(name, source, l)
        }
    }

    private fun addOrUpdatePlaylist(name: String, source: String?, channels: List<Channel>) {
        val existing = source?.let { s -> store.playlists.firstOrNull { it.source == s } }
        val p = existing ?: Playlist(store.newId(), name, source).also { store.playlists.add(it) }
        p.channels = channels
        store.saveMeta()
        val id = p.id
        io.execute { try { store.writePlaylist(id, channels) } catch (e: Exception) { } }

        selectedTab = id
        prefs.edit().putString("tab", id).apply()
        refreshTabs()
        showTab()
        toast("«${p.name}»: ${channels.size} canales")

        if (current < 0) { // si aún no se reproducía nada, arranca el primer canal
            queue = channels; queueTab = id
            playIndex(0)
        }
        if (overlay.visibility == View.VISIBLE) focusList()
    }

    private fun addDirectChannel(url: String) {
        var p = store.playlists.firstOrNull { it.id == OWN }
        if (p == null) {
            p = Playlist(OWN, "Mis canales", null)
            store.playlists.add(p)
        }
        if (p.channels.none { it.url == url }) {
            val host = try { Uri.parse(url).host } catch (e: Exception) { null }
            p.channels = p.channels + Channel("Canal ${p.channels.size + 1}" + (host?.let { " ($it)" } ?: ""), url)
            store.saveMeta()
            val ch = p.channels
            io.execute { try { store.writePlaylist(OWN, ch) } catch (e: Exception) { } }
        }
        selectedTab = OWN
        prefs.edit().putString("tab", OWN).apply()
        refreshTabs()
        showTab()
        queue = p.channels; queueTab = OWN
        playIndex(p.channels.indexOfFirst { it.url == url })
        hideOverlay()
    }

    // ------------------------------------------------------------ Opciones de lista

    private fun showOptions() {
        if (selectedTab == FAV) {
            AlertDialog.Builder(this)
                .setTitle("Favoritos")
                .setItems(arrayOf("Vaciar favoritos")) { _, _ ->
                    confirm("¿Quitar todos los canales de favoritos?") {
                        store.clearFavorites(); refreshTabs(); showTab()
                    }
                }
                .setNegativeButton("Cerrar", null)
                .show()
            return
        }
        val p = store.playlists.firstOrNull { it.id == selectedTab } ?: return
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (p.source != null) {
            labels.add("Actualizar desde su origen"); actions.add { refreshPlaylist(p) }
        }
        labels.add("Renombrar"); actions.add { renamePlaylist(p) }
        labels.add("Eliminar lista"); actions.add {
            confirm("¿Eliminar la lista «${p.name}»?") { deletePlaylist(p) }
        }
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun refreshPlaylist(p: Playlist) {
        val s = p.source ?: return
        when {
            s.startsWith("http://") || s.startsWith("https://") -> io.execute {
                val text = try { fetchPlaylistOrNull(s) } catch (e: Exception) { null }
                if (text != null) importText(text, s, p.name) else toast("No se pudo actualizar la lista")
            }
            s.startsWith("content://") -> readUri(Uri.parse(s))
            s.startsWith("/") -> readPath(s)
            else -> toast("Esta lista no se puede actualizar")
        }
    }

    private fun renamePlaylist(p: Playlist) {
        val et = EditText(this).apply {
            setText(p.name); setSelection(text.length); setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Renombrar lista")
            .setView(et)
            .setPositiveButton("Guardar") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) { p.name = n; store.saveMeta(); refreshTabs() }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deletePlaylist(p: Playlist) {
        store.playlists.remove(p)
        store.saveMeta()
        val id = p.id
        io.execute { store.deleteFile(id) }
        selectedTab = store.playlists.firstOrNull()?.id ?: FAV
        prefs.edit().putString("tab", selectedTab).apply()
        refreshTabs()
        showTab()
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Sí") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .show()
    }

    // ------------------------------------------------------------ UI

    private fun showInfo(text: String, ms: Long = 3000) {
        info.text = text
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, ms)
    }

    private fun showOverlay() {
        overlay.visibility = View.VISIBLE
        focusList()
    }

    /** Lleva el foco al canal en reproducción (si está en la lista visible) o al primero. */
    private fun focusList() {
        listView.post {
            val pos = channelAdapter.indexOfPlaying()
            if (pos >= 0) {
                (listView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 120)
            }
            listView.post {
                val v = listView.findViewHolderForAdapterPosition(maxOf(pos, 0))
                    ?.itemView?.findViewById<View>(R.id.main)
                (v ?: btnUrl).requestFocus()
            }
        }
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val visible = overlay.visibility == View.VISIBLE
        val k = e.keyCode

        if (visible) {
            if (e.action == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_MENU) {
                hideOverlay(); return true
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                if (e.action == KeyEvent.ACTION_UP) hideOverlay()
                return true
            }
            return super.dispatchKeyEvent(e)
        }

        if (e.action == KeyEvent.ACTION_DOWN) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_DPAD_LEFT -> { showOverlay(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { toggleCurrentFav(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    change(-1); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    change(+1); return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ------------------------------------------------------------ Ciclo de vida

    override fun onStart() {
        super.onStart()
        if (current >= 0) player.play()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player.release()
        io.shutdown()
    }
}
