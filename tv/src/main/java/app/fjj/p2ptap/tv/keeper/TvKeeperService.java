package app.fjj.p2ptap.tv.keeper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.Keep;

/**
 * L3 keep-alive, the half that runs as shell UID.
 *
 * Shizuku hosts this in a process of its own, as the shell Linux UID (2000),
 * and with daemon mode on it outlives this app's process. That is the one thing
 * no in-app code can achieve: a watcher that is still standing when this app is
 * gone. {@code TvShellKeeper} is the only caller and it is always in this app,
 * so the trust boundary here is this app's own code.
 *
 * <p>This class extends the AIDL stub and nothing else. It is deliberately not
 * an {@code android.app.Service}: the Shizuku server side instantiates it via
 * {@code serviceClass.getConstructor(Context.class).newInstance(application)},
 * where {@code application} is a {@link Context} built with
 * {@code createPackageContextAsUser} and no activity thread is attached. Both
 * Java's {@code implements} and Kotlin's single-class-supertype rule already
 * forbid declaring it as a Service anyway (the AIDL {@code Stub} is a class,
 * not an interface), so keeping the shape honest costs nothing.
 *
 * <p>The reason this file is Java and not Kotlin: Kotlin's constructor
 * delegation chain refuses two {@code this(...)} delegations from a private
 * primary when one of the secondary constructors reuses the primary's
 * parameter name — even with a renamed primary parameter, the compiler reports
 * a cycle. Java has no such rule; the Shizuku demo itself is Java for the same
 * class, and there is no reason not to match that shape.
 *
 * <p>What this service deliberately does NOT do:
 *
 * <ul>
 * <li>Read this app's SharedPreferences. It runs under a different Linux uid,
 * so assuming the app's data is readable here would silently degrade the whole
 * chain to a no-op. It sends one broadcast and reads nothing.
 *
 * <li>Hold policy. "Should the tunnel be up" is answered by
 * {@code TvTunnelRestore} in the app process, which can see the boot flag and
 * the live tunnel. If the keeper answered it, a box whose app was force-stopped
 * would resurrect a tunnel the user had switched off.
 *
 * <li>Exit any process it does not own. See {@link #destroy()}.
 * </ul>
 */
public final class TvKeeperService extends ITvKeeperService.Stub {

    private static final String TAG = "TvKeeperService";

    /**
     * Received by {@code TvKeeperReceiver} in the app process. The action is
     * arbitrary and private; the receiver is exported, which is required because
     * the sender holds a different Linux uid and the broadcast framework
     * therefore treats it as a different package.
     *
     * <p>That export is a benign surface rather than a hole to lock down: the
     * receiver re-derives the decision from the boot flag and the live tunnel,
     * so a third party can at most re-enable a tunnel the user already wanted
     * on. A signature permission would not help anyway — the sender holds shell
     * uid, not this app's signing key.
     */
    public static final String ACTION_KEEPER_RESTORE =
            "app.fjj.p2ptap.tv.action.KEEPER_RESTORE";

    /**
     * Kept as a string rather than a Class literal so the keeper stays correct
     * even if its host process resolves our classes lazily. Keep it in sync with
     * the receiver's class name: a mismatch is invisible until a box restarts
     * and Shizuku tries to restore a tunnel.
     */
    private static final String RECEIVER_CLASS_SIMPLE_NAME = "TvKeeperReceiver";

    public static volatile boolean binderAlive = false;

    private final Context context;

    /**
     * The no-arg instantiation path Shizuku falls back to when it cannot find
     * the Context-taking constructor. Every method that needs a Context degrades
     * to a logged no-op, which is the correct failure for a keeper whose one job
     * is to outlive failures rather than crash out of them.
     *
     * <p>{@code binderAlive} is set before any log so a client polling it
     * observes the keeper as up the instant the class is constructed.
     */
    public TvKeeperService() {
        super();
        this.context = null;
        binderAlive = true;
        Log.i(TAG, "Keeper constructed without a context");
    }

    /**
     * The instantiation path Shizuku v13 actually prefers. The context it
     * passes in comes from {@code createPackageContextAsUser}, which is safe to
     * use for {@code sendBroadcast} and {@code getPackageName} and nothing more.
     *
     * <p>The {@code @Keep} annotation matters: R8 has no reason to keep a
     * constructor it can never see called, and without this surviving the server
     * silently falls back to the no-arg branch. That branch is invisible until a
     * box reboots and Shizuku tries to restore a tunnel.
     */
    @Keep
    public TvKeeperService(Context context) {
        super();
        this.context = context;
        binderAlive = true;
        Log.i(TAG, "Keeper constructed with context: " + context.getPackageName());
    }

    /**
     * A cheap round trip whose return value proves the binder is live. Nothing
     * else reads the value.
     *
     * <p>Not named {@code pingBinder}: the generated stub extends
     * {@link android.os.Binder}, which already declares {@code pingBinder()}
     * returning boolean, and an int override with the same name will not
     * compile.
     */
    @Override
    public int isAlive() {
        return 0;
    }

    /**
     * Ask this app to bring the tunnel back. The keeper is a dumb trigger and
     * holds no policy of its own — it cannot know whether the user wanted the
     * tunnel on, whether the VPN service is reachable from shell, or whether the
     * key file is intact. All of that lives on the receiving end, which reads
     * the boot flag and decides.
     *
     * <p>Not oneway: the caller wants to know the trigger took, and this app's
     * restore is a broadcast dispatch that completes immediately.
     */
    @Override
    public void restoreTunnel() {
        Context ctx = context;
        if (ctx == null) {
            Log.w(TAG, "restoreTunnel: no context; the tunnel is not restored");
            return;
        }
        String pkg = ctx.getPackageName();
        ComponentName target = new ComponentName(ctx, pkg + "." + RECEIVER_CLASS_SIMPLE_NAME);
        Intent intent = new Intent(ACTION_KEEPER_RESTORE);
        intent.setComponent(target);
        intent.setPackage(pkg);
        try {
            ctx.sendBroadcast(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "restoreTunnel broadcast failed", e);
        }
        Log.i(TAG, "restoreTunnel: broadcast dispatched to " + target);
    }

    /**
     * Reserved tear-down call declared in the AIDL with its fixed transaction id.
     * Shizuku addresses it by that id, so declaring it on the interface is what
     * makes it arrive as a normal override rather than needing an
     * {@code onTransact} catch-all.
     *
     * <p>{@code System.exit} is the documented convention and is safe here
     * specifically because this class is not in this app's process: the exit
     * ends only the keeper process Shizuku already decided to end. The same call
     * in any other service of this app would kill the app, which is why destroy
     * handling belongs here and nowhere else.
     */
    @Override
    public void destroy() {
        binderAlive = false;
        Log.i(TAG, "destroy called; exiting keeper process");
        System.exit(0);
    }
}
