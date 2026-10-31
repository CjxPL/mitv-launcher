package pl.fouroptima.mitvlauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Jeden element siatki ekranu głównego. */
sealed class Row {
    abstract val key: String

    data class Section(val title: String) : Row() {
        override val key get() = "section:$title"
    }

    data class Hint(val text: String) : Row() {
        override val key get() = "hint"
    }

    /** [inFavorites] = kafelek w rzędzie Ulubione (ta sama aplikacja może być też niżej, na liście wszystkich). */
    data class App(val app: AppEntry, val inFavorites: Boolean, val isFavorite: Boolean) : Row() {
        override val key get() = (if (inFavorites) "fav:" else "all:") + app.packageName
    }

    data class Action(val id: Int, val label: String, val iconRes: Int, val badge: String?) : Row() {
        override val key get() = "action:$id"
    }

    val isTile get() = this is App || this is Action
}

class TileAdapter(
    private val onClick: (Row) -> Unit,
    private val onLongClick: (Row) -> Boolean,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<Row> = emptyList()
        private set

    init {
        setHasStableIds(true)
    }

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    fun indexOfKey(key: String): Int = rows.indexOfFirst { it.key == key }

    fun firstTileIndex(): Int = rows.indexOfFirst { it.isTile }

    override fun getItemCount() = rows.size

    override fun getItemId(position: Int) = rows[position].key.hashCode().toLong()

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Section -> TYPE_SECTION
        is Row.Hint -> TYPE_HINT
        else -> TYPE_TILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION -> TextHolder(inflater.inflate(R.layout.item_section, parent, false))
            TYPE_HINT -> TextHolder(inflater.inflate(R.layout.item_hint, parent, false))
            else -> TileHolder(inflater.inflate(R.layout.item_tile, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // Ponownie użyty kafelek nie może zostać powiększony po poprzedniej aplikacji
        if (holder is TileHolder && !holder.itemView.isFocused) {
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
            holder.itemView.translationZ = 0f
        }
        when (val row = rows[position]) {
            is Row.Section -> (holder as TextHolder).text.text = row.title
            is Row.Hint -> (holder as TextHolder).text.text = row.text
            is Row.App -> (holder as TileHolder).bindApp(row)
            is Row.Action -> (holder as TileHolder).bindAction(row)
        }
    }

    class TextHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }

    inner class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.image)
        private val label: TextView = view.findViewById(R.id.label)
        private val badge: TextView = view.findViewById(R.id.badge)
        private val star: View = view.findViewById(R.id.star)

        init {
            view.setOnClickListener { rowAt()?.let(onClick) }
            view.setOnLongClickListener { rowAt()?.let(onLongClick) ?: false }
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                v.translationZ = if (hasFocus) 8f else 0f
                label.isSelected = hasFocus // przewijanie długiej nazwy
            }
        }

        private fun rowAt(): Row? = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { rows[it] }

        fun bindApp(row: Row.App) {
            val app = row.app
            if (app.banner != null) {
                image.setPadding(0, 0, 0, 0)
                image.setImageDrawable(app.banner)
            } else {
                val pad = itemView.resources.getDimensionPixelSize(R.dimen.icon_padding)
                image.setPadding(pad, pad, pad, pad)
                image.setImageDrawable(app.icon)
            }
            label.text = app.label
            badge.visibility = View.GONE
            star.visibility = if (row.isFavorite && !row.inFavorites) View.VISIBLE else View.GONE
        }

        fun bindAction(row: Row.Action) {
            val pad = itemView.resources.getDimensionPixelSize(R.dimen.action_icon_padding)
            image.setPadding(pad, pad, pad, pad)
            image.setImageResource(row.iconRes)
            label.text = row.label
            badge.visibility = if (row.badge != null) View.VISIBLE else View.GONE
            badge.text = row.badge
            star.visibility = View.GONE
        }
    }

    companion object {
        const val TYPE_SECTION = 0
        const val TYPE_HINT = 1
        const val TYPE_TILE = 2
    }
}
