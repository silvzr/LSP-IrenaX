/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

package org.lsposed.lspd.service;

import static org.lsposed.lspd.service.ServiceManager.TAG;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;

import org.lsposed.lspd.models.Module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.github.libxposed.service.HookedProcess;
import io.github.libxposed.service.IHotReloadCallback;
import io.github.libxposed.service.IXposedService;

public class LSPApplicationService extends ILSPApplicationService.Stub {
    final static int DEX_TRANSACTION_CODE = 1310096052;
    final static int OBFUSCATION_MAP_TRANSACTION_CODE = 724533732;

    // key: <uid, pid>
    private final static Map<Pair<Integer, Integer>, ProcessInfo> processes = new ConcurrentHashMap<>();

    static class ProcessInfo implements DeathRecipient {
        final int uid;
        final int pid;
        final String processName;
        final IBinder heartBeat;

        /**
         * Hot reload bookkeeping per module package name (API 102).
         *
         * <p>The daemon is the only side that can keep this: it knows which build it handed the
         * process and which build it later asked it to load, while the process only ever knows the
         * code it is executing.</p>
         */
        final Map<String, Target> targets = new ConcurrentHashMap<>();

        static final class Target {
            /**
             * The build of the module this process was last handed, i.e. what it is running.
             *
             * <p>Recorded when the module list is served, which is what the process is about to
             * load; a build it then fails to load is corrected by the reload it reports.</p>
             */
            volatile String delivered;
            /** The build the daemon last asked this process to load, so one build is asked once. */
            volatile String requested;
            /** Last reported outcome, one of the {@code ILSPApplicationService.HOT_RELOAD_*} values. */
            volatile int status = ILSPApplicationService.HOT_RELOAD_IN_PROGRESS;
            volatile String message;
            /**
             * The callback a module app left behind when it asked for this reload through the
             * service (API 102), or {@code null}; consumed by the first outcome report.
             */
            volatile IHotReloadCallback serviceCallback;

            boolean runs(String build) {
                return build != null && build.equals(delivered);
            }

            boolean askedFor(String build) {
                return build != null && build.equals(requested);
            }
        }

        Target target(String packageName) {
            return targets.computeIfAbsent(packageName, k -> new Target());
        }

        /**
         * The binder the process offered for reload requests (API 102), or {@code null} while it
         * has not come up far enough to offer one.
         */
        volatile IBinder reloadEndpoint;

        ProcessInfo(int uid, int pid, String processName, IBinder heartBeat) throws RemoteException {
            this.uid = uid;
            this.pid = pid;
            this.processName = processName;
            this.heartBeat = heartBeat;
            heartBeat.linkToDeath(this, 0);
            Log.d(TAG, "register " + this);
            processes.put(new Pair<>(uid, pid), this);
        }

        @Override
        public void binderDied() {
            Log.d(TAG, this + " is dead");
            heartBeat.unlinkToDeath(this, 0);
            processes.remove(new Pair<>(uid, pid), this);
            // a module app may still be waiting on a reload this process never reported
            for (var target : targets.values()) {
                var callback = target.serviceCallback;
                target.serviceCallback = null;
                if (callback != null) notifyServiceHotReloadResult(callback, IXposedService.HOT_RELOAD_PROCESS_DIED, null);
            }
        }

        @NonNull
        @Override
        public String toString() {
            return "ProcessInfo{" +
                    "uid=" + uid +
                    ", pid=" + pid +
                    ", processName='" + processName + '\'' +
                    ", heartBeat=" + heartBeat +
                    '}';
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        Log.d(TAG, "LSPApplicationService.onTransact: code=" + code);
        switch (code) {
            case DEX_TRANSACTION_CODE: {
                var shm = ConfigManager.getInstance().getPreloadDex();
                if (shm == null) return false;
                // assume that write only a fd
                shm.writeToParcel(reply, 0);
                reply.writeLong(shm.getSize());
                return true;
            }
            case OBFUSCATION_MAP_TRANSACTION_CODE: {
                var obfuscation = ConfigManager.getInstance().dexObfuscate();
                var signatures = ObfuscationManager.getSignatures();
                reply.writeInt(signatures.size() * 2);
                for (Map.Entry<String, String> entry : signatures.entrySet()) {
                    reply.writeString(entry.getKey());
                    // return val = key if obfuscation disabled
                    reply.writeString(obfuscation ? entry.getValue() : entry.getKey());
                }
                return true;
            }
        }
        return super.onTransact(code, data, reply, flags);
    }

