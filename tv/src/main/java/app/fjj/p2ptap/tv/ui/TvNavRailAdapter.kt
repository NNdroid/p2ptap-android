package app.fjj.p2ptap.tv.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat.getColor
import androidx.core.content.ContextCompat.getString
import androidx.recyclerview.widget.RecyclerView
import app.fjj.p2ptap.tv.R

/**
 * Rail item adapter for the left navigation rail.
 *
 * The important part of this class is that **selection and focus are two
 * different states**, and they used to be one.
 *
 * Before: the item's fill followed `selectedId`, i.e. the destination you were
 * already on, and focus was expressed only by a 1.06x scale from
 * [TvFocusAnimation]. Moving the remote down the rail left the highlight
 * sitting on the current page while the cursor was somewhere else entirely —
 * on a dark panel the scale change was not enough to find it.
 *
 * Now: selection is the destination you are on (wash + leading accent bar),
 * focus is where the remote is pointing (3dp ring), and both can be true at
 * the same time without either one hiding the other.
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
            root.isFocusable = true
            root.isFocusableInTouchMode = true
            root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onSelect(destinations[pos])
            }
            root.setOnFocusChangeListener { v, hasFocus ->
                TvFocusAnimation.animate(
                    v,
                    if (hasFocus) TvFocusAnimation.SCALE_RAIL else TvFocusAnimation.SCALE_NORMAL,
                )
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) bindState(this, destinations[pos])
            }
        }
    }

    override fun onBindViewHolder(holder: RailItemHolder, position: Int) {
        val destination = destinations[position]
        holder.label.text = getString(holder.itemView.context, destination.labelRes)
        holder.icon.setImageResource(destination.iconRes)
        bindState(holder, destination)
    }

    /**
     * Renders the combination of focus and selection for one item. Called from
     * onBind and from the focus listener, so the two never disagree.
     */
    private fun bindState(holder: RailItemHolder, destination: TvNavDestination) {
        val ctx = holder.itemView.context
        val focused = holder.root.hasFocus()
        val selected = destination.id == selectedId
        val active = focused || selected

        holder.label.setTextColor(
            getColor(ctx, if (active) R.color.tv_rail_label_active else R.color.tv_rail_label)
        )
        holder.icon.imageTintList = ColorStateList.valueOf(
            getColor(ctx, if (active) R.color.md_theme_primary else R.color.md_theme_onSurfaceVariant)
        )
        holder.root.setBackgroundResource(
            when {
                focused -> R.drawable.bg_tv_rail_item_focus
                selected -> R.drawable.bg_tv_rail_item_selected
                else -> R.drawable.bg_tv_rail_item
            }
        )
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
