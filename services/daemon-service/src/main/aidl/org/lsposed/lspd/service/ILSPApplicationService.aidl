package org.lsposed.lspd.service;

import org.lsposed.lspd.models.Module;

interface ILSPApplicationService {
    /**
     * The code a process' hot reload endpoint answers to (API 102), carrying the module package
     * name and the extras to pass along.
     */
    const int HOT_RELOAD_TRANSACTION_CODE = 1598575180;

    /** The reload has been asked for and has not reported back yet. */
    const int HOT_RELOAD_IN_PROGRESS = 0;
    const int HOT_RELOAD_SUCCEEDED = 1;
    /** The old generation said no, or there was nothing left in it that could agree. */
    const int HOT_RELOAD_REFUSED = 2;
    /** The swap itself threw. The old generation is the one still running. */
    const int HOT_RELOAD_FAILED = 3;
    /** This process does not have the module, or could not find it to reload. */
    const int HOT_RELOAD_NOT_LOADED = 4;

    boolean isLogMuted();

    List<Module> getLegacyModulesList();

    List<Module> getModulesList();

    String getPrefsPath(String packageName);

    ParcelFileDescriptor requestInjectedManagerBinder(out List<IBinder> binder);

    /**
     * Reports the outcome of a hot reload the daemon asked this process for (API 102).
     *
     * <p>The request is oneway and the daemon has no deadline over the module code the process runs
     * in answer to it, so the outcome comes back here rather than in a reply. Without it a refused
     * or failed reload looks exactly like a successful one from the daemon's side.</p>
     */
    void reportHotReloadResult(String packageName, int status, String message);

    /**
     * Offers the daemon the binder it can ask this process to reload a module on (API 102).
     *
     * <p>A process has to offer one. The heartbeat the daemon already holds is a stock
     * android.os.Binder the native side creates before the framework dex exists, and the hook that
     * would route anything else sent to it is installed in system_server alone - so a request aimed
     * at any other process is dropped, silently.</p>
     */
    void registerHotReloadEndpoint(IBinder endpoint);
}