    public boolean registerHeartBeat(int uid, int pid, String processName, IBinder heartBeat) {
        try {
            new ProcessInfo(uid, pid, processName, heartBeat);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    private List<Module> getAllModulesList() throws RemoteException {
        var processInfo = ensureRegistered();
        var modules = modulesForProcess(processInfo);
        for (var module : modules) {
            // Serving the list is the moment the daemon hands over a build, and the closest it can
            // get to knowing what this process runs. On the next update that build is compared
            // against the one on disk, which is how processes that still need a reload - or a
            // restart, when the module never opted into reloading - are told apart from the ones
            // that are already current.
            processInfo.target(module.packageName).delivered =
                    ConfigManager.getInstance().getModuleBuild(module.packageName);
        }
        return modules;
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return !ServiceManager.getManagerService().isVerboseLog();
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        return getAllModulesList().stream().filter(m -> m.file.legacy).collect(Collectors.toList());
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        return getAllModulesList().stream().filter(m -> !m.file.legacy).collect(Collectors.toList());
    }

    @Override
    public String getPrefsPath(String packageName) throws RemoteException {
        ensureRegistered();
        return ConfigManager.getInstance().getPrefsPath(packageName, getCallingUid());
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) throws RemoteException {
        var processInfo = ensureRegistered();
        if (ServiceManager.getManagerService().postStartManager(processInfo.pid, processInfo.uid) ||
                ConfigManager.getInstance().isManager(processInfo.uid)) {
            binder.add(ServiceManager.getManagerService().obtainManagerBinder(processInfo.heartBeat, processInfo.pid, processInfo.uid));
        }
        return ConfigManager.getInstance().getManagerApk();
    }

    public boolean hasRegister(int uid, int pid) {
        return processes.containsKey(new Pair<>(uid, pid));
    }

    /**
     * What one pass of {@link #requestHotReload} found.
     */
    static final class HotReloadDispatch {
        /** Processes that were asked to load the new build. */
        int asked;
        /** Processes still running an older build than the one on disk, asked or not. */
        int stale;

        @Override
        public String toString() {
            return "asked=" + asked + ", stale=" + stale;
        }
    }

    /**
     * Asks every process that has {@code packageName} in scope to reload it (API 102).
     *
     * <p>A module is one package and one binary for the whole device, so replacing it has to reach
     * every process that is already running the old code. The daemon tracks processes rather than
     * modules, and the scope of a module is what says which of them has it loaded.</p>
     *
     * <p>An update is only offered to a module that declared {@code autoHotReload}: a reload
     * retires the generation in place, and a module that never asked for it is one that has not
     * been written to be retired. A process is asked about a given build at most once, so the
     * several broadcasts one install produces cannot reload it several times, and a refusal is not
     * retried until the code changes.</p>
     *
     * @return the pass's tally; {@link HotReloadDispatch#stale} is non-zero for a module that did
     * not opt in, which is the case a restart is the only way out of
     */
    static HotReloadDispatch requestHotReload(String packageName) {
        var dispatch = new HotReloadDispatch();
        var module = ConfigManager.getInstance().getModuleByPackage(packageName);
        var build = ConfigManager.getInstance().getModuleBuild(packageName);
        var optedIn = module != null && module.file != null && module.file.autoHotReload;

        for (var processInfo : processes.values()) {
            if (processInfo.heartBeat == null) continue;
            if (!isModuleInScope(processInfo, packageName)) continue;

            var target = processInfo.target(packageName);
            if (!target.runs(build)) dispatch.stale++;
            if (!optedIn) continue;

            if (processInfo.reloadEndpoint == null) {
                // It has the module and it ought to be reloaded, but it never offered a way in, so
                // it keeps running the build it started with.
                Log.w(TAG, processInfo.processName + " cannot be asked to reload " + packageName
                        + " and is left on the build it has");
                continue;
            }
            if (target.askedFor(build)) {
                Log.d(TAG, processInfo.processName + " was already asked for " + packageName
                        + ", last outcome: " + describe(target));
                continue;
            }
            target.requested = build;
            target.status = ILSPApplicationService.HOT_RELOAD_IN_PROGRESS;
            target.message = null;
            dispatch.asked++;
            dispatchHotReload(processInfo, packageName);
        }
        return dispatch;
    }

    /**
     * The endpoint a process offered so the daemon can ask it to reload a module (API 102).
     *
     * <p>It has to come from the process: the heartbeat the daemon tracks is a plain binder that
     * only reports death, and the hook that would route a request on it exists in system_server
     * alone.</p>
     */
    @Override
    public void registerHotReloadEndpoint(IBinder endpoint) {
        try {
            var processInfo = ensureRegistered();
            processInfo.reloadEndpoint = endpoint;
            Log.d(TAG, processInfo.processName + " offered a hot reload endpoint");
        } catch (RemoteException e) {
            Log.w(TAG, "Hot reload endpoint from an unregistered process: " + e.getMessage());
        }
    }

    /**
     * The outcome of a reload the daemon asked a process for (API 102).
     *
     * <p>The request is oneway, so this is the only way the daemon learns that a module refused, or
     * that the swap threw and the process is still running the build it started with. Until this
     * arrives the reload is assumed to be in progress; a process that dies first takes its entry in
     * {@code processes} with it, so nothing is left pending.</p>
     */
    @Override
    public void reportHotReloadResult(String packageName, int status, String message) {
        final ProcessInfo processInfo;
        try {
            processInfo = ensureRegistered();
        } catch (RemoteException e) {
            Log.w(TAG, "Hot reload result from an unregistered process: " + e.getMessage());
            return;
        }
        var target = processInfo.target(packageName);
        target.status = status;
        target.message = message;
        if (status == ILSPApplicationService.HOT_RELOAD_SUCCEEDED) {
            // The process now runs the build that was asked of it.
            target.delivered = target.requested;
        } else {
            // Nothing here knows what the process is running any more, and treating it as current
            // would hide exactly the state a restart is meant to fix.
            target.delivered = null;
        }
        var callback = target.serviceCallback;
        target.serviceCallback = null;
        if (callback != null) {
            notifyServiceHotReloadResult(callback, rawHotReloadStatus(status), message);
        }
        Log.i(TAG, processInfo.processName + " hot reload of " + packageName + ": " + describe(target));
    }

    private static String describe(ProcessInfo.Target target) {
        switch (target.status) {
            case ILSPApplicationService.HOT_RELOAD_SUCCEEDED:
                return "succeeded";
            case ILSPApplicationService.HOT_RELOAD_REFUSED:
                return "refused by the module";
            case ILSPApplicationService.HOT_RELOAD_NOT_LOADED:
                return "not loaded here";
            case ILSPApplicationService.HOT_RELOAD_FAILED:
                return target.message == null ? "failed" : "failed: " + target.message;
            default:
                return "in progress";
        }
    }

    /**
     * The modules a process would be handed if it asked now, which is not always the cached scope:
     * the system server loads its scope straight from the database, so it is not in that table.
     */
    private static List<Module> modulesForProcess(ProcessInfo processInfo) {
        if (processInfo.uid == Process.SYSTEM_UID && "system".equals(processInfo.processName)) {
            return ConfigManager.getInstance().getModulesForSystemServer();
        }
        // The manager is never handed modules, so it is never in a reload scope either - the same
        // answer the module list gives it. isRunningManager does not throw.
        if (ServiceManager.getManagerService().isRunningManager(processInfo.pid, processInfo.uid))
            return Collections.emptyList();
        return ConfigManager.getInstance().getModulesForProcess(processInfo.processName, processInfo.uid);
    }

    /**
     * The running targets of one module, as the service interface serves them (API 102). A
     * process counts when the module is in its scope; its state is read from the reload
     * bookkeeping, and a process that has not reported anything yet counts as stale.
     */
    static List<HookedProcess> getRunningTargets(String packageName) {
        var build = ConfigManager.getInstance().getModuleBuild(packageName);
        var result = new ArrayList<HookedProcess>();
        for (var processInfo : processes.values()) {
            if (processInfo.heartBeat == null) continue;
            if (!isModuleInScope(processInfo, packageName)) continue;
            var hooked = new HookedProcess();
            hooked.targetId = targetIdOf(processInfo);
            hooked.uid = processInfo.uid;
            hooked.pid = processInfo.pid;
            hooked.processName = processInfo.processName;
            hooked.state = targetState(processInfo.targets.get(packageName), build);
            result.add(hooked);
        }
        return result;
    }

    /** The opaque, process-scoped token {@code HookedProcess} hands back to module apps. */
    static long targetIdOf(ProcessInfo processInfo) {
        return ((long) processInfo.uid << 32) | (processInfo.pid & 0xFFFFFFFFL);
    }

    private static int targetState(ProcessInfo.Target target, String build) {
        if (target == null) return HookedProcess.TARGET_STATE_STALE;
        if (target.status == ILSPApplicationService.HOT_RELOAD_IN_PROGRESS) {
            return HookedProcess.TARGET_STATE_RELOADING;
        }
        if (target.status == ILSPApplicationService.HOT_RELOAD_REFUSED
                || target.status == ILSPApplicationService.HOT_RELOAD_FAILED) {
            return HookedProcess.TARGET_STATE_FAILED;
        }
        return target.runs(build) ? HookedProcess.TARGET_STATE_UP_TO_DATE : HookedProcess.TARGET_STATE_STALE;
    }

    static ProcessInfo findProcessByTargetId(long targetId) {
        for (var processInfo : processes.values()) {
            if (targetIdOf(processInfo) == targetId) return processInfo;
        }
        return null;
    }

    static boolean isModuleInScope(ProcessInfo processInfo, String packageName) {
        for (var scoped : modulesForProcess(processInfo)) {
            if (packageName.equals(scoped.packageName)) return true;
        }
        return false;
    }

    /**
     * A module app asked for its own module to be reloaded in one running target (API 102).
     * The module code still has the final word - only the update path is gated on the
     * {@code autoHotReload} declaration, because this caller is the module itself.
     */
    static void requestServiceHotReload(ProcessInfo processInfo, String packageName, Bundle extras, IHotReloadCallback callback) {
        var target = processInfo.target(packageName);
        if (target.status == ILSPApplicationService.HOT_RELOAD_IN_PROGRESS) {
            notifyServiceHotReloadResult(callback, IXposedService.HOT_RELOAD_IN_PROGRESS, null);
            return;
        }
        var build = ConfigManager.getInstance().getModuleBuild(packageName);
        if (build == null) {
            // not a module on this device any more
            notifyServiceHotReloadResult(callback, IXposedService.HOT_RELOAD_UNSUPPORTED, null);
            return;
        }
        if (target.runs(build)) {
            notifyServiceHotReloadResult(callback, IXposedService.HOT_RELOAD_SUCCEEDED, null);
            return;
        }
        if (processInfo.reloadEndpoint == null) {
            notifyServiceHotReloadResult(callback, IXposedService.HOT_RELOAD_UNSUPPORTED,
                    processInfo.processName + " never offered a reload endpoint");
            return;
        }
        target.requested = build;
        target.status = ILSPApplicationService.HOT_RELOAD_IN_PROGRESS;
        target.message = null;
        target.serviceCallback = callback;
        dispatchHotReload(processInfo, packageName, extras);
    }

    /** Maps the internal reload outcomes onto the raw statuses the service wire carries. */
    private static int rawHotReloadStatus(int status) {
        if (status == ILSPApplicationService.HOT_RELOAD_SUCCEEDED) return IXposedService.HOT_RELOAD_SUCCEEDED;
        if (status == ILSPApplicationService.HOT_RELOAD_IN_PROGRESS) return IXposedService.HOT_RELOAD_IN_PROGRESS;
        // refused, failed and not-loaded all read as failure to a module app; the message
        // tells the three apart
        return IXposedService.HOT_RELOAD_FAILED;
    }

    private static void notifyServiceHotReloadResult(IHotReloadCallback callback, int status, String message) {
        if (callback == null) return;
        try {
            callback.onHotReloadResult(status, message);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot deliver the hot reload result", e);
        }
    }

    private static void dispatchHotReload(ProcessInfo processInfo, String packageName) {
        dispatchHotReload(processInfo, packageName, null);
    }

    private static void dispatchHotReload(ProcessInfo processInfo, String packageName, Bundle extras) {
        var data = Parcel.obtain();
        try {
            data.writeString(packageName);
            // A module-update reload has nothing to hand over; the service-triggered one
            // passes what the module app asked along.
            data.writeBundle(extras);
            // Oneway: the callee runs module code the daemon has no deadline over, and the outcome
            // comes back through reportHotReloadResult rather than in a reply.
            processInfo.reloadEndpoint.transact(ILSPApplicationService.HOT_RELOAD_TRANSACTION_CODE,
                    data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot reach " + processInfo.processName + " for a hot reload: " + e.getMessage());
        } finally {
            data.recycle();
        }
    }

    @NonNull
    private ProcessInfo ensureRegistered() throws RemoteException {
        var uid = getCallingUid();
        var pid = getCallingPid();
        var key = new Pair<>(uid, pid);
        ProcessInfo processInfo = processes.getOrDefault(key, null);
        if (processInfo == null || uid != processInfo.uid || pid != processInfo.pid) {
            processes.remove(key, processInfo);
            Log.w(TAG, "non-authorized: info=" + processInfo + " uid=" + uid + " pid=" + pid);
            throw new RemoteException("Not registered");
        }
        return processInfo;
    }
}
