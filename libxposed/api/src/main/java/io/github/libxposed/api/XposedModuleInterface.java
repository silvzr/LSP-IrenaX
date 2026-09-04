package io.github.libxposed.api;

import android.app.AppComponentFactory;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * Interface for module initialization.
 *
 * <p>Superset of the API 100 and API 101 lifecycle surfaces. Modules compiled against either
 * version override the callbacks they know; the framework invokes every callback and modules
 * that do not override a given callback simply receive the default no-op.</p>
 */
@SuppressWarnings("unused")
public interface XposedModuleInterface {

    /**
     * Wraps information about the process in which the module is loaded.
     * This information only indicates the state at the time of loading and will not be updated.
     */
    interface ModuleLoadedParam {
        /**
         * Returns whether the current process is system server.
         */
        boolean isSystemServer();

        /**
         * Gets the process name.
         */
        @NonNull
        String getProcessName();
    }

    /**
     * Wraps information about the package being loaded.
     * <p>
     * Note that API 100 exposed {@link #getClassLoader()} directly on this interface; API 101 moved
     * it to {@link PackageReadyParam}. The superset keeps both accessors so that modules of either
     * generation can read the classloader they expect.
     * </p>
     */
    interface PackageLoadedParam {
        /**
         * Gets the package name of the current package.
         */
        @NonNull
        String getPackageName();

        /**
         * Gets the {@link ApplicationInfo} of the current package.
         */
        @NonNull
        ApplicationInfo getApplicationInfo();

        /**
         * Returns whether this is the first and main package loaded in the process.
         */
        boolean isFirstPackage();

        /**
         * Gets the default classloader of the current package. This is the classloader that loads
         * the package's code, resources and custom {@link AppComponentFactory}.
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        @NonNull
        ClassLoader getDefaultClassLoader();

        /**
         * Gets the classloader of the package being loaded (API 100).
         */
        @NonNull
        ClassLoader getClassLoader();
    }

    /**
     * Wraps information about the package whose classloader is ready (API 101).
     */
    interface PackageReadyParam extends PackageLoadedParam {
        /**
         * Gets the {@link AppComponentFactory} of the current package.
         */
        @RequiresApi(Build.VERSION_CODES.P)
        @NonNull
        AppComponentFactory getAppComponentFactory();
    }

    /**
     * Wraps information about system server (API 100).
     */
    interface SystemServerLoadedParam {
        /**
         * Gets the class loader of system server.
         */
        @NonNull
        ClassLoader getClassLoader();
    }

    /**
     * Wraps information about system server (API 101).
     */
    interface SystemServerStartingParam {
        /**
         * Gets the class loader of system server.
         */
        @NonNull
        ClassLoader getClassLoader();
    }

    /**
     * Gets notified when the module is loaded into the target process (API 101).<br/>
     * This callback is guaranteed to be called exactly once for a process.
     */
    default void onModuleLoaded(@NonNull ModuleLoadedParam param) {
    }

    /**
     * Gets notified when a {@link android.R.attr#hasCode} package is loaded into the process.
     * This is the time when the default classloader is ready but before the instantiation of
     * {@link AppComponentFactory}.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    default void onPackageLoaded(@NonNull PackageLoadedParam param) {
    }

    /**
     * Gets notified when {@link AppComponentFactory} has instantiated the classloader
     * and is ready to create {@link android.app.Application} (API 101).
     */
    default void onPackageReady(@NonNull PackageReadyParam param) {
    }

    /**
     * Gets notified when the system server is loaded (API 100).
     */
    default void onSystemServerLoaded(@NonNull SystemServerLoadedParam param) {
    }

    /**
     * Gets notified when system server is ready to start critical services (API 101).
     */
    default void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
    }
}