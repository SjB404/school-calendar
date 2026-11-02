package nl.arch.deltionrooster

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var groupInput: AutoCompleteTextView
    private lateinit var calendarSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var rawText: TextView

    private var groups: List<String> = emptyList()
    private var calendars: List<CalendarSync.Cal> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())

        groupInput.setText(prefs.group ?: "SD4A")
        loadGroups()

        if (hasCalendarPermission()) {
            loadCalendars()
            if (prefs.group != null) SyncWorker.schedule(this, replace = false)
        } else {
            askCalendarPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, dp(16), 0, dp(4))
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }
        val full = { LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT) }

        col.addView(label("Class"))
        groupInput = AutoCompleteTextView(this).apply {
            isSingleLine = true
            threshold = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        col.addView(groupInput, full())

        col.addView(label("Put lessons in"))
        calendarSpinner = Spinner(this)
        col.addView(calendarSpinner, full())

        col.addView(
            button("Save and sync") { save() },
            full().apply { topMargin = dp(20) },
        )
        col.addView(button("Sync now") { runSync(cleanup = null) }, full())

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(16), 0, dp(8))
            setTextIsSelectable(true)
        }
        col.addView(statusText, full())

        col.addView(
            button("Show raw data (for fixing problems)") { showRaw() },
            full().apply { topMargin = dp(24) },
        )
        rawText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        col.addView(rawText, full())

        return ScrollView(this).apply { addView(col) }
    }

    private fun refreshStatus() {
        val s = prefs.status ?: "Not synced yet."
        statusText.text = if (prefs.group != null) "$s\n\nChecks for changes about every 3 hours." else s
    }

    // ---------- data loading ----------

    private fun loadGroups() {
        Thread {
            try {
                val list = DeltionApi.fetchGroups()
                runOnUiThread {
                    groups = list
                    groupInput.setAdapter(
                        ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, list)
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Couldn't load Deltion's class list: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun loadCalendars() {
        calendars = try {
            CalendarSync.writableCalendars(this)
        } catch (e: SecurityException) {
            emptyList()
        }
        val names = listOf("Only on this phone (own \"Deltion Rooster\" calendar)") +
            calendars.map { "${it.name}  (${it.account})" }
        calendarSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val saved = prefs.calendarId
        val index = if (saved == Prefs.OWN_CALENDAR) 0 else calendars.indexOfFirst { it.id == saved } + 1
        calendarSpinner.setSelection(index.coerceAtLeast(0))
    }

    private fun selectedCalendarId(): Long {
        val pos = calendarSpinner.selectedItemPosition
        return if (pos <= 0 || pos > calendars.size) Prefs.OWN_CALENDAR else calendars[pos - 1].id
    }

    // ---------- actions ----------

    private fun save() {
        if (!hasCalendarPermission()) {
            askCalendarPermission()
            return
        }
        val typed = groupInput.text.toString().trim()
        if (typed.isEmpty()) {
            statusText.text = "Type your class first."
            return
        }
        val match = groups.firstOrNull { it.equals(typed, ignoreCase = true) }
        if (groups.isNotEmpty() && match == null) {
            statusText.text = "\"$typed\" isn't in Deltion's class list."
            return
        }
        val group = match ?: typed.uppercase()

        val hadSetup = prefs.group != null
        val oldCalendar = prefs.calendarId
        val newCalendar = selectedCalendarId()

        prefs.group = group
        prefs.calendarId = newCalendar
        groupInput.setText(group)
        groupInput.dismissDropDown()

        SyncWorker.schedule(this, replace = true)
        runSync(cleanup = if (hadSetup && oldCalendar != newCalendar) oldCalendar else null)
    }

    /** cleanup = calendar to remove old lessons from, after switching calendars. */
    private fun runSync(cleanup: Long?) {
        if (prefs.group == null) {
            statusText.text = "Save your class first."
            return
        }
        if (!hasCalendarPermission()) {
            askCalendarPermission()
            return
        }
        statusText.text = "Syncing…"
        Thread {
            try {
                if (cleanup != null) CalendarSync.clear(this, cleanup)
            } catch (e: Exception) {
                // old lessons stay behind; the new sync still runs
            }
            CalendarSync.runAndRecord(this)
            runOnUiThread { refreshStatus() }
        }.start()
    }

    private fun showRaw() {
        val group = groupInput.text.toString().trim().ifEmpty { "SD4A" }
        rawText.text = "Loading…"
        Thread {
            val text = try {
                val monday = LocalDate.now(DeltionApi.zone)
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                DeltionApi.fetchRaw(group, monday, monday.plusDays(6))
            } catch (e: Exception) {
                "Error: ${e.message ?: e.javaClass.simpleName}"
            }
            val shown = if (text.length > 4000) text.take(4000) + "\n…(cut off)" else text
            runOnUiThread {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Deltion raw data", text))
                rawText.text = "Copied to clipboard.\n\n$shown"
            }
        }.start()
    }

    // ---------- permission ----------

    private fun hasCalendarPermission() =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun askCalendarPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            REQUEST_CALENDAR,
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CALENDAR) return
        if (hasCalendarPermission()) {
            loadCalendars()
            if (prefs.group != null) SyncWorker.schedule(this, replace = false)
        } else {
            statusText.text = "The app needs calendar access to add your lessons."
        }
    }

    companion object {
        private const val REQUEST_CALENDAR = 1
    }
}
