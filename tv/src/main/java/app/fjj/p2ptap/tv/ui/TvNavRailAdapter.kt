package app.fjj.p2ptap.tv.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.content.ContextCompat.getColor
import androidx.core.content.ContextCompat.getString
import androidx.recyclerview.widget.RecyclerView
import app.fjj.p2ptap.tv.R

/**
 * Rail item adapter for the left navigation rail.
 *
 * Each item is a LinearLayout with an icon and label. State changes are
 * handled by swapping the icon tint and label color between
 * `colorPrimary` (selected) and `colorOnSurfaceVariant` (unselected).
 *
 * The selection change is animated by the RecyclerView's default
 * focus listener on the item; the item's own focus listener calls
 * TvFocusAnimation.attachTo, which scales the item.
 */
class TvNavRailAdapter(
    private val destinations: List<TvNavDestination>,
    private var selectedId: Int,
) : RecyclerView.Adapter<TvNavRailAdapter.RailItemHolder>() {

    var onSelect: (TvNavDestination) -> Unit = {}

    class RailItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view as LinearLayout
        val icon: ImageView = view.findViewById(R.id.tv_rail_icon)
        val label: TextView = view.findViewById(R.id.tv_rail_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RailItemHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.item_tv_rail, parent, false)
        return RailItemHolder(view).apply {
            TvFocusAnimation.attachTo(root, TvFocusAnimation.SCALE_RAIL)
            root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onSelect(destinations[pos])
            }
        }
    }

    override fun onBindViewHolder(holder: RailItemHolder, position: Int) {
        val destination = destinations[position]
        val isSelected = destination.id == selectedId

        holder.label.text = getString(holder.itemView.context, destination.labelRes)
        val icon = getDrawable(holder.itemView.context, destination.iconRes)
        if (icon != null) {
            val context = holder.itemView.context
            val tintRes = if (isSelected) R.color.md_theme_primary
                          else R.color.md_theme_onSurfaceVariant
            val tint = getColor(context, tintRes)
            icon.setTint(tint)
        }
        holder.icon.setImageDrawable(icon)

        val textColorRes = if (isSelected) R.color.md_theme_primary
                           else R.color.md_theme_onSurfaceVariant
        holder.label.setTextColor(getColor(holder.itemView.context, textColorRes))

        val bgRes = if (isSelected) R.drawable.bg_tv_rail_item_selected
                    else R.drawable.bg_tv_rail_item
        holder.root.setBackgroundResource(bgRes)
    }

    override fun getItemCount(): Int = destinations.size

    fun setSelectedDestination(id: Int) {
        if (id == selectedId) return
        val oldIndex = destinations.indexOfFirst { it.id == selectedId }
        val newIndex = destinations.indexOfFirst { it.id == id }
        if (oldIndex >= 0 && newIndex >= 0) {
            selectedId = id
            notifyItemChanged(oldIndex)
            notifyItemChanged(newIndex)
        }
    }
}
