package com.example.streamly

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

enum class Kind { LIVE, MOVIE, SERIES }
data class Item(val key: String, val title: String, val logo: String?, val url: String?,
                val group: String, val kind: Kind, val seriesId: String? = null)
data class Episode(val title: String, val url: String)
data class Play(val key: String, val url: String, val live: Boolean)

/** Login, favorites and resume positions, stored on-device. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("streamly", 0)
    fun get(k: String): String? = sp.getString(k, null)
    fun put(vararg kv: Pair<String, String?>) { sp.edit().apply { kv.forEach { putString(it.first, it.second) } }.apply() }
    fun clearLogin() { sp.edit().remove("mode").apply() }
    fun favs(): Set<String> = sp.getStringSet("favs", emptySet())!!.toSet()
    fun toggleFav(k: String) {
        val s = favs().toMutableSet(); if (!s.add(k)) s.remove(k)
        sp.edit().putStringSet("favs", s).apply()
    }
    fun pos(k: String) = sp.getLong("p_$k", 0L)
    fun setPos(k: String, v: Long) { sp.edit().putLong("p_$k", v).putLong("t_$k", System.currentTimeMillis()).apply() }
    fun recent(): List<String> = sp.all.keys.filter { it.startsWith("t_") }
        .sortedByDescending { sp.all[it] as Long }.map { it.removePrefix("t_") }.filter { pos(it) > 0 }
}

object Repo {
    private val http = OkHttpClient()
    fun get(url: String): String = http.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) error("Server returned HTTP ${it.code}")
        it.body!!.string()
    }

    fun parseM3u(text: String): List<Item> {
        val attr = Regex("""([\w-]+)="([^"]*)"""")
        val out = mutableListOf<Item>()
        var logo: String? = null; var group = "Other"; var title = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                val a = attr.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                logo = a["tvg-logo"]?.ifBlank { null }; group = a["group-title"]?.ifBlank { null } ?: "Other"
                title = line.substringAfterLast(",").trim()
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val kind = when { "/movie/" in line -> Kind.MOVIE; "/series/" in line -> Kind.SERIES; else -> Kind.LIVE }
                out += Item(line, title.ifBlank { line }, logo, line, group, kind)
                title = ""; logo = null
            }
        }
        return out
    }

    fun xtream(server: String, u: String, p: String): List<Item> {
        val base = server.trim().trimEnd('/')
        val api = "$base/player_api.php?username=$u&password=$p&action="
        val out = mutableListOf<Item>()
        fun load(action: String, catAction: String, kind: Kind) {
            val cats = JSONArray(get(api + catAction)).let { a ->
                (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.optString("category_id") to o.optString("category_name") } }
            }
            val arr = JSONArray(get(api + action))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val g = cats[o.optString("category_id")] ?: "Other"
                when (kind) {
                    Kind.LIVE -> { val id = o.optString("stream_id")
                        out += Item("l$id", o.optString("name"), o.optString("stream_icon").ifBlank { null }, "$base/live/$u/$p/$id.m3u8", g, kind) }
                    Kind.MOVIE -> { val id = o.optString("stream_id"); val ext = o.optString("container_extension").ifBlank { "mp4" }
                        out += Item("m$id", o.optString("name"), o.optString("stream_icon").ifBlank { null }, "$base/movie/$u/$p/$id.$ext", g, kind) }
                    Kind.SERIES -> { val id = o.optString("series_id")
                        out += Item("s$id", o.optString("name"), o.optString("cover").ifBlank { null }, null, g, kind, id) }
                }
            }
        }
        load("get_live_streams", "get_live_categories", Kind.LIVE)
        load("get_vod_streams", "get_vod_categories", Kind.MOVIE)
        load("get_series", "get_series_categories", Kind.SERIES)
        return out
    }

    fun episodes(server: String, u: String, p: String, seriesId: String): List<Episode> {
        val base = server.trim().trimEnd('/')
        val info = JSONObject(get("$base/player_api.php?username=$u&password=$p&action=get_series_info&series_id=$seriesId"))
        val seasons = info.optJSONObject("episodes") ?: return emptyList()
        val out = mutableListOf<Episode>()
        for (s in seasons.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }) {
            val arr = seasons.getJSONArray(s)
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val ext = e.optString("container_extension").ifBlank { "mp4" }
                out += Episode("S$s E${e.optString("episode_num")} · ${e.optString("title")}", "$base/series/$u/$p/${e.optString("id")}.$ext")
            }
        }
        return out
    }
}
