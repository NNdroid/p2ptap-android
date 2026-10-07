package app.fjj.p2ptap.tv.keeper;

/**
 * Binder exposed by {@link app.fjj.p2ptap.tv.keeper.TvKeeperService}.
 *
 * Shizuku instantiates that class in a process of its own, under the shell Linux
 * uid (2000), so every method here is called from across both a process and a uid
 * boundary. With daemon mode on it outlives this app's process, which is the only
 * part of the keep-alive chain that no in-app code can provide.
 *
 * The surface is deliberately three methods. Two of them are ours; the third is
 * Shizuku's own tear-down call, which has to be declared here with its fixed
 * transaction id or Shizuku cannot address it at all.
 *
 * Write-only from this app's side. There is no reverse path: the keeper never
 * calls back into this app, it broadcasts. That matters, because a callback
 * would mean this app has to be reachable from shell uid at any moment, which is
 * a different and larger trust decision.
 */
interface ITvKeeperService {

    /**
     * Reserved tear-down method defined by the Shizuku server.
     *
     * The id is fixed by Shizuku and is not negotiable: the server sends exactly
     * this transaction, so any other value means destroy never arrives and the
     * keeper process leaks for the life of the box.
     */
    void destroy() = 16777114;

    /**
     * A cheap round trip whose return value proves the binder is live. Nothing
     * else reads the value.
     *
     * Not named pingBinder: the generated stub extends android.os.Binder, which
     * already declares pingBinder() returning boolean, and an int override with
     * the same name will not compile.
     */
    int isAlive() = 1;

    /**
     * Ask this app to bring the tunnel back up.
     *
     * The keeper is a dumb trigger and holds no policy of its own. It cannot
     * know whether the user wanted the tunnel on, whether the VPN service is
     * reachable from shell, or whether the key file is intact; all of that lives
     * on the receiving end, which reads the boot flag and decides.
     *
     * Not oneway: the caller wants to know the trigger took, and this app's
     * restore is a broadcast dispatch that completes immediately.
     */
    void restoreTunnel() = 2;
}
