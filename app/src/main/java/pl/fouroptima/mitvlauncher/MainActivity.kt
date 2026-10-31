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
        findViewById<TextView>(R.id.version).text = getString(R.string.version, BuildConfig.VERSION_NAME)

        favorites = Favorites(this)
        updates = UpdateManager(this)
        adapter = TileAdapter(::onTileClick, ::onTileLongClick)

        grid.layoutManager = GridLayoutManager(this, SPAN).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.rows[position].isTile) 1 else SPAN
            }
        }
        grid.adapter = adapter
        grid.itemAnimator = null

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
        focusIndex(adapter.firstTileIndex())
    }

    /** Wstecz na ekranie głównym nie zamyka launchera. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun updateClock() {
        val now = Date()
        clock.text = timeFormat.format(now)
        date.text = dateFormat.format(now).replaceFirstChar { it.titlecase(polish) }
    }

    private fun loadApps() {
        io.execute {
            val list = AppRepository.load(this)
            main.post {
                apps = list
                favorites.seedIfFirstRun(list.map { it.packageName }.toSet())
                render()
                if (focusOnLoad) {
                    focusOnLoad = false
                    focusIndex(adapter.firstTileIndex())
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
        rows += Row.Section(getString(R.string.section_favorites))
        if (favApps.isEmpty()) {
            rows += Row.Hint(getString(R.string.hint_favorites))
        } else {
            favApps.forEach { rows += Row.App(it, inFavorites = true, isFavorite = true) }
        }

        rows += Row.Section(getString(R.string.section_shortcuts))
        rows += Row.Action(ACTION_SETTINGS, getString(R.string.action_settings), R.drawable.ic_settings, null)
        rows += Row.Action(ACTION_WIFI, getString(R.string.action_wifi), R.drawable.ic_wifi, null)
        val update = updateInfo
        rows += if (update != null) {
            Row.Action(ACTION_UPDATE, getString(R.string.action_update_to, update.versionName), R.drawable.ic_update, getString(R.string.badge_new))
        } else {
            Row.Action(ACTION_UPDATE, getString(R.string.action_update), R.drawable.ic_update, null)
        }

        rows += Row.Section(getString(R.string.section_all, apps.size))
        apps.forEach { rows += Row.App(it, inFavorites = false, isFavorite = it.packageName in favSet) }

        adapter.submit(rows)
        if (focusedKey != null) {
            val index = adapter.indexOfKey(focusedKey)
            focusIndex(if (index >= 0) index else adapter.firstTileIndex())
        }
    }

    private fun focusIndex(index: Int) {
        if (index < 0) return
        grid.post {
            grid.scrollToPosition(index)
            grid.post { grid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
        }
    }

    private fun onTileClick(row: Row) {
        when (row) {
            is Row.App -> {
                val intent = AppRepository.launchIntent(this, row.app.packageName)
                if (intent == null) toast(getString(R.string.cannot_open, row.app.label)) else start(intent)
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

    private companion object {
        const val SPAN = 5
        const val ACTION_SETTINGS = 1
        const val ACTION_WIFI = 2
        const val ACTION_UPDATE = 3
        const val UPDATE_INTERVAL_MS = 30 * 60 * 1000L
    }
}
