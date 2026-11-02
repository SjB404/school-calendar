package nl.arch.deltionrooster

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Puts lessons into a calendar. Every event this app makes has MARKER in its
 * description, so it only ever touches its own events, even in a Google calendar.
 */
object CalendarSync {
    private const val MARKER = "#deltionrooster"
    private const val OWN_NAME = "Deltion Rooster"
    const val WEEKS_AHEAD = 4L

    data class Cal(val id: Long, val name: String, val account: String)

    /** Calendars the app may write to, not counting its own one. */
    fun writableCalendars(ctx: Context): List<Cal> {
        val out = mutableListOf<Cal>()
        ctx.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val account = c.getString(2) ?: ""
                val type = c.getString(3) ?: ""
                if (type == CalendarContract.ACCOUNT_TYPE_LOCAL && account == OWN_NAME) continue
                out += Cal(c.getLong(0), c.getString(1) ?: "Calendar", account)
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun findOwnCalendar(ctx: Context): Long? {
        ctx.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(OWN_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL),
            null,
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    private fun createOwnCalendar(ctx: Context): Long {
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, OWN_NAME)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, OWN_NAME)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, OWN_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, OWN_NAME)
            put(Calendars.CALENDAR_COLOR, Color.parseColor("#1E6FD9"))
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, OWN_NAME)
            put(Calendars.CALENDAR_TIME_ZONE, DeltionApi.zone.id)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        val created = ctx.contentResolver.insert(uri, v)
            ?: throw IllegalStateException("Couldn't create the Deltion Rooster calendar")
        return ContentUris.parseId(created)
    }

    private fun resolve(ctx: Context, choice: Long, create: Boolean): Long? =
        if (choice == Prefs.OWN_CALENDAR) findOwnCalendar(ctx) ?: (if (create) createOwnCalendar(ctx) else null)
        else choice

    /** Removes every event this app made in that calendar (used when switching calendars). */
    fun clear(ctx: Context, choice: Long) {
        val id = resolve(ctx, choice, create = false) ?: return
        ctx.contentResolver.delete(
            Events.CONTENT_URI,
            "${Events.CALENDAR_ID} = ? AND ${Events.DESCRIPTION} LIKE ?",
            arrayOf(id.toString(), "%$MARKER%"),
        )
    }

    private fun key(start: Long, end: Long, title: String?, place: String?, desc: String?) =
        listOf(start.toString(), end.toString(), title ?: "", place ?: "", desc ?: "").joinToString("\u0001")

    private fun eventValues(calId: Long, l: Lesson) = ContentValues().apply {
        put(Events.CALENDAR_ID, calId)
        put(Events.DTSTART, l.start)
        put(Events.DTEND, l.end)
        put(Events.TITLE, l.title)
        put(Events.EVENT_LOCATION, l.room)
        put(Events.DESCRIPTION, (if (l.teacher.isNotEmpty()) "Teacher: ${l.teacher}\n" else "") + MARKER)
        put(Events.EVENT_TIMEZONE, DeltionApi.zone.id)
    }

    private fun ContentValues.key() = key(
        getAsLong(Events.DTSTART), getAsLong(Events.DTEND),
        getAsString(Events.TITLE), getAsString(Events.EVENT_LOCATION), getAsString(Events.DESCRIPTION),
    )

    /**
     * Makes the calendar match Deltion's schedule from the start of this week
     * to WEEKS_AHEAD weeks later. Only adds and removes what changed.
     */
    fun sync(ctx: Context, group: String, choice: Long): String {
        val calId = resolve(ctx, choice, create = true)!!
        val zone = DeltionApi.zone
        val first = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val last = first.plusWeeks(WEEKS_AHEAD).minusDays(1)
        val from = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val lessons = DeltionApi.fetchRoster(group, first, last).filter { it.start in from until to }

        val wanted = LinkedHashMap<String, ContentValues>()
        for (l in lessons) {
            val v = eventValues(calId, l)
            wanted.putIfAbsent(v.key(), v)
        }

        val ops = ArrayList<ContentProviderOperation>()
        val have = HashSet<String>()
        ctx.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.DTSTART, Events.DTEND, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION),
            "${Events.CALENDAR_ID} = ? AND ${Events.DESCRIPTION} LIKE ? AND " +
                "${Events.DTSTART} >= ? AND ${Events.DTSTART} < ? AND ${Events.DELETED} = 0",
            arrayOf(calId.toString(), "%$MARKER%", from.toString(), to.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val k = key(c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5))
                if (k in wanted && have.add(k)) continue // already there, keep it
                ops += ContentProviderOperation
                    .newDelete(ContentUris.withAppendedId(Events.CONTENT_URI, c.getLong(0)))
                    .build()
            }
        }
        val removed = ops.size
        var added = 0
        for ((k, v) in wanted) {
            if (k in have) continue
            ops += ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(v).build()
            added++
        }
        if (ops.isNotEmpty()) ctx.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)

        return "${wanted.size} lessons in the next $WEEKS_AHEAD weeks ($added added, $removed removed)"
    }

    /** Runs a sync with the saved settings and stores a status line. Returns true on success. */
    fun runAndRecord(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        val group = prefs.group ?: return false
        val time = LocalDateTime.now(DeltionApi.zone)
            .format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH))
        return try {
            val msg = sync(ctx, group, prefs.calendarId)
            prefs.status = "Last sync: $time\n$group: $msg"
            true
        } catch (e: SecurityException) {
            prefs.status = "Sync failed ($time): calendar permission is missing"
            false
        } catch (e: Exception) {
            prefs.status = "Sync failed ($time): ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }
}
