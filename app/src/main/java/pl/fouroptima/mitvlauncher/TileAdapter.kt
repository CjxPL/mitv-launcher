package pl.fouroptima.mitvlauncher

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar

/** Jeden element ekranu głównego. */
sealed class Row {
    abstract val key: String

    /** Duża scena u góry (jak „top shelf” w Apple TV). */
    object Hero : Row() {
        override val key get() = "hero"
    }

    data class Section(val title: String) : Row() {
        override val key get() = "section:$title"
    }

    data class Hint(val text: String) : Row() {
        override val key get() = "hint:$text"
    }

    /** [inFavorites] = kafelek w rzędzie Ulubione (ta sama aplikacja może być też niżej, na liście wszystkich). */
    data class App(val app: AppEntry, val inFavorites: Boolean, val isFavorite: Boolean, val inRecent: Boolean = false) : Row() {
        override val key get() = (if (inFavorites) "fav:" else if (inRecent) "recent:" else "all:") + app.packageName
    }

    data class Action(val id: Int, val label: String, val iconRes: Int, val badge: String?) : Row() {
        override val key get() = "action:$id"
    }

    val isTile get() = this is App || this is Action
}

class TileAdapter(
    private val onClick: (Row) -> Unit,
    private val onLongClick: (Row) -> Boolean,
    private val onFocus: (Row) -> Unit,
    private val spanCount: Int,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<Row> = emptyList()
        private set

    private var hero: HeroHolder? = null
    private var heroRow: Row? = null

    /** Zdanie „Otwierane dziś o …” dla aplikacji (null = pokaż podpowiedź o pilocie). */
    var openedText: ((String) -> String?)? = null

    /** Krótko: „dziś 10:32” – do kafelka z danymi na karcie. */
    var openedShort: ((String) -> String?)? = null

    /** Ostatnio otwierane aplikacje z opisem czasu – do karty dolnej-lewej. */
    var recentApps: List<Pair<AppEntry, String>> = emptyList()

    /** Pogoda pokazywana na scenie powitalnej (null = brak). */
    var weather: WeatherInfo? = null
        set(value) {
            field = value
            refreshGreeting()
        }

    init {
        setHasStableIds(true)
    }

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    fun indexOfKey(key: String): Int = rows.indexOfFirst { it.key == key }

    fun firstTileIndex(): Int = rows.indexOfFirst { it.isTile }

    /** Zmienia treść sceny bez przebudowy listy (płynne przejście). */
    fun showInHero(row: Row?) {
        if (row != null && row.key == heroRow?.key) return
        heroRow = row
        hero?.show(row, animate = true)
    }

    fun refreshGreeting() {
        if (heroRow == null) hero?.show(null, animate = false)
    }

    override fun getItemCount() = rows.size

    override fun getItemId(position: Int) = rows[position].key.hashCode().toLong()

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Hero -> TYPE_HERO
        is Row.Section -> TYPE_SECTION
        is Row.Hint -> TYPE_HINT
        else -> TYPE_TILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HERO -> {
                val view = inflater.inflate(R.layout.item_hero, parent, false)
                val h = parent.height.takeIf { it > 0 } ?: parent.resources.displayMetrics.heightPixels
                view.layoutParams.height = h - parent.paddingTop - (8 * parent.resources.displayMetrics.density).toInt()
                HeroHolder(view).also { hero = it }
            }
            TYPE_SECTION -> TextHolder(inflater.inflate(R.layout.item_section, parent, false))
            TYPE_HINT -> TextHolder(inflater.inflate(R.layout.item_hint, parent, false))
            else -> {
                val view = inflater.inflate(R.layout.item_tile, parent, false)
                // Karta 16:9 dopasowana do szerokości kolumny
                val res = parent.resources
                val inner = parent.width - parent.paddingLeft - parent.paddingRight
                val colW = if (inner > 0) inner / spanCount else res.getDimensionPixelSize(R.dimen.tile_fallback_width)
                val cardW = colW - 2 * res.getDimensionPixelSize(R.dimen.tile_gap)
                view.findViewById<View>(R.id.card).layoutParams.height = cardW * 9 / 16
                TileHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Hero -> (holder as HeroHolder).show(heroRow, animate = false)
            is Row.Section -> (holder as TextHolder).text.text = row.title
            is Row.Hint -> (holder as TextHolder).text.text = row.text
            is Row.App -> (holder as TileHolder).bindApp(row)
            is Row.Action -> (holder as TileHolder).bindAction(row)
        }
    }

    class TextHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }

    /** Scena jak we wzorze: nagłówek z lewej, karta „Ostatnio” dolna-lewa, karta aplikacji dolna-prawa. */
    inner class HeroHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val poster: View = view.findViewById(R.id.hero_poster)
        private val posterImage: ImageView = view.findViewById(R.id.hero_poster_image)
        private val cardTitle: TextView = view.findViewById(R.id.hero_card_title)
        private val cardSub: TextView = view.findViewById(R.id.hero_card_sub)
        private val cardDesc: TextView = view.findViewById(R.id.hero_card_desc)
        private val statFav: View = view.findViewById(R.id.stat_fav)
        private val statTime: View = view.findViewById(R.id.stat_time)
        private val statKind: View = view.findViewById(R.id.stat_kind)
        private val text: View = view.findViewById(R.id.hero_text)
        private val eyebrow: TextView = view.findViewById(R.id.hero_eyebrow)
        private val title: TextView = view.findViewById(R.id.hero_title)
        private val subtitle: TextView = view.findViewById(R.id.hero_subtitle)
        private val recentLines: TextView = view.findViewById(R.id.recent_lines)
        private val recentImage: ImageView = view.findViewById(R.id.recent_image)

        init {
            posterImage.clipToOutline = true
            recentImage.clipToOutline = true
        }

        fun show(row: Row?, animate: Boolean) {
            val res = itemView.resources
            bindRecent()
            val app = (row as? Row.App)?.app
            when {
                app != null -> {
                    eyebrow.text = res.getString(
                        when {
                            (row as Row.App).inRecent -> R.string.hero_recent
                            row.isFavorite -> R.string.hero_favorite
                            else -> R.string.hero_app
                        },
                    )
                    title.text = app.label
                    subtitle.text = openedText?.invoke(app.packageName) ?: res.getString(R.string.hero_open_hint)
                    poster.visibility = View.VISIBLE
                    cardTitle.text = app.label
                    cardSub.text = openedText?.invoke(app.packageName) ?: res.getString(R.string.stat_never)
                    cardDesc.text = res.getString(if (row.isFavorite) R.string.card_desc_fav else R.string.card_desc_app)
                    stat(statFav, R.drawable.ic_star_line, res.getString(if (row.isFavorite) R.string.stat_fav_yes else R.string.stat_fav_no))
                    stat(statTime, R.drawable.ic_clock_line, openedShort?.invoke(app.packageName) ?: res.getString(R.string.stat_never))
                    stat(statKind, R.drawable.ic_tv_line, res.getString(if (app.isTv) R.string.stat_tv else R.string.stat_phone))
                    if (app.banner != null) {
                        posterImage.setPadding(0, 0, 0, 0)
                        posterImage.setImageDrawable(app.banner)
                        posterImage.backgroundTintList = null
                    } else {
                        posterImage.setPadding(6, 6, 6, 6)
                        posterImage.setImageDrawable(app.icon)
                        posterImage.backgroundTintList = ColorStateList.valueOf(Artwork.tint(app))
                    }
                }
                row is Row.Action -> {
                    eyebrow.text = res.getString(R.string.hero_shortcut)
                    title.text = row.label
                    subtitle.text = ""
                    poster.visibility = View.GONE
                }
                else -> {
                    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    eyebrow.text = res.getString(R.string.hero_welcome)
                    title.text = res.getString(
                        when (hour) {
                            in 5..10 -> R.string.greeting_morning
                            in 11..17 -> R.string.greeting_day
                            else -> R.string.greeting_evening
                        },
                    )
                    val w = weather
                    subtitle.text = if (w != null) {
                        res.getString(R.string.hero_weather, w.temp, res.getString(w.descriptionRes)) +
                            ". " + res.getString(R.string.hero_default_hint)
                    } else {
                        res.getString(R.string.hero_default_hint)
                    }
                    poster.visibility = View.GONE
                }
            }
            if (animate) {
                text.alpha = 0f
                text.translationY = 14f
                text.animate().alpha(1f).translationY(0f).setDuration(240).start()
                if (poster.visibility == View.VISIBLE) {
                    poster.alpha = 0f
                    poster.translationY = 10f
                    poster.animate().alpha(1f).translationY(0f).setDuration(280).start()
                }
            }
        }

        private fun stat(v: View, iconRes: Int, label: String) {
            v.findViewById<ImageView>(R.id.stat_icon).setImageResource(iconRes)
            v.findViewById<TextView>(R.id.stat_text).text = label
        }

        private fun bindRecent() {
            val res = itemView.resources
            val list = recentApps
            recentLines.text = if (list.isEmpty()) {
                res.getString(R.string.card_recent_empty)
            } else {
                list.take(3).joinToString("\n") { (app, line) -> "${app.label} · $line" }
            }
            val first = list.firstOrNull()?.first
            when {
                first?.banner != null -> { recentImage.setImageDrawable(first.banner); recentImage.backgroundTintList = null }
                first != null -> { recentImage.setImageDrawable(first.icon); recentImage.backgroundTintList = ColorStateList.valueOf(Artwork.tint(first)) }
                else -> { recentImage.setImageDrawable(null); recentImage.backgroundTintList = null }
            }
        }
    }

    inner class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: View = view.findViewById(R.id.card)
        private val image: ImageView = view.findViewById(R.id.image)
        private val label: TextView = view.findViewById(R.id.label)
        private val badge: TextView = view.findViewById(R.id.badge)
        private val star: View = view.findViewById(R.id.star)

        init {
            card.clipToOutline = true
            view.setOnClickListener { rowAt()?.let(onClick) }
            view.setOnLongClickListener { rowAt()?.let(onLongClick) ?: false }
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(180).start()
                card.animate().translationZ(if (hasFocus) 16f else 0f).setDuration(180).start()
                label.isSelected = hasFocus // przewijanie długiej nazwy
                if (hasFocus) rowAt()?.let(onFocus)
            }
        }

        private fun rowAt(): Row? = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { rows[it] }

        fun bindApp(row: Row.App) {
            resetFocusState()
            val app = row.app
            if (app.banner != null) {
                image.setPadding(0, 0, 0, 0)
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.setImageDrawable(app.banner)
                card.backgroundTintList = null
            } else {
                val pad = itemView.resources.getDimensionPixelSize(R.dimen.icon_padding)
                image.setPadding(pad, pad, pad, pad)
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                image.setImageDrawable(app.icon)
                card.backgroundTintList = ColorStateList.valueOf(Artwork.tint(app))
            }
            label.text = app.label
            badge.visibility = View.GONE
            star.visibility = if (row.isFavorite && !row.inFavorites) View.VISIBLE else View.GONE
        }

        fun bindAction(row: Row.Action) {
            resetFocusState()
            val pad = itemView.resources.getDimensionPixelSize(R.dimen.action_icon_padding)
            image.setPadding(pad, pad, pad, pad)
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.setImageResource(row.iconRes)
            card.backgroundTintList = null
            label.text = row.label
            badge.visibility = if (row.badge != null) View.VISIBLE else View.GONE
            badge.text = row.badge
            star.visibility = View.GONE
        }

        /** Ponownie użyty kafelek nie może zostać powiększony po poprzedniej aplikacji. */
        private fun resetFocusState() {
            val focused = itemView.isFocused
            itemView.scaleX = if (focused) 1.08f else 1f
            itemView.scaleY = itemView.scaleX
            card.translationZ = if (focused) 16f else 0f
        }
    }

    companion object {
        const val TYPE_HERO = 0
        const val TYPE_SECTION = 1
        const val TYPE_HINT = 2
        const val TYPE_TILE = 3
    }
}
