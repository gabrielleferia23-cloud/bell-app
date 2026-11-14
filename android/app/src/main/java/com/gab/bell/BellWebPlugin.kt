package com.gab.bell

import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Lets Bell look things up on the internet, but only when the user turns it on
 * and asks her to. This runs from the app itself (not the web page), so it isn't
 * blocked by the browser's cross-site rules. It uses free sources with no key.
 */
@CapacitorPlugin(name = "BellWeb")
class BellWebPlugin : Plugin() {
    private val pool = Executors.newFixedThreadPool(3)
    private val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    private fun open(urlStr: String): HttpURLConnection {
        var link = urlStr
        var redirects = 0
        while (true) {
            val c = URL(link).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "en")
            val code = c.responseCode
            if (code in 300..399 && redirects < 5) {
                link = URL(URL(link), c.getHeaderField("Location")).toString()
                c.disconnect(); redirects++; continue
            }
            return c
        }
    }

    private fun body(c: HttpURLConnection, cap: Int): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream ?: return ""
        val sb = StringBuilder()
        stream.bufferedReader().use { r ->
            val buf = CharArray(8192)
            while (sb.length < cap) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
        }
        return sb.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
    private fun stripTags(html: String): String {
        var t = html.replace(Regex("(?is)<script.*?</script>"), " ").replace(Regex("(?is)<style.*?</style>"), " ")
        t = t.replace(Regex("(?is)<(head|nav|footer|header|form|aside).*?</\\1>"), " ")
        t = t.replace(Regex("(?is)<br\\s*/?>"), "\n").replace(Regex("(?is)</(p|div|li|h[1-6]|tr)>"), "\n")
        t = t.replace(Regex("(?s)<[^>]+>"), " ")
        return unescape(t).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n").trim()
    }

    /** Searches the web and returns a short answer plus the top results. */
    @PluginMethod
    fun search(call: PluginCall) {
        val query = call.getString("query") ?: return call.reject("No query")
        pool.execute {
            try {
                val ret = JSObject()
                val results = JSArray()
                val seen = HashSet<String>()

                // 1) DuckDuckGo instant answer (facts, no key)
                try {
                    val c = open("https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1")
                    val j = JSONObject(body(c, 200000))
                    val abs = j.optString("AbstractText", "")
                    if (abs.isNotBlank()) { ret.put("abstract", abs); ret.put("abstractSource", j.optString("AbstractSource", "")); ret.put("abstractUrl", j.optString("AbstractURL", "")) }
                } catch (_: Exception) {}

                // 2) Wikipedia summary (reliable free facts) + a clickable result
                try {
                    val s = open("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(query)}&format=json&srlimit=1")
                    val title = JSONObject(body(s, 100000)).optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title")
                    if (!title.isNullOrBlank()) {
                        val w = open("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(title).replace("+", "%20")}")
                        val wj = JSONObject(body(w, 100000))
                        val extract = wj.optString("extract", "")
                        val page = wj.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page") ?: "https://en.wikipedia.org/wiki/${enc(title).replace("+", "%20")}"
                        if (extract.isNotBlank()) {
                            if (!ret.has("abstract")) { ret.put("abstract", extract); ret.put("abstractSource", "Wikipedia"); ret.put("abstractUrl", page) }
                            if (seen.add(page)) { val r = JSObject(); r.put("title", "$title — Wikipedia"); r.put("url", page); r.put("snippet", extract.take(220)); results.put(r) }
                        }
                    }
                } catch (_: Exception) {}

                // 3) DuckDuckGo HTML results (general web links, no key)
                try {
                    val c = open("https://html.duckduckgo.com/html/?q=${enc(query)}")
                    val html = body(c, 400000)
                    val re = Regex("(?is)<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                    val snipRe = Regex("(?is)<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>")
                    val snips = snipRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
                    var i = 0
                    for (m in re.findAll(html)) {
                        var href = m.groupValues[1]
                        if (href.startsWith("//")) href = "https:$href"
                        val ud = Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)
                        if (ud != null) href = URLDecoder.decode(ud, "UTF-8")
                        if (!href.startsWith("http") || !seen.add(href)) continue
                        val title = stripTags(m.groupValues[2])
                        if (title.isBlank()) continue
                        val r = JSObject(); r.put("title", title); r.put("url", href); r.put("snippet", snips.getOrElse(i) { "" })
                        results.put(r)
                        if (++i >= 6) break
                    }
                } catch (_: Exception) {}

                // 4) if the general search gave nothing, try DuckDuckGo Lite as a backup
                if (results.length() == 0) try {
                    val c = open("https://lite.duckduckgo.com/lite/?q=${enc(query)}")
                    val html = body(c, 300000)
                    val re = Regex("(?is)<a[^>]*class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                    var i = 0
                    for (m in re.findAll(html)) {
                        var href = m.groupValues[1]
                        if (href.startsWith("//")) href = "https:$href"
                        val ud = Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)
                        if (ud != null) href = URLDecoder.decode(ud, "UTF-8")
                        if (!href.startsWith("http") || !seen.add(href)) continue
                        val title = stripTags(m.groupValues[2]); if (title.isBlank()) continue
                        val r = JSObject(); r.put("title", title); r.put("url", href); r.put("snippet", "")
                        results.put(r)
                        if (++i >= 6) break
                    }
                } catch (_: Exception) {}

                ret.put("results", results)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject(e.message ?: "search_failed")
            }
        }
    }

    /** Reads the readable text of one web page. */
    @PluginMethod
    fun fetchText(call: PluginCall) {
        val url = call.getString("url") ?: return call.reject("No url")
        pool.execute {
            try {
                val c = open(url)
                val ct = c.contentType ?: ""
                if (!ct.contains("html") && !ct.contains("text") && ct.isNotBlank()) return@execute call.reject("not_a_page")
                val text = stripTags(body(c, 600000))
                call.resolve(JSObject().put("text", text.take(20000)))
            } catch (e: Exception) {
                call.reject(e.message ?: "fetch_failed")
            }
        }
    }
}
