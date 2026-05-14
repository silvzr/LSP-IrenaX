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
 * Copyright (C) 2026 LSPosed Contributors
 */

package org.lsposed.lspd.service;

import static org.lsposed.lspd.service.PackageService.MATCH_ALL_FLAGS;
import static org.lsposed.lspd.service.ServiceManager.TAG;
import static org.lsposed.lspd.service.ServiceManager.existsInGlobalNamespace;
import static org.lsposed.lspd.service.ServiceManager.toGlobalNamespace;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

final class PackageMonitorService {
    private static final int MSG_UPDATE_ALL = 0;
    private static final int MSG_UPDATE_PACKAGE = 1;
    private static final int MSG_REMOVE_PACKAGE = 2;

    private static PackageMonitorService instance;

    private final ConcurrentHashMap<String, ModuleInfo> modules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> packageApks = new ConcurrentHashMap<>();
    private final Handler handler;

    static synchronized PackageMonitorService getInstance() {
        if (instance == null) {
            instance = new PackageMonitorService();
        }
        return instance;
    }

    private PackageMonitorService() {
        var handlerThread = new HandlerThread("LSPPackageMonitorService");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper(), message -> {
            switch (message.what) {
                case MSG_UPDATE_ALL -> updateAllPackages();
                case MSG_UPDATE_PACKAGE -> {
                    var request = (PackageRequest) message.obj;
                    updatePackage(request.packageName, request.userId);
                }
                case MSG_REMOVE_PACKAGE -> {
                    var request = (PackageRequest) message.obj;
                    removePackage(request.packageName, request.userId, request.allUsers);
                }
                default -> {
                    return false;
                }
            }
            return true;
        });
    }

    void updateAllPackagesAsync() {
        handler.obtainMessage(MSG_UPDATE_ALL).sendToTarget();
    }

    void updatePackageAsync(String packageName, int userId) {
        if (packageName == null) return;
        handler.obtainMessage(MSG_UPDATE_PACKAGE, new PackageRequest(packageName, userId, false)).sendToTarget();
    }

    void removePackageAsync(String packageName, int userId, boolean allUsers) {
        if (packageName == null) return;
        handler.obtainMessage(MSG_REMOVE_PACKAGE, new PackageRequest(packageName, userId, allUsers)).sendToTarget();
    }

    @Nullable
    ModuleInfo getModuleInfo(String packageName, int userId) throws RemoteException {
        if (packageName == null || packageName.equals("lspd")) return null;
        synchronized (this) {
            var moduleInfo = modules.get(packageName);
            if (isValidModuleInfo(moduleInfo, userId)) {
                return moduleInfo;
            }
            return updatePackageLocked(packageName, userId).moduleInfo;
        }
    }

    PackageState updatePackage(String packageName, int userId) {
        if (packageName == null || packageName.equals("lspd")) return PackageState.empty(packageName);
        synchronized (this) {
            try {
                return updatePackageLocked(packageName, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Get package info of " + packageName, e);
                return PackageState.empty(packageName);
            }
        }
    }

    PackageState removePackage(String packageName, int userId, boolean allUsers) {
        if (packageName == null || packageName.equals("lspd")) return PackageState.empty(packageName);
        synchronized (this) {
            var oldModule = modules.get(packageName);
            if (allUsers) {
                modules.remove(packageName);
                packageApks.remove(packageName);
                return new PackageState(packageName, oldModule, oldModule != null);
            }
            try {
                var state = updatePackageLocked(packageName, -1);
                return state.xposedModule ? state : new PackageState(packageName, oldModule, oldModule != null);
            } catch (RemoteException e) {
                Log.w(TAG, "Get package info of " + packageName, e);
                return new PackageState(packageName, oldModule, oldModule != null);
            }
        }
    }

    private void updateAllPackages() {
        synchronized (this) {
            if (!PackageService.isAlive() || !UserService.isAlive()) return;
            var packageNames = ConcurrentHashMap.<String>newKeySet();
            try {
                for (var user : UserService.getUsers()) {
                    for (var packageInfo : PackageService.getInstalledPackages(MATCH_ALL_FLAGS | PackageManager.GET_META_DATA, user.id)) {
                        if (packageInfo == null || packageInfo.applicationInfo == null) continue;
                        if (!PackageService.isPackageAvailable(packageInfo.packageName, user.id, true)) continue;
                        packageNames.add(packageInfo.packageName);
                        updatePackageLocked(packageInfo, user.id);
                    }
                }
                modules.keySet().retainAll(packageNames);
                packageApks.keySet().retainAll(packageNames);
            } catch (RemoteException e) {
                Log.w(TAG, "update package monitor cache", e);
            }
        }
    }

    private PackageState updatePackageLocked(String packageName, int userId) throws RemoteException {
        var packageInfo = findPackageInfo(packageName, userId);
        if (packageInfo == null || packageInfo.applicationInfo == null) {
            modules.remove(packageName);
            packageApks.remove(packageName);
            return PackageState.empty(packageName);
        }
        return updatePackageLocked(packageInfo, userId);
    }

    private PackageState updatePackageLocked(@Nullable PackageInfo packageInfo, int userId) throws RemoteException {
        if (packageInfo == null || packageInfo.applicationInfo == null) {
            return PackageState.empty(null);
        }
        var packageName = packageInfo.packageName;
        var moduleInfo = parseModule(packageInfo);
        var xposedModule = isXposedModule(packageInfo, moduleInfo);
        if (moduleInfo != null) {
            modules.put(packageName, moduleInfo);
        } else {
            modules.remove(packageName);
        }
        return new PackageState(packageName, moduleInfo, xposedModule);
    }

    @Nullable
    private PackageInfo findPackageInfo(String packageName, int userId) throws RemoteException {
        if (userId >= 0) {
            var packageInfo = findPackageInfoForUser(packageName, userId);
            if (packageInfo != null) {
                return packageInfo;
            }
        }
        if (!UserService.isAlive()) return null;
        for (var user : UserService.getUsers()) {
            var packageInfo = findPackageInfoForUser(packageName, user.id);
            if (packageInfo != null) {
                return packageInfo;
            }
        }
        return null;
    }

    @Nullable
    private PackageInfo findPackageInfoForUser(String packageName, int userId) throws RemoteException {
        if (!PackageService.isPackageAvailable(packageName, userId, true)) return null;
        var packageInfo = PackageService.getPackageInfo(packageName, MATCH_ALL_FLAGS | PackageManager.GET_META_DATA, userId);
        if (packageInfo == null || packageInfo.applicationInfo == null) return null;
        return packageInfo;
    }

    @Nullable
    private ModuleInfo parseModule(PackageInfo packageInfo) throws RemoteException {
        var applicationInfo = packageInfo.applicationInfo;
        if (applicationInfo == null) return null;
        var apks = collectApks(applicationInfo);
        packageApks.put(packageInfo.packageName, new HashSet<>(apks));

        for (var apk : apks) {
            if (apk == null) {
                Log.w(TAG, packageInfo.packageName + " has null apk path???");
                continue;
            }
            var moduleInfo = parseModuleApk(apk, packageInfo, applicationInfo);
            if (moduleInfo != null) {
                return moduleInfo;
            }
        }
        return null;
    }

    @Nullable
    private ModuleInfo parseModuleApk(String apk, PackageInfo packageInfo, ApplicationInfo applicationInfo) throws RemoteException {
        try (var zip = new ZipFile(toGlobalNamespace(apk))) {
            if (ConfigFileManager.readModernModuleProperties(zip) != null) {
                return new ModuleInfo(packageInfo.packageName, apk, applicationInfo.uid, applicationInfo, packageInfo, false, collectInstalledUsers(packageInfo.packageName));
            }
            if (ConfigFileManager.requiresModernModuleLoading(zip)) {
                return null;
            }
            if (zip.getEntry("assets/xposed_init") != null) {
                return new ModuleInfo(packageInfo.packageName, apk, applicationInfo.uid, applicationInfo, packageInfo, true, collectInstalledUsers(packageInfo.packageName));
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private boolean isXposedModule(PackageInfo packageInfo, @Nullable ModuleInfo moduleInfo) {
        if (moduleInfo != null) return true;
        var applicationInfo = packageInfo.applicationInfo;
        if (applicationInfo == null) return false;
        if (applicationInfo.metaData != null && applicationInfo.metaData.containsKey("xposedminversion")) {
            return true;
        }
        for (var apk : collectApks(applicationInfo)) {
            if (apk == null) continue;
            try (var zip = new ZipFile(toGlobalNamespace(apk))) {
                if (zip.getEntry("META-INF/xposed/java_init.list") != null) {
                    return true;
                }
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    private List<String> collectApks(ApplicationInfo applicationInfo) {
        var apks = new ArrayList<String>();
        if (applicationInfo.sourceDir != null) {
            apks.add(applicationInfo.sourceDir);
        }
        if (applicationInfo.splitSourceDirs != null) {
            Collections.addAll(apks, applicationInfo.splitSourceDirs);
        }
        return apks;
    }

    private Set<Integer> collectInstalledUsers(String packageName) throws RemoteException {
        var users = new HashSet<Integer>();
        if (!UserService.isAlive()) return users;
        for (var user : UserService.getUsers()) {
            if (PackageService.isPackageAvailable(packageName, user.id, true)) {
                users.add(user.id);
            }
        }
        return users;
    }

    private boolean isValidModuleInfo(@Nullable ModuleInfo moduleInfo, int userId) {
        if (moduleInfo == null) return false;
        if (moduleInfo.apkPath == null || !existsInGlobalNamespace(moduleInfo.apkPath)) return false;
        if (userId == -1) return true;
        return moduleInfo.installedUsers.contains(userId);
    }

    static final class ModuleInfo {
        final String packageName;
        final String apkPath;
        final int appId;
        final ApplicationInfo applicationInfo;
        final PackageInfo packageInfo;
        final boolean legacy;
        final Set<Integer> installedUsers;

        ModuleInfo(String packageName, String apkPath, int appId, ApplicationInfo applicationInfo, PackageInfo packageInfo, boolean legacy, Set<Integer> installedUsers) {
            this.packageName = packageName;
            this.apkPath = apkPath;
            this.appId = appId;
            this.applicationInfo = applicationInfo;
            this.packageInfo = packageInfo;
            this.legacy = legacy;
            this.installedUsers = installedUsers;
        }
    }

    static final class PackageState {
        final String packageName;
        final ModuleInfo moduleInfo;
        final boolean xposedModule;

        PackageState(String packageName, @Nullable ModuleInfo moduleInfo, boolean xposedModule) {
            this.packageName = packageName;
            this.moduleInfo = moduleInfo;
            this.xposedModule = xposedModule;
        }

        static PackageState empty(String packageName) {
            return new PackageState(packageName, null, false);
        }
    }

    private static final class PackageRequest {
        final String packageName;
        final int userId;
        final boolean allUsers;

        PackageRequest(String packageName, int userId, boolean allUsers) {
            this.packageName = Objects.requireNonNull(packageName);
            this.userId = userId;
            this.allUsers = allUsers;
        }
    }
}
