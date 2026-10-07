package app.fjj.p2ptap.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.service.StatsSnapshotParser
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainViewModel : ViewModel() {
    private val refreshMutex = Mutex()
    val status = P2PStateRepository.status
    val state = P2PStateRepository.state
    val message = P2PStateRepository.message
    val metrics = P2PStateRepository.metrics
    val peers = P2PStateRepository.peers
    val telemetry = P2PStateRepository.telemetry

    fun refreshPeers() {
        viewModelScope.launch { refreshStats() }
    }

    /**
     * Telemetry is polled by the UI and also pulled on every onResume, which
     * used to mean two getStatsJSON round-trips plus a full JSON parse within a
     * few hundred milliseconds. A short TTL collapses those into one: the
     * caller gets the value it asked for, and the next call inside the window
     * reuses the freshest snapshot instead of hitting the engine again.
     *
     * 1200ms is short enough that live throughput still looks continuous, and
     * long enough to absorb a resume + poll tick landing together.
     */
    private val telemetryTtlMs = 1_200L

    /**
     * P2PTap.getStatsJSON() blocks until the Go engine answers, and the engine
     * holds its mutex while a slow Start() or Stop() runs. Unbounded, that call
     * could hang this refresh indefinitely — and the caller sees nothing but
     * stale data, so a wedged engine looked exactly like a permanently frozen
     * screen. runBlocking is what lets withTimeoutOrNull wait on a plain
     * blocking call at all.
     *
     * This bounds OUR WAIT, not the JNI call itself: a native call cannot be
     * interrupted mid-flight, so a timed-out pull keeps occupying the engine's
     * mutex until it finishes on its own. That is still the right trade — the
     * UI stops hanging, and the next pull simply queues behind it.
     */
    private suspend fun pullStatsJSON(): String? {
        val result: String? = runBlocking {
            withTimeoutOrNull(P2PTapVpnService.PULL_TIMEOUT_MS) { P2PTap.getStatsJSON() }
        }
        if (result == null) {
            Log.w("MainViewModel", "Telemetry pull timed out after ${P2PTapVpnService.PULL_TIMEOUT_MS}ms")
        }
        return result
    }

    suspend fun refreshStats(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (P2PStateRepository.telemetryFresh(now, telemetryTtlMs)) return@withContext true

        val revision = P2PStateRepository.sessionRevision
        refreshMutex.withLock {
            try {
                if (!P2PStateRepository.beginRefresh(revision)) return@withLock false
                // A null pull means the engine did not answer in time. Report it
                // as a failed refresh instead of pretending the previous numbers
                // are still current.
                val snapshot = pullStatsJSON()?.let { StatsSnapshotParser.parse(it) }
                val applied = P2PStateRepository.finishRefresh(snapshot, revision)
                if (applied) P2PStateRepository.markSnapshotFresh(System.currentTimeMillis())
                applied
            } catch (cancelled: CancellationException) {
                P2PStateRepository.finishRefresh(null, revision)
                throw cancelled
            } catch (error: Exception) {
                Log.w("MainViewModel", "Unable to refresh Go telemetry", error)
                P2PStateRepository.finishRefresh(null, revision)
                false
            }
        }
    }
}
