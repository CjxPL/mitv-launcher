package pl.fouroptima.mitvlauncher

import android.content.Context

/** Ulubione aplikacje w kolejności dodania, zapisane na sticku. */
class Favorites(context: Context) {

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun list(): List<String> =
        prefs.getString(KEY, null).orEmpty().split('\n').filter { it.isNotBlank() }

    /** Zwraca true, gdy aplikacja została dodana, false – gdy usunięta. */
    fun toggle(pkg: String): Boolean {
        val items = list().toMutableList()
        val added = if (items.remove(pkg)) false else items.add(pkg)
        save(items)
        return added
    }

    /** Przy pierwszym uruchomieniu wstawia popularne serwisy, które są zainstalowane. */
    fun seedIfFirstRun(installed: Set<String>) {
        if (prefs.getBoolean(SEEDED, false)) return
        save(DEFAULTS.filter { it in installed })
        prefs.edit().putBoolean(SEEDED, true).apply()
    }

    private fun save(items: List<String>) {
        prefs.edit().putString(KEY, items.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY = "favorites"
        const val SEEDED = "favorites_seeded"
        val DEFAULTS = listOf(
            "com.netflix.ninja",                  // Netflix
            "com.google.android.youtube.tv",      // YouTube
            "com.amazon.amazonvideo.livingroom",  // Prime Video
            "com.disney.disneyplus",              // Disney+
            "com.wbd.stream",                     // Max
            "com.spotify.tv.android",             // Spotify
        )
    }
}
