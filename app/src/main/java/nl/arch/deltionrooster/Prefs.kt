package nl.arch.deltionrooster

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var group: String?
        get() = sp.getString("group", null)
        set(v) = sp.edit().putString("group", v).apply()

    /** OWN_CALENDAR = the app's own "Deltion Rooster" calendar on the phone. */
    var calendarId: Long
        get() = sp.getLong("calendar", OWN_CALENDAR)
        set(v) = sp.edit().putLong("calendar", v).apply()

    var status: String?
        get() = sp.getString("status", null)
        set(v) = sp.edit().putString("status", v).apply()

    companion object {
        const val OWN_CALENDAR = -1L
    }
}
