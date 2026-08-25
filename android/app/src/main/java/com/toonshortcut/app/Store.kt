package com.toonshortcut.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 다음 회차 확인 결과.
 * 실패와 "회차 번호가 없는 주소"를 나눈다. 전자는 다시 시도하면 되지만
 * 후자는 주소를 고쳐야 하므로 사용자가 할 일이 다르다.
 */
enum class NextStatus { UNKNOWN, YES, NO, FAILED, NO_EPISODE }

data class Comic(
    val id: String,
    var title: String,
    var path: String,
    var next: NextStatus = NextStatus.UNKNOWN,
    /** 확인이 실패했을 때의 이유. 원인을 짐작하지 않아도 되도록 그대로 남긴다. */
    var nextNote: String? = null,
)

/**
 * 폰 안에만 저장한다. 서버도 DB도 쓰지 않는다.
 * 목록이 크지 않아 SharedPreferences + JSON 으로 충분하다.
 */
class Store(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("toon", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DOMAIN = "domain"
        private const val KEY_COMICS = "comics"
        private const val KEY_AUTO_SAVE = "auto_save_episode"
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
                    val next = runCatching {
                        NextStatus.valueOf(o.optString("next", NextStatus.UNKNOWN.name))
                    }.getOrDefault(NextStatus.UNKNOWN)
                    val note = o.optString("nextNote", "").ifEmpty { null }
                    out.add(Comic(id, o.optString("title", "제목 없음"), path, next, note))
                }
                out
            } catch (e: Exception) {
                mutableListOf()
            }
        }
        set(value) {
            val arr = JSONArray()
            for (c in value) {
                arr.put(
                    JSONObject().put("id", c.id).put("title", c.title).put("path", c.path)
                        .put("next", c.next.name)
                        .put("nextNote", c.nextNote ?: ""),
                )
            }
            prefs.edit().putString(KEY_COMICS, arr.toString()).apply()
        }

    /** 회차 자동 저장 사용 여부. 끄면 상단 [저장] 버튼으로만 저장된다. */
    var autoSaveEpisode: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SAVE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SAVE, value).apply()

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
        // 회차가 바뀌면 "다음 화가 있나"에 대한 이전 판정은 더 이상 맞지 않는다.
        if (path != null && path != c.path) {
            c.path = path
            c.next = NextStatus.UNKNOWN
            c.nextNote = null
        }
        comics = list
    }

    fun setNext(id: String, next: NextStatus, note: String? = null) {
        val list = comics
        val c = list.firstOrNull { it.id == id } ?: return
        if (c.next == next && c.nextNote == note) return
        c.next = next
        c.nextNote = note
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
