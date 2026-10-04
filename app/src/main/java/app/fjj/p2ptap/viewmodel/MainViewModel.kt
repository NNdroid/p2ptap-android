package app.fjj.p2ptap.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.StatsSnapshotParser
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    suspend fun refreshStats(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (P2PStateRepository.telemetryFresh(now, telemetryTtlMs)) return@withContext true

        val revision = P2PStateRepository.sessionRevision
        refreshMutex.withLock {
            try {
                if (!P2PStateRepository.beginRefresh(revision)) return@withLock false
                val snapshot = StatsSnapshotParser.parse(P2PTap.getStatsJSON().orEmpty())
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
