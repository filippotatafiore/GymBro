package com.example.gymbro.home

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.drawerlayout.widget.DrawerLayout
import com.example.gymbro.MainActivity
import com.example.gymbro.R
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.displayTitle
import com.example.gymbro.formatDuration
import com.example.gymbro.observe
import com.example.gymbro.showSessionDetails
import com.example.gymbro.viewmodel.HomeViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

// Calendar sliding in from the left of the home page (calendar button), like the menu of the
// profile: the days of a month, with a green dot under the days with at least one training
// session. Tap a day with a dot: a small window over the dot lists the sessions of that day
// (tap one to see its details). Layout: res/layout/calendar_drawer.xml in activity_main.xml.
// The sessions: HomeViewModel.
class CalendarDrawer(private val activity: MainActivity, viewModel: HomeViewModel) {

    private val drawerLayout: DrawerLayout = activity.findViewById(R.id.mainDrawer)
    private val drawer: View = activity.findViewById(R.id.calendarDrawer)
    private val monthTitle: TextView = activity.findViewById(R.id.calendarMonthTitle)
    private val grid: LinearLayout = activity.findViewById(R.id.calendarGrid)

    private val locale = Locale.getDefault()
    private val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek

    private var month = YearMonth.now()
    private var sessionsByDay: Map<LocalDate, List<TrainingSession>> = emptyMap()
    private var popup: PopupWindow? = null

    // The back button closes the calendar
    private val closeOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = close()
    }

    init {
        activity.findViewById<View>(R.id.calendarPrevMonth).setOnClickListener {
            month = month.minusMonths(1)
            showMonth()
        }
        activity.findViewById<View>(R.id.calendarNextMonth).setOnClickListener {
            month = month.plusMonths(1)
            showMonth()
        }
        showWeekDays()

        // Opened only with the calendar button: a swipe from the edge of the screen would also
        // move the map of a run. Once open, it can be closed with a swipe (or back, or a tap outside).
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
                closeOnBack.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START)
                closeOnBack.isEnabled = false
                popup?.dismiss()
            }
        })
        activity.onBackPressedDispatcher.addCallback(activity, closeOnBack)

        // The sessions of the logged user saved on this phone, also offline: the dots are updated
        // automatically when a session is saved, deleted or downloaded
        activity.observe(viewModel.sessionsByDay) { days ->
            sessionsByDay = days ?: return@observe
            showMonth()
        }
        showMonth()
    }

    // Opens the calendar on the current month
    fun open() {
        month = YearMonth.now()
        showMonth()
        drawerLayout.openDrawer(GravityCompat.START)
    }

    fun close() {
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    // e.g. "M T W T F S S" (Italian: "L M M G V S D")
    private fun showWeekDays() {
        val row = activity.findViewById<LinearLayout>(R.id.calendarWeekDays)
        CalendarMonth.weekDays(firstDayOfWeek).forEach { day ->
            row.addView(TextView(activity).apply {
                text = day.getDisplayName(TextStyle.NARROW, locale)
                gravity = Gravity.CENTER
                setTextColor(activity.getColor(R.color.textSecondary))
                textSize = 12f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // Name of the month and its days, week by week
    private fun showMonth() {
        popup?.dismiss()
        // e.g. "October 2026" ("LLLL": name of the month alone, e.g. "ottobre" in Italian)
        monthTitle.text = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
            .replaceFirstChar { it.titlecase(locale) }

        grid.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        val today = LocalDate.now()
        CalendarMonth.cells(month, firstDayOfWeek).chunked(7).forEach { week ->
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            grid.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            week.forEach { date ->
                val cell = inflater.inflate(R.layout.item_calendar_day, row, false)
                row.addView(cell)
                if (date == null) return@forEach  // empty cell

                val number = cell.findViewById<TextView>(R.id.dayNumber)
                number.text = String.format(locale, "%d", date.dayOfMonth)
                if (date == today) number.setBackgroundResource(R.drawable.calendar_today_background)

                // green dot: at least one session that day; tap the day to see them
                val sessions = sessionsByDay[date].orEmpty()
                val dot = cell.findViewById<View>(R.id.dayDot)
                dot.isVisible = sessions.isNotEmpty()
                if (sessions.isNotEmpty()) {
                    cell.setBackgroundResource(R.drawable.menu_item_background)
                    cell.setOnClickListener { showDaySessions(dot, date, sessions) }
                }
            }
        }
    }

    // Small window over the dot of a day, with the sessions of that day.
    // InflateParams: the content of a PopupWindow has no parent view.
    @SuppressLint("InflateParams")
    private fun showDaySessions(dot: View, date: LocalDate, sessions: List<TrainingSession>) {
        popup?.dismiss()
        val inflater = LayoutInflater.from(activity)
        val density = activity.resources.displayMetrics.density
        val content = inflater.inflate(R.layout.popup_day_sessions, null)
        content.findViewById<TextView>(R.id.popupDate).text =
            date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

        val width = (220 * density).toInt()
        val window = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        window.elevation = 12 * density
        window.animationStyle = android.R.style.Animation_Dialog

        // e.g. "Run · 5.3 km" + "18:32 · 42:10" (start time · duration)
        val list = content.findViewById<LinearLayout>(R.id.popupSessions)
        val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        sessions.sortedBy { it.startTime }.forEach { session ->
            val row = inflater.inflate(R.layout.item_day_session, list, false)
            row.findViewById<ImageView>(R.id.daySessionIcon).setImageResource(session.type.icon)
            row.findViewById<TextView>(R.id.daySessionTitle).text = session.displayTitle(activity)
            row.findViewById<TextView>(R.id.daySessionTime).text = activity.getString(
                R.string.day_session_time,
                Instant.ofEpochMilli(session.startTime).atZone(ZoneId.systemDefault()).format(timeFormat),
                formatDuration(session.durationMillis)
            )
            row.setOnClickListener {
                window.dismiss()
                showSessionDetails(activity, activity, session)
            }
            list.addView(row)
        }

        // Many sessions: the list scrolls, the window stays small
        val maxHeight = (260 * density).toInt()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        var height = content.measuredHeight
        if (height > maxHeight) {
            content.findViewById<View>(R.id.popupScroll).updateLayoutParams {
                this.height = maxHeight - (height - list.measuredHeight)
            }
            height = maxHeight
        }

        // Over the dot, centered on it but inside the calendar; below it if there is no room above
        val margin = (8 * density).toInt()
        val dotPosition = IntArray(2).also { dot.getLocationInWindow(it) }
        val drawerPosition = IntArray(2).also { drawer.getLocationInWindow(it) }
        val x = (dotPosition[0] + dot.width / 2 - width / 2)
            .coerceIn(drawerPosition[0] + margin, drawerPosition[0] + drawer.width - width - margin)
        var y = dotPosition[1] - height - margin
        if (y < drawerPosition[1] + margin) y = dotPosition[1] + dot.height + margin

        window.showAtLocation(dot, Gravity.NO_GRAVITY, x, y)
        popup = window
    }
}
