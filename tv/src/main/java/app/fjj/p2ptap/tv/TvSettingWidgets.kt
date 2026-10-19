package app.fjj.p2ptap.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.fjj.p2ptap.tv.databinding.ItemTvGroupBinding
import app.fjj.p2ptap.tv.databinding.ItemTvToggleChipBinding

/**
 * The settings screen is made of groups, and nothing else. A group is one
 * card holding a title and a row of chips, and a chip is one boolean setting.
 *
 * Both ideas come from one constraint. A TV has no scrolling, so twelve
 * settings have to be readable in a single pass; and a remote can only press a
 * view or move between views, so every change on the screen is a press on
 * something that flips. There is no dropdown, no scrollbar and no text field,
 * so nothing else is needed.
 */

/** One boolean setting, described by how to read and write it in the config. */
class Toggle(
    val labelRes: Int,
    /**
     * Whether this is one of the transports that carry traffic. A node with no
     * transports enabled cannot reach anyone, so the last one standing is
     * shown but locked, rather than allowed to be turned off.
     */
    val transport: Boolean = false,
    private val getter: () -> Boolean,
    private val setter: (Boolean) -> Unit,
    /**
     * False while another setting makes this one moot. The relay children are
     * moot while the relay umbrella is off, because the umbrella forces both of
     * them off regardless of their own values.
     */
    val enabled: () -> Boolean = { true },
) {
    fun value(): Boolean = getter()
    fun set(next: Boolean) = setter(next)
}

/**
 * A named group of toggles, painted as one card holding a row of chips.
 *
 * [wide] asks for the full-width slot above the two-column grid. One group
 * needs it: the relay group's circuit-relay label runs to 23 characters in
 * Russian, and no narrow card can hold it in one line.
 */
class ToggleGroup(
    val titleRes: Int,
    val toggles: List<Toggle>,
    val wide: Boolean = false,
)

/**
 * One chip, kept after it is built so a repaint can update it in place.
 *
 * Repainting in place rather than rebuilding the views is what keeps focus
 * where the viewer put it. Re-inflating a chip after every press drops focus
 * back to the first chip in the column, which reads as the press being
 * rejected.
 */
class Chip(internal val binding: ItemTvToggleChipBinding, val toggle: Toggle) {

    val root: View get() = binding.root

    fun paint(locked: Boolean) {
        val context = binding.root.context
        binding.tvToggleChipLabel.text = context.getString(toggle.labelRes)
        val live = toggle.enabled()
        binding.tvToggleChipState.text =
            context.getString(
                if (toggle.value()) R.string.tv_toggle_on else R.string.tv_toggle_off
            )
        binding.tvToggleChipState.isSelected = toggle.value()
        // Moot rather than disabled: the chip keeps its place in the focus path
        // so the pointer never vanishes out from under the viewer, but it reads
        // as "recorded, not in effect" while the setting above it wins.
        binding.tvToggleChipLabel.alpha = if (live) 1f else MUTED_ALPHA
        binding.tvToggleChipState.alpha = if (live) 1f else MUTED_ALPHA
        binding.root.isEnabled = !locked
    }

    private companion object {
        const val MUTED_ALPHA = 0.45f
    }
}

/**
 * Paint one group card and hand back its chips. The chips are built once and
 * then repainted, so the caller keeps them and calls [Chip.paint] after every
 * change instead of re-inflating.
 */
fun ItemTvGroupBinding.bindGroup(
    group: ToggleGroup,
    onToggle: (Toggle) -> Unit,
): List<Chip> {
    val context = root.context
    tvGroupTitle.text = context.getString(group.titleRes)
    tvGroupChips.removeAllViews()
    return group.toggles.mapIndexed { index, toggle ->
        val chip = ItemTvToggleChipBinding.inflate(
            LayoutInflater.from(context), tvGroupChips, false
        )
        // wrap_content under weight one: the chip takes its label's width first,
        // then shares the leftover space. A 0dp width splits the row into equal
        // quarters, which is not wide enough for the longest labels.
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f,
        )
        if (index > 0) {
            params.marginStart = context.resources.getDimensionPixelSize(R.dimen.tv_gap_s)
        }
        tvGroupChips.addView(chip.root, params)
        val painted = Chip(chip, toggle)
        chip.root.setOnClickListener { onToggle(toggle) }
        painted
    }
}
