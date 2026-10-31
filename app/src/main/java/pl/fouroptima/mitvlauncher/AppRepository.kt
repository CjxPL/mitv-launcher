package pl.fouroptima.mitvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import java.text.Collator
import java.util.Locale

data class AppEntry(
    val packageName: String,
    val label: String,
    /** Poziomy obrazek 16:9, który aplikacje telewizyjne mają specjalnie na ekran główny. */
    val banner: Drawable?,
    val icon: Drawable?,
)

object AppRepository {

    /** Wszystkie aplikacje, które da się uruchomić: telewizyjne i zwykłe (np. wgrane z telefonu). */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val leanback = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
        )
        val phone = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        val result = LinkedHashMap<String, AppEntry>()
        for (ri in leanback + phone) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName || result.containsKey(pkg)) continue
            val label = ri.loadLabel(pm).toString().trim().ifEmpty { pkg }
            val banner = runCatching { ri.activityInfo.loadBanner(pm) }.getOrNull()
            val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
            result[pkg] = AppEntry(pkg, label, banner, icon)
        }
        val collator = Collator.getInstance(Locale("pl", "PL"))
        return result.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    fun launchIntent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        return pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    }
}
