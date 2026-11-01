package pl.fouroptima.mitvlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable

/**
 * Tanie grafiki do sceny w stylu Apple TV: zamiast prawdziwego rozmycia (niedostępne na Androidzie 9
 * bez dodatkowych bibliotek) rysujemy obraz w małej bitmapie i rozciągamy ją – wygląda jak rozmycie,
 * a kosztuje kilka kilobajtów.
 */
object Artwork {

    private val blurCache = HashMap<String, Bitmap>()
    private val colorCache = HashMap<String, Int>()

    /** Mała bitmapa (rozciągana na całą scenę) z banera aplikacji albo jej ikony na jej kolorze. */
    fun blurred(app: AppEntry): Bitmap = blurCache.getOrPut(app.packageName) {
        val bmp = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val banner = app.banner
        if (banner != null) {
            draw(banner, canvas, 0, 0, 48, 27)
        } else {
            canvas.drawColor(tint(app))
            app.icon?.let { draw(it, canvas, 14, 4, 34, 23) }
        }
        bmp
    }

    /** Przyciemniony średni kolor ikony – tło kafelka dla aplikacji bez banera. */
    fun tint(app: AppEntry): Int = colorCache.getOrPut(app.packageName) {
        val icon = app.icon ?: return@getOrPut Color.rgb(30, 41, 59)
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        draw(icon, Canvas(bmp), 0, 0, 8, 8)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (x in 0 until 8) for (y in 0 until 8) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        bmp.recycle()
        if (n == 0L) return@getOrPut Color.rgb(30, 41, 59)
        // Przyciemnienie, żeby biała ikona i podpis były czytelne
        Color.rgb((r / n * 0.55).toInt(), (g / n * 0.55).toInt(), (b / n * 0.55).toInt())
    }

    fun clear() {
        blurCache.clear()
        colorCache.clear()
    }

    private fun draw(d: Drawable, canvas: Canvas, l: Int, t: Int, r: Int, b: Int) {
        val old = d.copyBounds()
        d.setBounds(l, t, r, b)
        d.draw(canvas)
        d.bounds = old
    }
}
