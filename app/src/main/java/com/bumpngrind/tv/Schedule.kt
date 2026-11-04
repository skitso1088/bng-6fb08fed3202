package com.bumpngrind.tv

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * "What's on" for each channel, built from public listings only (no streams):
 *  - tflix events  (https://tflix.su/api/public)            — matches, by channel page slug
 *  - epg.pw guide  (channels.json "epg": "epgpw:<id>")      — USA channels
 *  - epgshare01 AU (channels.json "epg": "au1:<channel id>") — Australian channels
 *  - i.mjh.nz       (channels.json "epg": "mjh:<channel id>") — Australian free-to-air (Brisbane)
 *  - i.mjh.nz       (channels.json "epg": "mjhnz:<channel id>") — New Zealand free-to-air
 *
 * Output: {"<channel index>": [{h: headline, d: detail, a: startMs, b: endMs, l: 1 if live, k: "e" event | "g" guide}]}
 */
class Schedule(private val channels: List<JSONObject>, private val prefs: SharedPreferences) {

    companion object {
        private const val TFLIX = "https://tflix.su/api/public"
        private const val EPGPW = "https://epg.pw/api/epg.json?channel_id="
        private const val AU1 = "https://epgshare01.online/epgshare01/epg_ripper_AU1.xml.gz"
        private const val MJH = "https://i.mjh.nz/au/Brisbane/epg.xml.gz"
        private const val MJH_NZ = "https://i.mjh.nz/nz/epg.xml.gz"
        private const val UA = "BumpNGrind/2.2 (Android TV)"
        private const val HOUR = 3_600_000L
        private val REFRESH = mapOf("tflix" to HOUR / 6, "epgpw" to HOUR / 2, "mjh" to 3 * HOUR, "mjhnz" to 3 * HOUR, "au1" to 3 * HOUR)
    }

    /** Refresh whichever sources are due (call off the main thread) and return the merged schedule. */
    fun refresh(onUpdate: (String) -> Unit = {}): String {
        val now = System.currentTimeMillis()
        for ((src, every) in REFRESH) {
            if (now - prefs.getLong("t_$src", 0) < every) continue
            try {
                val data = when (src) { "tflix" -> tflix(); "epgpw" -> epgPw(); "mjh" -> xmltv(MJH, "mjh"); "mjhnz" -> xmltv(MJH_NZ, "mjhnz"); else -> xmltv(AU1, "au1") }
                prefs.edit().putString("s_$src", data.toString()).putLong("t_$src", now).apply()
                onUpdate(merged()) // show each source as soon as it arrives
            } catch (_: Exception) { /* offline / source changed: keep last good copy */ }
        }
        return merged()
    }

    fun merged(): String {
        val now = System.currentTimeMillis()
        val all = HashMap<String, ArrayList<JSONObject>>()
        for (src in REFRESH.keys) {
            val o = try { JSONObject(prefs.getString("s_$src", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            for (k in o.keys()) {
                val arr = o.getJSONArray(k)
                for (i in 0 until arr.length()) all.getOrPut(k) { ArrayList() }.add(arr.getJSONObject(i))
            }
        }
        val out = JSONObject()
        for ((k, list) in all) {
            val keep = list.filter { it.getLong("b") > now }.sortedBy { it.getLong("a") }.take(6)
            if (keep.isNotEmpty()) out.put(k, JSONArray(keep))
        }
        return out.toString()
    }

    // ---- sources ------------------------------------------------------------------------------

    private fun get(url: String): java.io.InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", UA)
        return c.inputStream
    }

    private fun item(h: String, d: String, a: Long, b: Long, live: Boolean, kind: String) =
        JSONObject().put("h", h).put("d", d).put("a", a).put("b", b).put("l", if (live) 1 else 0).put("k", kind)

    private fun add(out: JSONObject, idx: Int, item: JSONObject) {
        val key = idx.toString()
        (out.optJSONArray(key) ?: JSONArray().also { out.put(key, it) }).put(item)
    }

    private val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun isoMs(s: String): Long = try { isoUtc.parse(s.take(19))?.time ?: 0 } catch (_: Exception) { 0 }

    /** tflix: sport + match title, matched by channel page slug, or by name as a fallback. */
    private fun tflix(): JSONObject {
        val body = get(TFLIX).bufferedReader().use { it.readText() }
        fun norm(n: String) = n.lowercase(Locale.US).replace(Regex("\\b(international|english)\\b"), "").replace(Regex("\\s+"), " ").trim()
        val bySlug = HashMap<String, Int>(); val byName = HashMap<String, Int>()
        channels.forEachIndexed { i, c ->
            bySlug[c.getString("page_url").substringAfterLast('/')] = i; byName[norm(c.getString("name"))] = i
        }
        val root = JSONObject(body)
        val slugOfId = HashMap<String, String>()
        root.optJSONArray("channels")?.let { for (i in 0 until it.length()) it.getJSONObject(i).run { slugOfId[optString("id")] = optString("slug") } }
        val out = JSONObject()
        val events = root.optJSONArray("events") ?: JSONArray()
        for (e in 0 until events.length()) {
            val ev = events.getJSONObject(e)
            val a = isoMs(ev.optString("starts_at")); var b = isoMs(ev.optString("ends_at"))
            if (a == 0L || ev.optString("status") == "finished") continue
            if (b <= a) b = a + 3 * HOUR
            val chs = ev.optJSONArray("channels") ?: continue
            val seen = HashSet<Int>()
            for (c in 0 until chs.length()) {
                val ch = chs.getJSONObject(c)
                val slug = ch.optString("slug").ifEmpty { slugOfId[ch.optString("id")].orEmpty() }
                val idx = bySlug[slug] ?: byName[norm(ch.optString("name"))] ?: continue
                if (seen.add(idx)) add(out, idx, item(ev.optString("sport"), ev.optString("title"), a, b, true, "e"))
            }
        }
        return out
    }

    private fun epgIds(prefix: String): Map<String, List<Int>> {
        val m = HashMap<String, ArrayList<Int>>()
        channels.forEachIndexed { i, c ->
            val epg = c.optString("epg")
            if (epg.startsWith("$prefix:")) m.getOrPut(epg.substringAfter(':')) { ArrayList() }.add(i)
        }
        return m
    }

    /** "Live: College Basketball" -> headline "College Basketball", live; "AFLW - Adelaide v Melbourne" -> "AFLW" + detail. */
    private fun guideItem(title: String, sub: String, a: Long, b: Long): JSONObject {
        var t = title.trim(); var live = false
        Regex("^live[:\\s-]+", RegexOption.IGNORE_CASE).find(t)?.let { live = true; t = t.substring(it.range.last + 1).trim() }
        var h = t; var d = sub.trim()
        val cut = Regex("\\s[-–:]\\s|:\\s").find(t)
        if (cut != null && cut.range.first in 2..30) {
            h = t.substring(0, cut.range.first).trim()
            val rest = t.substring(cut.range.last + 1).trim()
            d = if (d.isEmpty()) rest else "$rest · $d"
        }
        return item(h, d, a, b, live, "g")
    }

    /** epg.pw JSON per channel: entries have a start time only, so each ends when the next begins. */
    private fun epgPw(): JSONObject {
        val out = JSONObject()
        for ((id, idxs) in epgIds("epgpw")) {
            val list = try {
                JSONObject(get(EPGPW + id).bufferedReader().use { it.readText() }).optJSONArray("epg_list")
            } catch (_: Exception) { null } ?: continue
            for (i in 0 until list.length()) {
                val p = list.getJSONObject(i)
                val a = isoMs(p.optString("start_date").let { s -> toUtcIso(s) })
                val b = if (i + 1 < list.length()) isoMs(toUtcIso(list.getJSONObject(i + 1).optString("start_date"))) else a + HOUR
                if (a == 0L || b <= a) continue
                val it = guideItem(p.optString("title"), "", a, b)
                idxs.forEach { idx -> add(out, idx, JSONObject(it.toString())) }
            }
        }
        return out
    }

    /** "2026-11-03T00:30:00+00:00" (any offset) -> UTC "yyyy-MM-ddTHH:mm:ss". */
    private fun toUtcIso(s: String): String = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        isoUtc.format(f.parse(s)!!)
    } catch (_: Exception) { s }

    /** Gzipped XMLTV guide, streamed, keeping only our channels for the next 36 hours. */
    private fun xmltv(url: String, prefix: String): JSONObject {
        val ids = epgIds(prefix)
        val out = JSONObject()
        if (ids.isEmpty()) return out
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        fun ms(s: String): Long = try { fmt.parse(s)?.time ?: 0 } catch (_: Exception) { 0 }
        val now = System.currentTimeMillis(); val until = now + 36 * HOUR
        val xp = android.util.Xml.newPullParser()
        java.util.zip.GZIPInputStream(get(url)).use { input ->
            xp.setInput(input, "UTF-8")
            var idxs: List<Int>? = null; var a = 0L; var b = 0L
            var title = ""; var sub = ""; var field = ""
            while (true) {
                when (xp.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.START_TAG -> when (xp.name) {
                        "programme" -> {
                            idxs = ids[xp.getAttributeValue(null, "channel")]
                            a = ms(xp.getAttributeValue(null, "start") ?: ""); b = ms(xp.getAttributeValue(null, "stop") ?: "")
                            if (b < now || a > until) idxs = null
                            title = ""; sub = ""
                        }
                        "title", "sub-title" -> field = if (idxs != null) xp.name else ""
                    }
                    XmlPullParser.TEXT -> when (field) { "title" -> title += xp.text; "sub-title" -> sub += xp.text }
                    XmlPullParser.END_TAG -> when (xp.name) {
                        "title", "sub-title" -> field = ""
                        "programme" -> {
                            val list = idxs
                            if (list != null && a > 0 && b > a) {
                                val it = guideItem(title, sub, a, b)
                                list.forEach { idx -> add(out, idx, JSONObject(it.toString())) }
                            }
                            idxs = null
                        }
                    }
                }
            }
        }
        return out
    }
}
