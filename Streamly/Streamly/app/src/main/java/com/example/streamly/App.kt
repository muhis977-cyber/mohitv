package com.example.streamly

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.*

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var play by remember { mutableStateOf<Play?>(null) }
    var loggedIn by remember { mutableStateOf(prefs.get("mode") != null) }

    fun load() {
        scope.launch {
            loading = true; error = null
            try {
                items = withContext(Dispatchers.IO) {
                    if (prefs.get("mode") == "m3u") Repo.parseM3u(Repo.get(prefs.get("m3u")!!))
                    else Repo.xtream(prefs.get("server")!!, prefs.get("user")!!, prefs.get("pass")!!)
                }
            } catch (e: Exception) { error = e.message ?: "Failed to load" }
            loading = false
        }
    }
    LaunchedEffect(loggedIn) { if (loggedIn) load() }
    BackHandler(play != null) { play = null }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            when {
                play != null -> Player(play!!, prefs)
                !loggedIn -> Login(prefs) { loggedIn = true }
                else -> Home(items, loading, error, prefs, { play = it }, { load() }) {
                    prefs.clearLogin(); items = emptyList(); loggedIn = false
                }
            }
        }
    }
}

@Composable
fun Login(prefs: Prefs, onDone: () -> Unit) {
    var xtream by remember { mutableStateOf(prefs.get("mode") != "m3u") }
    var m3u by remember { mutableStateOf(prefs.get("m3u") ?: "") }
    var server by remember { mutableStateOf(prefs.get("server") ?: "") }
    var user by remember { mutableStateOf(prefs.get("user") ?: "") }
    var pass by remember { mutableStateOf(prefs.get("pass") ?: "") }
    Column(Modifier.fillMaxSize().padding(32.dp).widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Text("Streamly", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (xtream) Button({ xtream = true }) { Text("Xtream Codes") } else OutlinedButton({ xtream = true }) { Text("Xtream Codes") }
            if (!xtream) Button({ xtream = false }) { Text("M3U URL") } else OutlinedButton({ xtream = false }) { Text("M3U URL") }
        }
        if (xtream) {
            OutlinedTextField(server, { server = it }, label = { Text("Server (http://host:port)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(m3u, { m3u = it }, label = { Text("Playlist URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Button({
            if (xtream) prefs.put("mode" to "xtream", "server" to server, "user" to user, "pass" to pass)
            else prefs.put("mode" to "m3u", "m3u" to m3u)
            onDone()
        }, enabled = if (xtream) server.isNotBlank() && user.isNotBlank() else m3u.isNotBlank()) { Text("Connect") }
    }
}

@Composable
fun Home(all: List<Item>, loading: Boolean, error: String?, prefs: Prefs,
         onPlay: (Play) -> Unit, reload: () -> Unit, logout: () -> Unit) {
    val tabs = listOf("Home", "Live TV", "Movies", "Series", "Favorites")
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var favs by remember { mutableStateOf(prefs.favs()) }
    var series by remember { mutableStateOf<Item?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>?>(null) }
    val scope = rememberCoroutineScope()

    fun open(i: Item) {
        if (i.kind == Kind.SERIES && i.url == null) {
            series = i; episodes = null
            scope.launch {
                episodes = try {
                    withContext(Dispatchers.IO) { Repo.episodes(prefs.get("server")!!, prefs.get("user")!!, prefs.get("pass")!!, i.seriesId!!) }
                } catch (e: Exception) { emptyList() }
            }
        } else onPlay(Play(i.key, i.url!!, i.kind == Kind.LIVE))
    }
    val toggle = { i: Item -> prefs.toggleFav(i.key); favs = prefs.favs() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEachIndexed { i, t ->
                TextButton({ tab = i }) { Text(t, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal, color = if (tab == i) Color.White else Color.Gray) }
            }
            Spacer(Modifier.weight(1f))
            TextButton(reload) { Text("Refresh") }
            TextButton(logout) { Text("Log out") }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (error != null) Text("Error: $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))

        val q = query.trim()
        val byTab = when (tab) { 1 -> all.filter { it.kind == Kind.LIVE }; 2 -> all.filter { it.kind == Kind.MOVIE }
            3 -> all.filter { it.kind == Kind.SERIES }; 4 -> all.filter { it.key in favs }; else -> all }
        val filtered = if (q.isEmpty()) byTab else byTab.filter { it.title.contains(q, true) }

        LazyColumn(Modifier.fillMaxSize()) {
            if (tab == 0 && q.isEmpty()) {
                val byKey = all.associateBy { it.key }
                val resume = prefs.recent().mapNotNull { byKey[it] }.take(20)
                val fav = all.filter { it.key in favs }.take(40)
                if (resume.isNotEmpty()) item { Shelf("Continue watching", resume, ::open, toggle) }
                if (fav.isNotEmpty()) item { Shelf("Favorites", fav, ::open, toggle) }
                item { Shelf("Live TV", all.filter { it.kind == Kind.LIVE }.take(40), ::open, toggle) }
                item { Shelf("Movies", all.filter { it.kind == Kind.MOVIE }.take(40), ::open, toggle) }
                item { Shelf("Series", all.filter { it.kind == Kind.SERIES }.take(40), ::open, toggle) }
            } else {
                filtered.groupBy { it.group }.forEach { (g, list) -> item(key = g) { Shelf(g, list.take(60), ::open, toggle) } }
            }
        }
    }

    if (series != null) AlertDialog(
        onDismissRequest = { series = null },
        confirmButton = { TextButton({ series = null }) { Text("Close") } },
        title = { Text(series!!.title) },
        text = {
            when {
                episodes == null -> CircularProgressIndicator()
                episodes!!.isEmpty() -> Text("No episodes found.")
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(episodes!!) { e -> TextButton({ series = null; onPlay(Play(e.url, e.url, false)) }) { Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                }
            }
        })
}

@Composable
fun Shelf(title: String, list: List<Item>, onOpen: (Item) -> Unit, onFav: (Item) -> Unit) {
    if (list.isEmpty()) return
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.key }) { Poster(it, { onOpen(it) }, { onFav(it) }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Poster(item: Item, onClick: () -> Unit, onLong: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(140.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(3.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(4.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF222222))) {
            AsyncImage(item.logo, item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Player(p: Play, prefs: Prefs) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(p.url))
            if (!p.live) seekTo(prefs.pos(p.key))
            prepare(); playWhenReady = true
        }
    }
    fun save() {
        if (p.live || player.duration <= 0) return
        prefs.setPos(p.key, if (player.currentPosition > player.duration - 30_000) 0 else player.currentPosition)
    }
    LaunchedEffect(Unit) { while (true) { delay(5000); save() } }
    DisposableEffect(Unit) { onDispose { save(); player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; keepScreenOn = true; isFocusable = true; requestFocus() } },
        modifier = Modifier.fillMaxSize()
    )
}
