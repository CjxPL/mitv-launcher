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

    private val whiteCache = HashMap<String, Bitmap>()
    private val accentCache = HashMap<String, Int>()

    /**
     * Biały kontur logo: ikonę zamieniamy na maskę – im bardziej piksel różni się od tła ikony
     * (tło = brzegi), tym bardziej jest widoczny. Działa i dla ciemnych, i dla jasnych ikon.
     */
    fun whiteIcon(app: AppEntry): Bitmap = whiteCache.getOrPut(app.packageName) {
        val size = 96
        val src = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val d = app.icon ?: app.banner
        if (d != null) draw(d, Canvas(src), 0, 0, size, size)
        val px = IntArray(size * size)
        src.getPixels(px, 0, size, 0, 0, size, size)
        src.recycle()
        // Luminancja tła z brzegów (tylko nieprzezroczyste piksele)
        var sum = 0.0
        var n = 0
        for (i in 0 until size) for (j in listOf(1, size - 2)) {
            for (c in listOf(px[j * size + i], px[i * size + j])) {
                if (Color.alpha(c) > 200) { sum += lum(c); n++ }
            }
        }
        val bg = if (n > 0) sum / n else 0.0
        val out = IntArray(size * size)
        var covered = 0
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c)
            if (a < 16) { out[i] = 0; continue }
            val diff = kotlin.math.abs(lum(c) - bg) // 0..255
            val alpha = (diff * 1.6).coerceIn(0.0, 255.0) * a / 255.0
            out[i] = Color.argb(alpha.toInt(), 255, 255, 255)
            if (alpha > 96) covered++
        }
        // Gdy kontur prawie pusty (np. ikona jednolita) – pokaż ikonę jako jasną sylwetkę po alfie
        if (covered < size * size / 60) {
            for (i in px.indices) out[i] = Color.argb(Color.alpha(px[i]), 255, 255, 255)
        }
        Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888)
    }

    /** Kolor marki: średnia najbardziej nasyconych pikseli ikony; gdy ich brak – pomarańcz 4optima. */
    fun accent(app: AppEntry): Int = accentCache.getOrPut(app.packageName) {
        val size = 32
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val d = app.icon ?: app.banner ?: return@getOrPut Color.rgb(254, 91, 0)
        draw(d, Canvas(bmp), 0, 0, size, size)
        val hsv = FloatArray(3)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (x in 0 until size) for (y in 0 until size) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.45f || hsv[2] < 0.35f) continue
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        bmp.recycle()
        if (n < 4) return@getOrPut Color.rgb(254, 91, 0)
        val c = Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        // Zbyt jasny kolor przyciemniamy, żeby biały napis był czytelny
        Color.colorToHSV(c, hsv)
        if (hsv[2] > 0.8f) hsv[2] = 0.8f
        if (hsv[1] < 0.7f) hsv[1] = 0.7f
        Color.HSVToColor(hsv)
    }

    private fun lum(c: Int): Double = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    fun clear() {
        blurCache.clear()
        colorCache.clear()
        whiteCache.clear()
        accentCache.clear()
    }

    private fun draw(d: Drawable, canvas: Canvas, l: Int, t: Int, r: Int, b: Int) {
        val old = d.copyBounds()
        d.setBounds(l, t, r, b)
        d.draw(canvas)
        d.bounds = old
    }
}
