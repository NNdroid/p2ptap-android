package app.fjj.p2ptap.tv

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import app.fjj.p2ptap.tv.databinding.ItemTvTierBinding
import app.fjj.p2ptap.tv.databinding.ItemTvTierChipBinding
import app.fjj.p2ptap.tv.databinding.ItemTvToggleBinding
import java.util.Locale

/**
 * The two controls a TV settings screen is made of, and nothing else: a toggle
 * and a tier.
 *
 * Both are built around one idea. A remote can press a view and move between
 * views, and it cannot do anything more — no dropdown, no scrollbar, no text
 * field. So every change on these screens is either a press on something that
 * flips, or a press on one member of a small closed set.
 */

/** One boolean setting, described by how to read and write it in the config. */
class Toggle(
    val labelRes: Int,
    val iconRes: Int,
    /**
     * Whether this is one of the transports that carry traffic. A node with no
     * transports enabled cannot reach anyone, so the last one standing is
     * shown but locked, rather than allowed to be turned off.
     */
    val transport: Boolean,
    private val getter: () -> Boolean,
    private val setter: (Boolean) -> Unit
) {
    fun value(): Boolean = getter()
    fun set(next: Boolean) = setter(next)
}

/**
 * Paint one toggle card. [locked] is the "this is the last transport enabled"
 * case: the card is drawn dimmed and removed from the focus path, which is the
 * honest way to say "on, and not changeable from here".
 */
fun ItemTvToggleBinding.bindToggle(item: Toggle, locked: Boolean, onToggle: () -> Unit) {
    val context = root.context
    tvToggleIcon.setImageResource(item.iconRes)
    tvToggleLabel.text = context.getString(item.labelRes)
    val value = item.value()
    tvToggleState.text = context.getString(
        if (value) R.string.tv_toggle_on else R.string.tv_toggle_off
    )
    tvToggleState.isSelected = value
    root.isSelected = value
    root.isEnabled = !locked
    root.setOnClickListener { onToggle() }
}

class ToggleAdapter(
    private val transportCount: () -> Int,
    private val onToggle: (Toggle) -> Unit
) : RecyclerView.Adapter<ToggleAdapter.Row>() {

    private var items: List<Toggle> = emptyList()

    fun submit(newItems: List<Toggle>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val inflater = LayoutInflater.from(parent.context)
        return Row(ItemTvToggleBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: Row, position: Int) {
        val item = items[position]
        val locked = item.transport && item.value() && transportCount() <= 1
        holder.bind(item, locked, { onToggle(item) })
    }

    override fun getItemCount(): Int = items.size

    class Row(private val binding: ItemTvToggleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Toggle, locked: Boolean, onToggle: () -> Unit) {
            binding.bindToggle(item, locked, onToggle)
        }
    }
}

/**
 * A setting with a small closed set of values, shown as a row of chips.
 *
 * The values are the engine's own strings, unmapped and unlocalised, because
 * what the viewer picks is what the config records, and a relabelled copy
 * would drift from it. They are shown upper case, which is only display.
 *
 * [enabled] is false while a tier is moot, for instance the obfuscation mode
 * while obfuscation itself is off. The chips stay on screen dimmed, because
 * removing them would move the focus target out from under the viewer.
 */
class TierModel(
    val labelRes: Int,
    val options: List<String>,
    var current: String = options.first(),
    var enabled: Boolean = true,
    private val onChange: (String) -> Unit
) {

    /** Index of the current value, or negative one when it is not an option. */
    val selectedIndex: Int get() = options.indexOf(current)

    fun pick(index: Int, onRepaint: () -> Unit) {
        if (index < 0 || index >= options.size) return
        if (options[index] == current) return
        current = options[index]
        onChange(current)
        onRepaint()
    }

    fun render(view: ItemTvTierBinding, onRepaint: () -> Unit) {
        val context = view.root.context
        view.tvTierLabel.text = context.getString(labelRes)
        val chips = view.tvTierChips
        chips.removeAllViews()

        options.forEachIndexed { index, option ->
            val chipBinding = ItemTvTierChipBinding.inflate(
                LayoutInflater.from(context), chips, false
            )
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (index > 0) {
                params.marginStart = context.resources.getDimensionPixelSize(R.dimen.tv_gap_s)
            }
            chips.addView(chipBinding.root, params)

            val chip = chipBinding.tvTierValue
            chip.text = option.uppercase(Locale.ROOT)
            chip.isSelected = index == selectedIndex
            chip.isEnabled = enabled
            chip.alpha = if (enabled) 1f else DISABLED_ALPHA
            chip.setOnClickListener { pick(index, onRepaint) }
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.35f
    }
}
