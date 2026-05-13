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

import static org.lsposed.lspd.service.ServiceManager.TAG;

import android.os.Build;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

public class DeviceIdleService {
    private static final String DEVICE_IDLE_SERVICE = "deviceidle";
    private static final long TEMP_WHITELIST_DURATION = 30_000L;
    private static final int TEMP_WHITELIST_REASON = 316;

    private static IDeviceIdleController deviceIdleController = null;
    private static IBinder binder = null;

    private static final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "DeviceIdleService is dead");
            binder.unlinkToDeath(this, 0);
            binder = null;
            deviceIdleController = null;
        }
    };

    private static IDeviceIdleController getDeviceIdleController() {
        if (binder == null || deviceIdleController == null) {
            binder = ServiceManager.getService(DEVICE_IDLE_SERVICE);
            if (binder == null) {
                Log.w(TAG, "DeviceIdleController is not available");
                return null;
            }
            try {
                binder.linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(e));
            }
            deviceIdleController = IDeviceIdleController.Stub.asInterface(binder);
        }
        return deviceIdleController;
    }

    public static void addPowerSaveTempWhitelistApp(String packageName, int userId, String reason) throws RemoteException {
        var controller = getDeviceIdleController();
        if (controller == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            controller.addPowerSaveTempWhitelistApp(packageName, TEMP_WHITELIST_DURATION, userId, TEMP_WHITELIST_REASON, reason);
        } else {
            controller.addPowerSaveTempWhitelistApp(packageName, TEMP_WHITELIST_DURATION, userId, reason);
        }
        Log.d(TAG, "added power save temp whitelist app: " + packageName + ", duration: " + TEMP_WHITELIST_DURATION + ", userId: " + userId);
    }
}
