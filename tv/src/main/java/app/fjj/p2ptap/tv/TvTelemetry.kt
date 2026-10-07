package app.fjj.p2ptap.tv

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.service.StatsSnapshot
import app.fjj.p2ptap.service.StatsSnapshotParser
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * The one thing every TV screen needs besides observing the repository: a
 * peer-level snapshot, which the engine does not push and which therefore has
 * to be pulled.
 *
 * It lives here rather than in each Activity because two screens polling at
 * once would each hold the engine mutex for the whole of getStatsJSON and the
 * second would time out and discard its own result. Home, Peers and Diagnostics
 * all need the same numbers, so exactly one job runs for the whole app.
 *
 * Everything is guarded on the RUNNING state because getStatsJSON returns the
 * literal "{}" when no engine is up, and parsing that throws.
 */
object TvTelemetry {

    /** Cadence matches the phone build's MainViewModel: fast enough to feel live. */
    private const val CADENCE_MS = 1_500L

    private var job: Job? = null

    /**
     * Run the poll from whichever screen is showing.
     *
     * Cheap to call repeatedly and safe to call from several activities: the
     * previous job is cancelled before a new one is started, so at most one
     * pull is ever in flight.
     *
     * The pull runs on [Dispatchers.Default] rather than the main thread: the
     * JNI call can block for up to PULL_TIMEOUT_MS when the engine is wedged,
     * and blocking the UI thread during that window is an ANR. The repository
     * writes are thread-safe and are also dispatched to Default, so no main-
     * thread hop is needed.
     */
    @Synchronized
    fun start(owner: LifecycleOwner): Job {
        val scope = owner.lifecycleScope
        job?.cancel()
        val newJob = scope.launch {
            while (isActive) {
                try {
                    if (P2PStateRepository.state.value == P2PTapVpnService.STATE_RUNNING) {
                        val revision = P2PStateRepository.sessionRevision
                        if (P2PStateRepository.beginRefresh(revision)) {
                            val snapshot = withContext(Dispatchers.Default) {
                                pull()
                            }
                            P2PStateRepository.finishRefresh(snapshot, revision)
                            if (snapshot != null) {
                                P2PStateRepository.markSnapshotFresh(System.currentTimeMillis())
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Never let a failed pull kill the poll loop. The next tick
                    // retries, and the repository keeps showing the last good
                    // numbers — which is the right thing for a viewer to see.
                }
                delay(CADENCE_MS)
            }
        }
        job = newJob
        return newJob
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Pull a snapshot over JNI, bounded.
     *
     * The timeout bounds the *wait*, not the JNI call itself — native calls
     * cannot be interrupted mid-flight. If the engine is wedged the call
     * blocks until it answers and the repository keeps showing the last good
     * numbers, which is the right thing for a viewer to see rather than a
     * screen that says "loading" forever.
     *
     * Parsing is wrapped in runCatching: getStatsJSON returns "{}" when no
     * engine is up, and StatsSnapshotParser.parse() throws on that. During
     * the auto-recovery restart window the engine can be down momentarily, so
     * an uncaught throw would kill the poll loop permanently.
     */
    suspend fun pull(): StatsSnapshot? {
        val json: String? = withTimeoutOrNull(P2PTapVpnService.PULL_TIMEOUT_MS) {
            P2PTap.getStatsJSON()
        }
        return runCatching { json?.let { StatsSnapshotParser.parse(it) } }.getOrNull()
    }
}
