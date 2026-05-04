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
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.lsposed.manager.App;
import org.lsposed.manager.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DownloadUtil {
    private static final String TAG = "DownloadUtil";
    private static final OkHttpClient basicClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public static void download(Context context, String url, String fileName) {
        manualDownload(context, url, fileName);
    }

    private static void manualDownload(Context context, String url, String fileName) {
        Toast.makeText(context, R.string.downloading, Toast.LENGTH_SHORT).show();
        Request request = new Request.Builder().url(url).build();
        // Use basicClient to avoid custom DNS issues in system processes
        basicClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Manual download failed", e);
                App.getMainHandler().post(() -> 
                    Toast.makeText(context, context.getString(R.string.download_failed, e.getMessage()), Toast.LENGTH_LONG).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    onFailure(call, new IOException("Unexpected code " + response));
                    return;
                }

                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                    onFailure(call, new IOException("Failed to create download directory"));
                    return;
                }

                File file = new File(downloadDir, fileName);
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                    fos.flush();
                }

                // Use file:// URI — temporarily disable StrictMode's FileUriExposedException
                // check, then restore the original policy immediately after.
                // TODO: find a way to start install intent with shell uid (2000) which may lack REQUEST_INSTALL_PACKAGES permission
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                intent.setDataAndType(android.net.Uri.fromFile(file), "application/vnd.android.package-archive");
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                App.getMainHandler().post(() -> {
                    android.os.StrictMode.VmPolicy oldPolicy = android.os.StrictMode.getVmPolicy();
                    android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder().build());
                    try {
                        context.startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Module view failed", e);
                        Toast.makeText(context, "Module view failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    } finally {
                        android.os.StrictMode.setVmPolicy(oldPolicy);
                    }
                });
            }
        });
    }
}
