package pl.fouroptima.mitvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dok-karuzela: zaznaczona ikona zawsze na środku, sąsiednie po bokach coraz mniejsze i bledsze,
 * lista zawija się w kółko. Jeden widok z fokusem – strzałki przesuwają cały rząd z animacją.
 */
class DockView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    class Item(
        val key: String,
        val label: String,
        val bitmap: Bitmap? = null,
        val iconRes: Int = 0,
        val onClick: () -> Unit,
        val onLongClick: (() -> Boolean)? = null,
    )

    private val items = ArrayList<Item>()
    private val views = ArrayList<ImageView>()
    private var selected = 0
    private val density = resources.displayMetrics.density
    private val spacing = 92 * density
    private val iconSize = (30 * density).roundToInt()
    private val visibleEachSide = 5

    /** Wywoływane, gdy zmienia się zaznaczenie – do podpisu nad dokiem. */
    var onSelected: ((Item) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        clipChildren = false
        clipToPadding = false
        setOnFocusChangeListener { _, has -> layoutItems(animate = true); if (has) items.getOrNull(selected)?.let { onSelected?.invoke(it) } }
    }

    fun setItems(newItems: List<Item>, keepKey: String? = null) {
        items.clear()
        items.addAll(newItems)
        removeAllViews()
        views.clear()
        for (item in items) {
            val iv = ImageView(context).apply {
                layoutParams = LayoutParams(iconSize, iconSize).apply { gravity = android.view.Gravity.CENTER }
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (item.bitmap != null) setImageBitmap(item.bitmap) else setImageResource(item.iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFF5F7FA.toInt())
                contentDescription = item.label
            }
            addView(iv)
            views += iv
        }
        val keep = keepKey?.let { k -> items.indexOfFirst { it.key == k } } ?: -1
        selected = if (keep >= 0) keep else selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        layoutItems(animate = false)
        items.getOrNull(selected)?.let { onSelected?.invoke(it) }
    }

    fun selectedItem(): Item? = items.getOrNull(selected)

    private fun move(delta: Int) {
        if (items.isEmpty()) return
        selected = ((selected + delta) % items.size + items.size) % items.size
        layoutItems(animate = true)
        items[selected].let { onSelected?.invoke(it) }
    }

    /** Najkrótsze przesunięcie w kółku: -n/2..n/2. */
    private fun offsetOf(index: Int): Int {
        val n = items.size
        var d = index - selected
        if (d > n / 2) d -= n
        if (d < -n / 2) d += n
        return d
    }

    private fun layoutItems(animate: Boolean) {
        val focused = isFocused
        for ((i, v) in views.withIndex()) {
            val d = offsetOf(i)
            val far = abs(d) > visibleEachSide
            val x = d * spacing
            val scale = when {
                d == 0 && focused -> 1.9f
                d == 0 -> 1.25f
                abs(d) == 1 -> 1.05f
                else -> 1f
            }
            val alpha = when {
                far -> 0f
                d == 0 -> 1f
                abs(d) == 1 -> 0.8f
                abs(d) == 2 -> 0.6f
                else -> 0.4f
            }
            // Element „wskakujący” z drugiej strony koła nie przelatuje przez środek – ustawiamy go od razu
            val jumped = abs(v.translationX - x) > spacing * visibleEachSide
            if (animate && !jumped) {
                v.animate().translationX(x).scaleX(scale).scaleY(scale).alpha(alpha).setDuration(220).start()
            } else {
                v.animate().cancel()
                v.translationX = x; v.scaleX = scale; v.scaleY = scale; v.alpha = alpha
            }
            v.translationZ = if (d == 0) 4f else 0f
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                event.startTracking()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            longPressed = true
            return items.getOrNull(selected)?.onLongClick?.invoke() ?: false
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private var longPressed = false

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val wasLong = longPressed
            longPressed = false
            if (!wasLong && !event.isCanceled) {
                val v = views.getOrNull(selected)
                v?.animate()?.scaleX(1.6f)?.scaleY(1.6f)?.setDuration(80)?.withEndAction { layoutItems(animate = true) }?.start()
                items.getOrNull(selected)?.onClick?.invoke()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) layoutItems(animate = false)
    }
}
