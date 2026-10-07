package app.fjj.p2ptap.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.tv.databinding.ActivityTvAboutBinding
import app.fjj.p2ptap.tv.databinding.ItemTvAboutBinding
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * S7: what this set is, and what it is connected as.
 *
 * Everything here is a read of facts, so nothing on the screen is an action.
 * That is deliberate: a TV has no keyboard to type an address into a browser,
 * and most sets ship no browser at all to receive an ACTION_VIEW. An identity
 * the viewer needs to speak aloud is shown as text and nothing else.
 *
 * The rows are static, so the screen renders placeholders first and fills them
 * in when the reads come back. [AppConfigManager.getPeerId] touches JNI and
 * disk, and a set-top box CPU is slow enough that doing either on the main
 * thread would show as a frozen list.
 */
class TvAboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvAboutBinding
    private val adapter = AboutAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvAboutGrid.apply {
            adapter = this@TvAboutActivity.adapter
            setNumColumns(1)
        }

        render(buildRows(Identity()))
        loadIdentity()
        binding.tvAboutGrid.requestFocus()
    }

    /** Everything this screen reports, gathered in one off-main-thread pass. */
    private class Identity(
        val appVersion: String = "",
        val engineVersion: String = "",
        val peerId: String = "",
        val nodeName: String = "",
        val tapIpv4: String = "",
        val tapIpv6: String = "",
        val dnsServers: String = ""
    )

    private fun loadIdentity() {
        lifecycleScope.launch(Dispatchers.Default) {
            // One config load covers name, addresses and DNS. It is kept
            // separate from the peer id, because the peer id comes from the
            // node key on disk and survives a config being replaced.
            val config = runCatching { AppConfigManager.load(this@TvAboutActivity) }.getOrNull()

            // The stored id wins: it answers the question "what am I to the
            // mesh" even while the VPN is off. The engine id is only the
            // fallback, because it needs a running tunnel.
            var peerId = runCatching { AppConfigManager.getPeerId(this@TvAboutActivity) }
                .getOrNull().orEmpty().trim()
            if (peerId.isEmpty()) {
                peerId = runCatching { P2PTap.getPeerID() }.getOrNull().orEmpty().trim()
            }

            val engineVersion = runCatching { P2PTap.version() }.getOrNull().orEmpty().trim()
            val appVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().trim()
            }.getOrNull().orEmpty()
            val dnsServers = config?.dnsServers.orEmpty()
                .filter { it.trim().isNotBlank() }.joinToString(", ").trim()

            val identity = Identity(
                appVersion = appVersion,
                engineVersion = engineVersion,
                peerId = peerId,
                nodeName = config?.nodeName.orEmpty().trim(),
                tapIpv4 = config?.tapIp.orEmpty().trim(),
                tapIpv6 = config?.tapIpv6.orEmpty().trim(),
                dnsServers = dnsServers
            )
            launch(Dispatchers.Main) { render(buildRows(identity)) }
        }
    }

    private fun render(rows: List<AboutRow>) = adapter.submit(rows)

    private fun buildRows(i: Identity): List<AboutRow> {
        val versions = listOfNotNull(
            i.appVersion.ifBlank { null }?.let { getString(R.string.tv_version_app_fmt, it) },
            i.engineVersion.ifBlank { null }?.let { getString(R.string.tv_version_engine_fmt, it) }
        ).joinToString(" · ")
        val tunnel = listOf(i.tapIpv4, i.tapIpv6).filter { it.isNotEmpty() }.joinToString(" · ")

        return listOf(
            AboutRow(R.string.tv_about_versions, versions.ifBlank { getString(R.string.tv_unknown) }),
            // Shown in full: the peer id is the one string a viewer will read
            // aloud to another operator, and truncating it defeats the point.
            AboutRow(R.string.tv_peer_id, i.peerId.ifBlank { getString(R.string.tv_unknown) }),
            AboutRow(
                R.string.tv_about_tunnel,
                tunnel.ifBlank { getString(R.string.tv_addresses_none) }
            ),
            AboutRow(R.string.tv_about_node_name, i.nodeName.ifBlank { getString(R.string.tv_unknown) }),
            AboutRow(R.string.tv_about_dns, i.dnsServers.ifBlank { getString(R.string.tv_unknown) }),
            AboutRow(R.string.tv_about_repository, getString(R.string.tv_about_repo_url))
        )
    }
}

/** One labelled fact, pre-formatted so the binding stays trivial. */
private data class AboutRow(val labelRes: Int, val value: String)

private class AboutAdapter : RecyclerView.Adapter<AboutAdapter.Row>() {

    private var rows: List<AboutRow> = emptyList()

    fun submit(newRows: List<AboutRow>) {
        if (newRows == rows) return
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val inflater = LayoutInflater.from(parent.context)
        return Row(ItemTvAboutBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: Row, position: Int) = holder.bind(rows[position])

    override fun getItemCount(): Int = rows.size

    class Row(private val binding: ItemTvAboutBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: AboutRow) {
            val context = binding.root.context
            binding.tvAboutLabel.text = context.getString(row.labelRes)
            binding.tvAboutValue.text = row.value
        }
    }
}
