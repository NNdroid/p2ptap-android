package app.fjj.p2ptap.ui

import android.view.WindowInsets
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Keep the entire sheet scrollable on small screens and with large fonts. */
open class LiveDataSheet : BottomSheetDialogFragment() {
    override fun onStart() {
        super.onStart()
        val sheetDialog = dialog as? BottomSheetDialog ?: return
        val sheet = sheetDialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val metrics = requireActivity().windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        val height = ((metrics.bounds.height() - insets.top - insets.bottom) * 0.9).toInt()
        sheet.layoutParams = sheet.layoutParams.apply { this.height = height }
        sheetDialog.behavior.apply {
            maxHeight = height
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }
}
