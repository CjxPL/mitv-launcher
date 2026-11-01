package pl.fouroptima.mitvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var grid: RecyclerView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var adapter: TileAdapter
    private lateinit var favorites: Favorites
    private lateinit var updates: UpdateManager
    private lateinit var weather: Weather
    private lateinit var panel: View
    private lateinit var panelScrim: View
    private lateinit var panelRows: ViewGroup
    private lateinit var rail: View
    private lateinit var railItems: ViewGroup
    private lateinit var railBottom: ViewGroup
    private lateinit var backdrop: Backdrop
    private lateinit var backdropA: ImageView
    private lateinit var backdropB: ImageView
    private var backdropFront: ImageView? = null
    private var lastBackdropAt = 0L
    private var focusBeforePanel: View? = null
    private var weatherInfo: WeatherInfo? = null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var apps: List<AppEntry> = emptyList()
    private var updateInfo: UpdateInfo? = null
    private var lastUpdateCheck = 0L
    private var focusOnLoad = true

    private val polish = Locale("pl", "PL")
    private val timeFormat = SimpleDateFormat("HH:mm", polish)
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", polish)

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    /** Instalacja, usunięcie albo aktualizacja aplikacji → odśwież listę. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        grid = findViewById(R.id.grid)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)

        favorites = Favorites(this)
        updates = UpdateManager(this)
        weather = Weather(this)
        backdrop = Backdrop(this)
        backdropA = findViewById(R.id.backdrop_a)
        backdropB = findViewById(R.id.backdrop_b)
        showBackdrop(backdrop.cached() ?: backdrop.bundled(), animate = false)
        setUpPanel()
        weatherInfo = weather.cached()
        showWeather()
        adapter = TileAdapter(::onTileClick, ::onTileLongClick, ::onTileFocus, SPAN)

        grid.layoutManager = GridLayoutManager(this, SPAN).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.rows[position].isTile) 1 else SPAN
            }
        }
        grid.adapter = adapter
        grid.itemAnimator = null
        adapter.weather = weatherInfo
        adapter.openedText = ::openedText

        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, packages, ContextCompat.RECEIVER_EXPORTED)

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        val time = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, timeReceiver, time, ContextCompat.RECEIVER_EXPORTED)

        // Ciche sprawdzenie nowej wersji co najwyżej raz na 30 minut
        if (System.currentTimeMillis() - lastUpdateCheck > UPDATE_INTERVAL_MS) checkForUpdates(silent = true)
        if (weatherInfo?.isStale != false) refreshWeather()
        // Nowa losowa tapeta przy każdym powrocie, nie częściej niż co 20 minut
        if (System.currentTimeMillis() - lastBackdropAt > BACKDROP_INTERVAL_MS) refreshBackdrop()
    }

    override fun onPause() {
        unregisterReceiver(timeReceiver)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        io.shutdownNow()
        super.onDestroy()
    }

    /** Przycisk Home na ekranie głównym: wróć na górę. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        grid.scrollToPosition(0)
        if (railItems.childCount > 0) railItems.getChildAt(0).requestFocus() else focusIndex(adapter.firstTileIndex())
        grid.post { grid.scrollToPosition(0) }
    }

    /** Wstecz zamyka panel, a na ekranie głównym nie zamyka launchera. Menu na pilocie otwiera panel. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (panel.visibility == View.VISIBLE) closePanel()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (panel.visibility == View.VISIBLE) closePanel() else openPanel()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- Panel skrótów ----------

    private fun setUpPanel() {
        panel = findViewById(R.id.side_panel)
        panelScrim = findViewById(R.id.panel_scrim)
        panelRows = findViewById(R.id.panel_rows)
        findViewById<TextView>(R.id.panel_version).text = getString(R.string.version, BuildConfig.VERSION_NAME)
        panelScrim.setOnClickListener { closePanel() }
        buildPanelRows()
        setUpRail()
    }

    // ---------- Pasek ulubionych po lewej ----------

    private fun setUpRail() {
        rail = findViewById(R.id.rail)
        railItems = findViewById(R.id.rail_items)
        railBottom = findViewById(R.id.rail_bottom)
    }

    private fun isInRail(v: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        if (v === rail) return true
        while (p != null) {
            if (p === rail) return true
            p = p.parent
        }
        return false
    }

    private fun buildRail(favApps: List<AppEntry>) {
        val focusedPkg = (currentFocus?.takeIf { isInRail(it) }?.tag as? String)
        railItems.removeAllViews()
        railBottom.removeAllViews()
        for (app in favApps) {
            val item = addRailItem(railItems, app.label, Artwork.accent(app)) {
                it.setImageBitmap(Artwork.whiteIcon(app))
                it.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ink))
            }
            item.tag = app.packageName
            val row = Row.App(app, inFavorites = true, isFavorite = true)
            item.setOnClickListener { onTileClick(row) }
            item.setOnLongClickListener { onTileLongClick(row) }
            item.setOnFocusChangeListener { _, has -> if (has) onTileFocus(row) }
            if (app.packageName == focusedPkg) item.post { item.requestFocus() }
        }
        // Usunięto zaznaczoną aplikację z paska – zaznaczenie przechodzi na sąsiada
        if (focusedPkg != null && favApps.none { it.packageName == focusedPkg }) {
            (railItems.getChildAt(0) ?: railBottom).post { (railItems.getChildAt(0) ?: railBottom.getChildAt(0))?.requestFocus() }
        }
        addRailItem(railBottom, getString(R.string.rail_apps), getColor(R.color.ink)) { it.setImageResource(R.drawable.ic_apps) }
            .setOnClickListener {
                val index = adapter.rows.indexOfFirst { it is Row.Section && it.title == getString(R.string.section_apps) }
                focusIndex(if (index >= 0) index + 1 else adapter.firstTileIndex())
            }
        addRailItem(railBottom, getString(R.string.rail_more), getColor(R.color.accent)) { it.setImageResource(R.drawable.ic_dots_white) }
        // Nowa tapeta w panelu – patrz buildPanelRows
            .setOnClickListener { openPanel() }
    }

    /** Element paska: biała ikona, nazwa widoczna po rozwinięciu, po zaznaczeniu tło w kolorze aplikacji. */
    private fun addRailItem(group: ViewGroup, label: String, accent: Int, icon: (ImageView) -> Unit): View {
        val item = LayoutInflater.from(this).inflate(R.layout.rail_item, group, false)
        icon(item.findViewById(R.id.rail_icon))
        val labelView = item.findViewById<TextView>(R.id.rail_label).apply { text = label }
        val circle = item.findViewById<View>(R.id.rail_circle)
        val iconView = item.findViewById<ImageView>(R.id.rail_icon)
        circle.backgroundTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(accent, getColor(R.color.glass)),
        )
        item.setOnFocusChangeListener { _, has ->
            circle.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(150).start()
            labelView.animate().alpha(if (has) 1f else 0f).translationX(if (has) 0f else -8f).setDuration(160).start()
            // Ciemna ikona na szkle, biała na kolorowym kółku
            iconView.imageTintList = android.content.res.ColorStateList.valueOf(
                if (has) android.graphics.Color.WHITE else getColor(R.color.ink),
            )
        }
        group.addView(item)
        return item
    }

    private fun buildPanelRows() {
        panelRows.removeAllViews()
        val update = updateInfo
        addPanelRow(R.drawable.ic_settings, getString(R.string.action_settings), getString(R.string.panel_settings_sub), null) {
            closePanel(); start(Intent(Settings.ACTION_SETTINGS))
        }
        addPanelRow(R.drawable.ic_wifi, getString(R.string.action_wifi), getString(R.string.panel_wifi_sub), null) {
            closePanel(); start(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        addPanelRow(
            R.drawable.ic_update,
            getString(R.string.action_update),
            if (update != null) getString(R.string.panel_update_ready, update.versionName) else getString(R.string.panel_update_sub),
            if (update != null) getString(R.string.badge_new) else null,
        ) { closePanel(); onUpdateClicked() }
        addPanelRow(R.drawable.ic_history, getString(R.string.panel_backdrop), getString(R.string.panel_backdrop_sub), null) {
            closePanel(); lastBackdropAt = 0L; refreshBackdrop()
        }
        addPanelRow(R.drawable.ic_menu_dots, getString(R.string.panel_close), getString(R.string.panel_close_sub), null) { closePanel() }
    }

    private fun addPanelRow(iconRes: Int, title: String, sub: String, badge: String?, onClick: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.panel_row, panelRows, false)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.row_title).text = title
        row.findViewById<TextView>(R.id.row_sub).text = sub
        row.findViewById<TextView>(R.id.row_badge).apply {
            visibility = if (badge != null) View.VISIBLE else View.GONE
            text = badge
        }
        row.setOnClickListener { onClick() }
        val titleView = row.findViewById<TextView>(R.id.row_title)
        val subView = row.findViewById<TextView>(R.id.row_sub)
        val iconView = row.findViewById<ImageView>(R.id.row_icon)
        row.setOnFocusChangeListener { _, has ->
            titleView.setTextColor(getColor(if (has) android.R.color.white else R.color.text))
            subView.setTextColor(if (has) 0xCCFFFFFF.toInt() else getColor(R.color.text_muted))
            iconView.imageTintList = android.content.res.ColorStateList.valueOf(if (has) android.graphics.Color.WHITE else getColor(R.color.ink))
        }
        panelRows.addView(row)
    }

    private fun openPanel() {
        if (panel.visibility == View.VISIBLE) return
        focusBeforePanel = currentFocus
        buildPanelRows()
        panelScrim.visibility = View.VISIBLE
        panelScrim.animate().alpha(1f).setDuration(220).start()
        panel.visibility = View.VISIBLE
        panel.translationX = panel.width.takeIf { it > 0 }?.toFloat() ?: 900f
        panel.animate().translationX(0f).setDuration(260).start()
        panelRows.getChildAt(0)?.requestFocus()
    }

    private fun closePanel() {
        if (panel.visibility != View.VISIBLE) return
        panelScrim.animate().alpha(0f).setDuration(200).withEndAction { panelScrim.visibility = View.GONE }.start()
        panel.animate().translationX(panel.width.toFloat()).setDuration(220).withEndAction { panel.visibility = View.GONE }.start()
        (focusBeforePanel ?: railBottom.getChildAt(railBottom.childCount - 1))?.requestFocus()
        focusBeforePanel = null
    }

    private fun updateClock() {
        val now = Date()
        clock.text = timeFormat.format(now)
        date.text = dateFormat.format(now).replaceFirstChar { it.titlecase(polish) }
        adapter.refreshGreeting()
    }

    private fun openedText(pkg: String): String? {
        val at = favorites.openedAt(pkg)
        if (at == 0L) return null
        val time = timeFormat.format(Date(at))
        val day = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val today = java.util.Calendar.getInstance()
        val sameDay = { a: java.util.Calendar, b: java.util.Calendar ->
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        }
        if (sameDay(day, today)) return getString(R.string.opened_today, time)
        today.add(java.util.Calendar.DAY_OF_YEAR, -1)
        if (sameDay(day, today)) return getString(R.string.opened_yesterday, time)
        return getString(R.string.opened_on, dateFormat.format(Date(at)).lowercase(polish))
    }

    private fun refreshBackdrop() {
        lastBackdropAt = System.currentTimeMillis()
        io.execute {
            val bmp = runCatching { backdrop.fetchRandom() }.getOrNull() ?: return@execute
            main.post { showBackdrop(bmp, animate = true) }
        }
    }

    private fun showBackdrop(bitmap: android.graphics.Bitmap?, animate: Boolean) {
        if (bitmap == null) return
        val front = backdropFront
        val back = if (front === backdropA) backdropB else backdropA
        back.setImageBitmap(bitmap)
        if (animate && front != null) {
            back.alpha = 0f
            back.animate().alpha(1f).setDuration(900).start()
            front.animate().alpha(0f).setDuration(900).withEndAction { front.setImageDrawable(null) }.start()
        } else {
            back.alpha = 1f
            front?.alpha = 0f
            front?.setImageDrawable(null)
        }
        backdropFront = back
    }

    private fun refreshWeather() {
        io.execute {
            val result = runCatching { weather.fetch() }.getOrNull() ?: return@execute
            main.post {
                weatherInfo = result
                showWeather()
                adapter.weather = result
            }
        }
    }

    private fun showWeather() {
        val w = weatherInfo ?: return
        val box = findViewById<View>(R.id.weather)
        box.visibility = View.VISIBLE
        findViewById<ImageView>(R.id.weather_icon).setImageResource(w.iconRes)
        findViewById<TextView>(R.id.weather_temp).text = "${w.temp}°"
        findViewById<TextView>(R.id.weather_desc).text = getString(w.descriptionRes)
        findViewById<TextView>(R.id.weather_range).text =
            if (w.city.isNotBlank()) getString(R.string.weather_range, w.max, w.min, w.city)
            else getString(R.string.weather_range_nocity, w.max, w.min)
    }

    /** Wybrany kafelek trafia na scenę; w rzędzie Ulubione pokazujemy też całą scenę (przewinięcie na górę). */
    private fun onTileFocus(row: Row) {
        adapter.showInHero(row)
        if (row is Row.App && (row.inFavorites || row.inRecent) && grid.computeVerticalScrollOffset() > 0) grid.smoothScrollToPosition(0)
    }

    private fun loadApps() {
        io.execute {
            val list = AppRepository.load(this)
            main.post {
                Artwork.clear() // nowe lub zaktualizowane aplikacje mogą mieć inne grafiki
                apps = list
                favorites.seedIfFirstRun(list.map { it.packageName }.toSet())
                render()
                if (focusOnLoad) {
                    focusOnLoad = false
                    if (railItems.childCount > 0) railItems.getChildAt(0).requestFocus() else focusIndex(adapter.firstTileIndex())
                }
            }
        }
    }

    private fun render() {
        val focusedKey = grid.focusedChild
            ?.let { grid.getChildAdapterPosition(it) }
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?.let { adapter.rows.getOrNull(it)?.key }

        val favPackages = favorites.list()
        val favSet = favPackages.toSet()
        val byPackage = apps.associateBy { it.packageName }
        val favApps = favPackages.mapNotNull { byPackage[it] }

        val rows = mutableListOf<Row>()
        rows += Row.Hero
        buildRail(favApps)
        if (favApps.isEmpty()) rows += Row.Hint(getString(R.string.hint_rail))

        val recentApps = favorites.recent().mapNotNull { byPackage[it] }
        if (recentApps.isNotEmpty()) {
            rows += Row.Section(getString(R.string.section_recent))
            recentApps.forEach { rows += Row.App(it, inFavorites = false, isFavorite = it.packageName in favSet, inRecent = true) }
        }

        rows += Row.Section(getString(R.string.section_apps))
        apps.forEach { rows += Row.App(it, inFavorites = false, isFavorite = it.packageName in favSet) }
        rows += Row.Hint(getString(R.string.footer, BuildConfig.VERSION_NAME))

        adapter.submit(rows)
        if (focusedKey != null) {
            val index = adapter.indexOfKey(focusedKey)
            focusIndex(if (index >= 0) index else adapter.firstTileIndex())
        }
    }

    private fun focusIndex(index: Int) {
        if (index < 0) return
        // Ulubione leżą tuż pod sceną – wtedy pokazujemy ekran od samej góry
        val top = (adapter.rows.getOrNull(index) as? Row.App)?.let { it.inFavorites || it.inRecent } == true
        grid.post {
            grid.scrollToPosition(if (top) 0 else index)
            grid.post { grid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
        }
    }

    private fun onTileClick(row: Row) {
        when (row) {
            is Row.App -> {
                val intent = AppRepository.launchIntent(this, row.app.packageName)
                if (intent == null) {
                    toast(getString(R.string.cannot_open, row.app.label))
                } else {
                    start(intent)
                    favorites.markOpened(row.app.packageName)
                    grid.postDelayed({ render() }, 600) // rząd „Ostatnio” odświeży się, gdy wrócisz
                }
            }
            is Row.Action -> when (row.id) {
                ACTION_SETTINGS -> start(Intent(Settings.ACTION_SETTINGS))
                ACTION_WIFI -> start(Intent(Settings.ACTION_WIFI_SETTINGS))
                ACTION_UPDATE -> onUpdateClicked()
            }
            else -> Unit
        }
    }

    /** Przytrzymanie OK na aplikacji dodaje ją do ulubionych albo usuwa. */
    private fun onTileLongClick(row: Row): Boolean {
        if (row !is Row.App) return false
        val added = favorites.toggle(row.app.packageName)
        toast(getString(if (added) R.string.fav_added else R.string.fav_removed, row.app.label))
        render()
        return true
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.cannot_open_generic))
        } catch (e: SecurityException) {
            toast(getString(R.string.cannot_open_generic))
        }
    }

    // ---------- Aktualizacje ----------

    private fun checkForUpdates(silent: Boolean, installIfFound: Boolean = false) {
        lastUpdateCheck = System.currentTimeMillis()
        io.execute {
            val result = runCatching { updates.check() }
            main.post {
                result.onSuccess { info ->
                    val changed = info?.versionCode != updateInfo?.versionCode
                    updateInfo = info
                    if (changed) render()
                    when {
                        info != null && installIfFound -> startDownload(info)
                        info == null && !silent -> toast(getString(R.string.up_to_date, BuildConfig.VERSION_NAME))
                        else -> Unit
                    }
                }.onFailure {
                    if (!silent) toast(getString(R.string.update_check_failed))
                }
            }
        }
    }

    private fun onUpdateClicked() {
        toast(getString(R.string.update_checking))
        checkForUpdates(silent = false, installIfFound = true)
    }

    private fun startDownload(info: UpdateInfo) {
        if (!updates.canInstall()) {
            askInstallPermission()
            return
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.update_title, info.versionName))
            .setMessage(getString(R.string.update_progress, 0))
            .setCancelable(false)
            .show()
        io.execute {
            val result = runCatching {
                updates.download(info) { pct -> main.post { dialog.setMessage(getString(R.string.update_progress, pct)) } }
            }
            main.post {
                dialog.dismiss()
                result.onSuccess { file ->
                    try {
                        updates.launchInstaller(file)
                    } catch (e: ActivityNotFoundException) {
                        toast(getString(R.string.update_failed))
                    }
                }.onFailure { toast(getString(R.string.update_failed)) }
            }
        }
    }

    private fun askInstallPermission() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_message)
            .setPositiveButton(R.string.permission_open) { _, _ ->
                try {
                    updates.openInstallPermissionSettings()
                } catch (e: ActivityNotFoundException) {
                    start(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val SPAN = 5
        const val ACTION_SETTINGS = 1
        const val ACTION_WIFI = 2
        const val ACTION_UPDATE = 3
        private const val UPDATE_INTERVAL_MS = 30 * 60 * 1000L
        private const val BACKDROP_INTERVAL_MS = 20 * 60 * 1000L
    }
}
