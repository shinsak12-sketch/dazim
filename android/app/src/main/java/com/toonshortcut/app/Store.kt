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
    /**
     * 이 작품의 목록 페이지 주소. 비워두면 회차 주소에서 추측한다.
     * 추측이 빗나가는 작품만 직접 채워 넣으면 된다.
     */
    var listPath: String? = null,
    /** 목록 페이지에서 읽은 최신 회차. 몇 화 밀렸는지 보여주는 데 쓴다. */
    var latestEp: Int? = null,
    /**
     * 최신 회차의 실제 주소. 사이트가 준 것을 그대로 담는다.
     * 도중에 표기가 바뀌는 작품이 있어("074화" -> "EP.075_부제") 번호만
     * 갈아끼워서는 주소를 만들 수 없다.
     */
    var latestPath: String? = null,
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
        private const val KEY_COMICS_2 = "comics2"

        /**
         * 지금 보고 있는 목록 (1 또는 2).
         *
         * 일부러 저장하지 않는다. 앱을 다시 켜면 항상 1번으로 돌아와야
         * 숨긴 목록이 그대로 떠 있는 일이 없다. 화면끼리 값을 공유해야 하므로
         * 인스턴스가 아니라 여기에 둔다.
         */
        @Volatile
        var activeList: Int = 1

        fun toggleList(): Int {
            activeList = if (activeList == 1) 2 else 1
            return activeList
        }
    }

    private val comicsKey: String
        get() = if (activeList == 1) KEY_COMICS else KEY_COMICS_2

    private object Keys {
        const val AUTO_SAVE = "auto_save_episode"
    }

    private val defaultDomain =
        SiteUrl.Domain(head = "", prefix = "tkor", suffix = "com", pad = 3, num = 146)

    var domain: SiteUrl.Domain
        get() {
            val raw = prefs.getString(KEY_DOMAIN, null) ?: return defaultDomain
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
                defaultDomain
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
            val raw = prefs.getString(comicsKey, null) ?: return mutableListOf()
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
                    val listPath = o.optString("listPath", "").ifEmpty { null }
                    val latestEp = if (o.has("latestEp") && !o.isNull("latestEp")) o.optInt("latestEp") else null
                    val latestPath = o.optString("latestPath", "").ifEmpty { null }
                    out.add(
                        Comic(id, o.optString("title", "제목 없음"), path, next, note, listPath, latestEp, latestPath),
                    )
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
                        .put("nextNote", c.nextNote ?: "")
                        .put("listPath", c.listPath ?: "")
                        .put("latestEp", c.latestEp ?: JSONObject.NULL)
                        .put("latestPath", c.latestPath ?: ""),
                )
            }
            prefs.edit().putString(comicsKey, arr.toString()).apply()
        }

    /** 회차 자동 저장 사용 여부. 끄면 상단 [저장] 버튼으로만 저장된다. */
    var autoSaveEpisode: Boolean
        get() = prefs.getBoolean(Keys.AUTO_SAVE, true)
        set(value) = prefs.edit().putBoolean(Keys.AUTO_SAVE, value).apply()

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

    fun updateComic(
        id: String,
        title: String? = null,
        path: String? = null,
        listPath: String? = null,
    ) {
        val list = comics
        val c = list.firstOrNull { it.id == id } ?: return
        if (title != null) c.title = title
        if (listPath != null) c.listPath = listPath.ifEmpty { null }

        if (path != null && path != c.path) {
            // 작품이 아예 바뀌었는지 본다. 회차 번호 앞부분이 그 작품을 가리킨다.
            val oldSeries = SiteUrl.parseEpisode(c.path)?.before
            val newSeries = SiteUrl.parseEpisode(path)?.before

            c.path = path
            // 회차가 바뀌면 "다음 화가 있나"에 대한 이전 판정은 더 이상 맞지 않는다.
            c.next = NextStatus.UNKNOWN
            c.nextNote = null
            c.latestEp = null
            c.latestPath = null

            // 시즌이 넘어갔으면 예전 제목과 목록 주소는 더 이상 맞지 않는다.
            // "사형집행관 1화"로 등록해두고 "사형집행관 시즌2"를 보면 옛것으로 남는다.
            // 제목을 직접 지정한 경우(title 인자)는 건드리지 않는다.
            if (oldSeries != null && newSeries != null && oldSeries != newSeries &&
                SiteUrl.relatedSeries(oldSeries, newSeries)
            ) {
                if (title == null) c.title = SiteUrl.guessTitle(path)
                // 목록 주소도 예전 작품 것이다. 비워두면 지금 주소에서 다시 뽑는다.
                if (listPath == null) c.listPath = null
            }
        }
        comics = list
    }

    fun setNext(
        id: String,
        next: NextStatus,
        note: String? = null,
        latestEp: Int? = null,
        latestPath: String? = null,
    ) {
        val list = comics
        val c = list.firstOrNull { it.id == id } ?: return
        if (c.next == next && c.nextNote == note && c.latestEp == latestEp && c.latestPath == latestPath) return
        c.next = next
        c.nextNote = note
        c.latestEp = latestEp
        c.latestPath = latestPath
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
                    for (c in comics) {
                        put(
                            JSONObject().put("title", c.title).put("path", c.path)
                                .put("listPath", c.listPath ?: ""),
                        )
                    }
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
            list.add(
                Comic(
                    id = UUID.randomUUID().toString(),
                    title = c.optString("title", SiteUrl.guessTitle(path)),
                    path = path,
                    listPath = c.optString("listPath", "").ifEmpty { null },
                ),
            )
            added++
        }
        comics = list
        return added
    }
}
