package pl.fouroptima.mitvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Cienka linia-podziałka: 40 odcinków, co piąta kreska wyższa. */
class RulerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f; color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val base = height - 1f
        paint.alpha = 50
        canvas.drawLine(0f, base, w, base, paint)
        val n = 40
        for (i in 0..n) {
            val x = w * i / n
            val major = i % 5 == 0
            paint.alpha = if (major) 85 else 48
            canvas.drawLine(x, base - (if (major) 10 else 5) * d, x, base, paint)
        }
    }
}
