package nl.arch.deltionrooster

import org.json.JSONObject
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Lesson(
    val start: Long,   // epoch millis
    val end: Long,     // epoch millis
    val title: String,
    val room: String,
    val teacher: String,
)

/**
 * Talks to the (unofficial, undocumented) JSON endpoints behind roosters.deltion.nl.
 *
 *  GET /api/group                                   -> { "data": ["SD4A", ...] }
 *  GET /api/roster?group=X&start=yyyyMMdd&end=yyyyMMdd
 *      -> { "data": [ { "date_f", "weeknum", "items": [ { "t", "v", "r", "l", "st", "et" } ] } ] }
 *
 * Field meanings as used by earlier student projects: t = time text, v = subject,
 * r = room, l = teacher, st/et = start/end in epoch millis.
 * The site wants a session cookie, which it hands out on the homepage.
 */
object DeltionApi {
    private const val BASE = "https://roosters.deltion.nl"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) DeltionRoosterApp/1.0"
    private const val SESSION_MAX_AGE = 30 * 60 * 1000L
    private const val NO_ANSWER =
        "roosters.deltion.nl didn't answer. If a VPN is on, turn it off and try again."

    val zone: ZoneId = ZoneId.of("Europe/Amsterdam")
    private val ymd: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    private var sessionAt = 0L

    init {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
    }

    @Synchronized
    private fun ensureSession(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - sessionAt < SESSION_MAX_AGE) return
        get("$BASE/") // response sets the cookie, CookieManager stores it
        sessionAt = now
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
            val code = try {
                c.responseCode
            } catch (e: java.net.UnknownHostException) {
                throw IOException("No internet, or roosters.deltion.nl can't be found")
            } catch (e: java.net.SocketTimeoutException) {
                throw IOException(NO_ANSWER)
            } catch (e: java.net.ConnectException) {
                throw IOException(NO_ANSWER)
            }
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("Deltion's server answered with error $code")
            return body
        } finally {
            c.disconnect()
        }
    }

    /** Gets JSON from the API; if it fails, gets a fresh cookie and tries once more. */
    private fun getJson(url: String): JSONObject {
        return try {
            ensureSession()
            JSONObject(get(url))
        } catch (first: Exception) {
            ensureSession(force = true)
            try {
                JSONObject(get(url))
            } catch (e: org.json.JSONException) {
                throw IOException("Deltion sent something that isn't schedule data")
            }
        }
    }

    fun fetchGroups(): List<String> {
        val arr = getJson("$BASE/api/group").optJSONArray("data")
            ?: throw IOException("Class list has an unexpected format")
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            val name = when (item) {
                is String -> item
                is JSONObject -> item.optString("name", item.optString("group", ""))
                else -> ""
            }.trim()
            if (name.isNotEmpty()) out += name
        }
        return out.distinct().sorted()
    }

    private fun rosterUrl(group: String, start: LocalDate, end: LocalDate) =
        "$BASE/api/roster?group=${URLEncoder.encode(group, "UTF-8")}" +
            "&start=${start.format(ymd)}&end=${end.format(ymd)}"

    fun fetchRoster(group: String, start: LocalDate, end: LocalDate): List<Lesson> =
        parseRoster(getJson(rosterUrl(group, start, end)))

    /** Raw response text, for checking what the API actually sends. */
    fun fetchRaw(group: String, start: LocalDate, end: LocalDate): String {
        ensureSession()
        return get(rosterUrl(group, start, end))
    }

    private fun parseRoster(root: JSONObject): List<Lesson> {
        val days = root.optJSONArray("data")
            ?: throw IOException("Schedule has an unexpected format (no \"data\" list)")
        val out = mutableListOf<Lesson>()
        var unreadable = 0
        for (i in 0 until days.length()) {
            val day = days.optJSONObject(i) ?: continue
            val items = day.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val times = lessonTimes(day, item)
                if (times == null) {
                    unreadable++
                    continue
                }
                out += Lesson(
                    start = times.first,
                    end = times.second,
                    title = item.optString("v").trim().ifEmpty { "Lesson" },
                    room = item.optString("r").trim(),
                    teacher = item.optString("l").trim(),
                )
            }
        }
        if (out.isEmpty() && unreadable > 0) {
            throw IOException("Found $unreadable lessons but couldn't read their times")
        }
        return out
    }

    /** Values below 10^11 are seconds, not millis. */
    private fun toMillis(v: Long) = if (v in 1 until 100_000_000_000L) v * 1000 else v

    private val timeRange = Regex("""(\d{1,2})[:.](\d{2})\D+(\d{1,2})[:.](\d{2})""")

    private fun lessonTimes(day: JSONObject, item: JSONObject): Pair<Long, Long>? {
        val st = item.optLong("st", 0L)
        val et = item.optLong("et", 0L)
        if (st > 0 && et > st) return toMillis(st) to toMillis(et)

        // Fallback: the day's date plus the "08:30 - 10:00" style time text.
        val date = dayDate(day) ?: return null
        val m = timeRange.find(item.optString("t")) ?: return null
        val (h1, m1, h2, m2) = m.destructured
        return runCatching {
            val s = date.atTime(h1.toInt(), m1.toInt()).atZone(zone).toInstant().toEpochMilli()
            val e = date.atTime(h2.toInt(), m2.toInt()).atZone(zone).toInstant().toEpochMilli()
            if (e > s) s to e else null
        }.getOrNull()
    }

    private fun dayDate(day: JSONObject): LocalDate? {
        val ts = day.optLong("date_ts", 0L)
        if (ts > 0) return Instant.ofEpochMilli(toMillis(ts)).atZone(zone).toLocalDate()
        val f = day.optString("date_f")
        return runCatching {
            Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(f)?.let {
                val (y, mo, d) = it.destructured
                return@runCatching LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
            }
            Regex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})""").find(f)?.let {
                val (d, mo, y) = it.destructured
                return@runCatching LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
            }
            null
        }.getOrNull()
    }
}
