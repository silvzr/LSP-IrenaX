package org.lsposed.lspd.impl;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import org.lsposed.lspd.core.ApplicationServiceClient;
import org.lsposed.lspd.service.ILSPApplicationService;

/**
 * The binder a process offers the daemon so that it can be asked to reload a module (API 102).
 *
 * <p>The daemon already holds a binder for every injected process - the heartbeat that process
 * registers - but that one can only report death. The native side creates it before this dex
 * exists and makes it a stock {@link Binder}, so a transaction carrying anything else ends up in
 * the base {@code onTransact} and is dropped: the hook that would route it somewhere useful is
 * installed in system_server alone. A module can be updated while a process is running it, though,
 * and that process is the only one that can retire the code it is executing, so it has to offer a
 * way in itself, once, when it comes up.</p>
 *
 * <p>The daemon addresses this binder with {@link ILSPApplicationService#HOT_RELOAD_TRANSACTION_CODE},
 * passing the module package name and the extras to hand along. The reply is not where the answer
 * is: the request is oneway because the module code it runs has no deadline, and the process
 * reports the outcome through {@code ILSPApplicationService#reportHotReloadResult}.</p>
 */
public final class HotReloadEndpoint extends Binder {

    private static final String TAG = "HotReloadEndpoint";

    /**
     * Kept on purpose. The binder the daemon holds is only reachable through this reference, and a
     * collected endpoint is a process the daemon can no longer reach.
     */
    private static volatile HotReloadEndpoint endpoint;

    /**
     * Offers this process's endpoint to the daemon, which needs an application service to offer it
     * on. Called when the process comes up; calling it again is harmless.
     */
    public static void register() {
        var client = ApplicationServiceClient.serviceClient;
        if (client == null) return;
        var instance = endpoint;
        if (instance == null) {
            synchronized (HotReloadEndpoint.class) {
                if (endpoint == null) endpoint = new HotReloadEndpoint();
                instance = endpoint;
            }
        }
        try {
            client.registerHotReloadEndpoint(instance);
        } catch (Throwable t) {
            // Not fatal: the process runs, it just cannot be handed a new build of a module it is
            // already running.
            Log.w(TAG, "Cannot offer the hot reload endpoint", t);
        }
    }

    private HotReloadEndpoint() {
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != ILSPApplicationService.HOT_RELOAD_TRANSACTION_CODE) {
            return super.onTransact(code, data, reply, flags);
        }
        // Root only, as on the bridge: the daemon is the only caller that knows a module's code
        // changed on disk, and a module able to ask for its own reload could retire the generation
        // the process is running out from under it.
        if (Binder.getCallingUid() != 0) {
            Log.w(TAG, "Refused a hot reload from uid " + Binder.getCallingUid());
            return true;
        }
        var packageName = data.readString();
        var extras = data.readBundle(HotReloadEndpoint.class.getClassLoader());
        var reloaded = packageName != null && LSPosedContext.requestHotReload(packageName, extras);
        if (reply != null) {
            reply.writeNoException();
            // Parcel#writeBoolean is API 29+ and the minSdk is lower; an int does the same job.
            reply.writeInt(reloaded ? 1 : 0);
        }
        return true;
    }
}
