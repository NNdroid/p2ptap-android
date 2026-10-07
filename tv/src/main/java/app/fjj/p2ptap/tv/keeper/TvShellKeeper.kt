package app.fjj.p2ptap.tv.keeper

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuApiConstants

/**
 * The client half of L3. Owns exactly two mutable values — the args and the
 * connection — because [Shizuku.unbindUserService] takes both back, and
 * dropping either turns the unbind into a silent no-op that leaks the keeper
 * process forever. That is the reason this class exists at all rather than the
 * keep-alive screen calling Shizuku directly.
 *
 * Everything here is best-effort and returns rather than throws. This screen
 * must never crash the app to report that Shizuku is missing; a box without
 * Shizuku is a supported configuration, and the keep-alive screen exists to say
 * exactly that.
 */
object TvShellKeeper {

    /**
     * Bump whenever TvKeeperService changes shape. Shizuku keys user services
     * on this value, so a stale keeper process left over from an older APK
     * cannot serve the newer AIDL and quietly return wrong answers.
     */
    const val SERVICE_VERSION = 1

    @Volatile private var args: Shizuku.UserServiceArgs? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var keeper: ITvKeeperService? = null

    /** Is Shizuku running at all? This is the gate every other call checks. */
    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun serviceVersion(): Int = ShizukuApiConstants.SERVER_VERSION

    /**
     * Shizuku's own patch version is only reachable through
     * [Shizuku.getServerPatchVersion], which is annotated
     * `@RestrictTo(Scope.LIBRARY_GROUP_PREFIX)` — Shizuku reserves that call
     * for its own library group. We deliberately do not call it: the compile-time
     * major version from [serviceVersion] is what tells a viewer whether the
     * installed Shizuku is compatible, and asking for the patch level would
     * mean taking on a lint exception to display a cosmetic third component.
     */

    fun uid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun selinuxContext(): String = runCatching { Shizuku.getSELinuxContext() }.getOrNull().orEmpty()

    fun keeperBinder(): ITvKeeperService? = keeper

    /**
     * @return an empty string on success, or the reason the bind did not happen.
     *         The reason is shown verbatim on the keep-alive screen, so it is
     *         written for a viewer rather than for a log.
     */
    fun bind(context: Context): String {
        if (!shizukuRunning()) return "shizuku-offline"

        val builtArgs = Shizuku.UserServiceArgs(
            ComponentName(context, TvKeeperService::class.java)
        )
            .daemon(true)
            .debuggable(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .version(SERVICE_VERSION)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                // Wrapping is required: the raw binder Shizuku hands over is not
                // yet safe for transact from this side. This is the one line in
                // the whole keeper chain whose omission does not fail loudly, so
                // it is kept as close to the binder as possible.
                keeper = ITvKeeperService.Stub.asInterface(ShizukuBinderWrapper(binder))
                TvKeeperService.binderAlive = true
                Log.i(TAG, "Keeper bound")
            }

            override fun onServiceDisconnected(name: ComponentName) {
                keeper = null
                TvKeeperService.binderAlive = false
                Log.w(TAG, "Keeper binder died; rebind will recreate it")
            }
        }

        args = builtArgs
        connection = conn
        try {
            Shizuku.bindUserService(builtArgs, conn)
        } catch (e: Exception) {
            // A bind failure must not leave the caller believing the keeper is
            // live. Clear both so the next attempt starts clean.
            Log.e(TAG, "bindUserService failed", e)
            args = null
            connection = null
            keeper = null
            return "bind-failed"
        }
        return ""
    }

    /**
     * Stop the keeper. [force] unbinds even if Shizuku says it is still active;
     * the screen passes false on the normal path and only escalates if the first
     * attempt does not take.
     */
    fun unbind(force: Boolean = false) {
        val a = args ?: return
        val c = connection ?: return
        try {
            Shizuku.unbindUserService(a, c, force)
        } catch (e: Exception) {
            Log.w(TAG, "unbindUserService failed", e)
        } finally {
            args = null
            connection = null
            keeper = null
            TvKeeperService.binderAlive = false
        }
    }

    /**
     * Ask shell uid to bring the tunnel back. This is the actual payoff for
     * binding in the first place, so it returns a boolean rather than a status
     * string: the caller wants to know whether the broadcast went out.
     */
    fun restoreTunnel(): Boolean {
        val keeperRef = keeper
        if (keeperRef == null) return false
        return runCatching {
            keeperRef.restoreTunnel()
            true
        }.getOrElse {
            Log.w(TAG, "restoreTunnel call failed", it)
            false
        }
    }

    private const val TAG = "TvShellKeeper"
}
