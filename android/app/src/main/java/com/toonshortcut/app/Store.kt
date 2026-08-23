package com.toonshortcut.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Comic(val id: String, var title: String, var path: String)

/**
 * 폰 안에만 저장한다. 서버도 DB도 쓰지 않는다.
 * 목록이 크지 않아 SharedPreferences + JSON 으로 충분하다.
 */
class Store(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("toon", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DOMAIN = "domain"
        private const val KEY_COMICS = "comics"
        private val DEFAULT_DOMAIN = SiteUrl.Domain(head = "", prefix = "tkor", suffix = "com", pad = 3, num = 146)
    }

    var domain: SiteUrl.Domain
        get() {
            val raw = prefs.getString(KEY_DOMAIN, null) ?: return DEFAULT_DOMAIN
            return try {
                val o = JSONObject(raw)
                SiteUrl.Domain(
                    head = o.optString("head", ""),
                    prefix = o.optString("prefix", "tkor"),
                    suffix = o.optString("suffix", "com"),
                    pad = o.optInt("pad", 3),
                    num = SiteUrl.clampNum(o.optInt("num", 146)),
                )
            } catch (e: Exception) {
                DEFAULT_DOMAIN
            }
        }
        set(value) {
            val o = JSONObject()
                .put("head", value.head)
                .put("prefix", value.prefix)
                .put("suffix", value.suffix)
                .put("pad", value.pad)
                .put("num", SiteUrl.clampNum(value.num))
            prefs.edit().putString(KEY_DOMAIN, o.toString()).apply()
        }

    var comics: MutableList<Comic>
        get() {
            val raw = prefs.getString(KEY_COMICS, null) ?: return mutableListOf()
            return try {
                val arr = JSONArray(raw)
                val out = mutableListOf<Comic>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id", "")
                    val path = o.optString("path", "")
                    if (id.isEmpty() || path.isEmpty()) continue
                    out.add(Comic(id, o.optString("title", "제목 없음"), path))
                }
                out
            } catch (e: Exception) {
                mutableListOf()
            }
        }
        set(value) {
            val arr = JSONArray()
            for (c in value) {
                arr.put(JSONObject().put("id", c.id).put("title", c.title).put("path", c.path))
            }
            prefs.edit().putString(KEY_COMICS, arr.toString()).apply()
        }

    fun bumpDomain(delta: Int): SiteUrl.Domain {
        val d = domain
        val next = d.copy(num = SiteUrl.clampNum(d.num + delta))
        domain = next
        return next
    }

    fun setDomainNum(num: Int): SiteUrl.Domain {
        val next = domain.copy(num = SiteUrl.clampNum(num))
        domain = next
        return next
    }

    fun addComic(title: String, path: String): Comic {
        val c = Comic(UUID.randomUUID().toString(), title, path)
        comics = comics.also { it.add(c) }
        return c
    }

    fun updateComic(id: String, title: String? = null, path: String? = null) {
        val list = comics
        val c = list.firstOrNull { it.id == id } ?: return
        if (title != null) c.title = title
        if (path != null) c.path = path
        comics = list
    }

    fun deleteComic(id: String) {
        comics = comics.filter { it.id != id }.toMutableList()
    }

    fun move(id: String, delta: Int) {
        val list = comics
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = i + delta
        if (j < 0 || j >= list.size) return
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        comics = list
    }

    /** 다른 폰으로 옮기거나 배우자와 목록을 나눌 때 쓴다. */
    fun exportJson(): String {
        val d = domain
        return JSONObject()
            .put("version", 1)
            .put(
                "domain",
                JSONObject().put("head", d.head).put("prefix", d.prefix)
                    .put("suffix", d.suffix).put("pad", d.pad).put("num", d.num),
            )
            .put(
                "comics",
                JSONArray().apply {
                    for (c in comics) put(JSONObject().put("title", c.title).put("path", c.path))
                },
            )
            .toString(2)
    }

    /** 기존 목록에 덧붙인다. 같은 경로는 건너뛴다. 돌려주는 값은 추가된 개수. */
    fun importJson(text: String): Int {
        val o = JSONObject(text)
        o.optJSONObject("domain")?.let { d ->
            domain = SiteUrl.Domain(
                head = d.optString("head", ""),
                prefix = d.optString("prefix", "tkor"),
                suffix = d.optString("suffix", "com"),
                pad = d.optInt("pad", 3),
                num = SiteUrl.clampNum(d.optInt("num", 146)),
            )
        }
        val arr = o.optJSONArray("comics") ?: return 0
        val list = comics
        val seen = list.map { it.path }.toMutableSet()
        var added = 0
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val path = c.optString("path", "")
            if (path.isEmpty() || !seen.add(path)) continue
            list.add(Comic(UUID.randomUUID().toString(), c.optString("title", SiteUrl.guessTitle(path)), path))
            added++
        }
        comics = list
        return added
    }
}
