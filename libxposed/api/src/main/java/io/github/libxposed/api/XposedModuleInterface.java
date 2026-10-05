package io.github.libxposed.api;

import android.app.AppComponentFactory;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.List;

/**
 * Interface for module initialization.
 *
 * <p>Superset of the API 100, API 101 and API 102 lifecycle surfaces. Modules compiled against
 * any version override the callbacks they know; the framework invokes every callback and modules
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
     * Wraps information about the hot reloading event (API 102).<br/>
     * The callbacks carrying this parameter run in the <b>old</b> code.
     */
    interface HotReloadingParam {
        /**
         * Gets the extras the module app passed when it asked for the reload, or {@code null} when
         * the reload was triggered by a module update or the app passed nothing.
         */
        @Nullable
        Bundle getExtras();

        /**
         * Hands an object to the new generation. It comes back from
         * {@link HotReloadedParam#getSavedInstanceState()}.
         *
         * <p>Do not put anything created by the old module's classloader in here: the new
         * generation would keep the old one reachable, which is the leak a reload exists to
         * avoid. Only classloader-neutral values survive.</p>
         *
         * @param outState The state to carry over, or {@code null}
         * @throws IllegalArgumentException if the state is detected as belonging to the old
         *                                  module classloader
         */
        void setSavedInstanceState(@Nullable Object outState);
    }

    /**
     * Wraps information about the hot reloaded event (API 102).<br/>
     * The callbacks carrying this parameter run in the <b>new</b> code.
     */
    interface HotReloadedParam extends ModuleLoadedParam {
        /**
         * Gets the extras the module app passed when it asked for the reload.
         */
        @Nullable
        Bundle getExtras();

        /**
         * Gets what the old generation left through
         * {@link HotReloadingParam#setSavedInstanceState(Object)}, or {@code null}.
         */
        @Nullable
        Object getSavedInstanceState();

        /**
         * Gets the hooks the previous generation had installed and has not torn down.
         *
         * <p>New code decides what to do with them - keep, {@link XposedInterface.HookHandle#unhook()}
         * or {@link XposedInterface.HookHandle#replaceHook(XposedInterface.Hooker)}. The default
         * {@link #onHotReloaded(HotReloadedParam)} removes them all.</p>
         */
        @NonNull
        List<XposedInterface.HookHandle> getOldHookHandles();
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

    /**
     * Gets notified when the module is about to be reloaded (API 102).
     *
     * <p>Either the module app asked for the reload through the service, or the module package was
     * updated and it declared {@code autoHotReload}. An update-triggered reload proceeds only if
     * this returns {@code true}.</p>
     *
     * <p>Returning {@code true} declares the old generation ready to be retired. Before it does,
     * the module has to stop every thread it owns, unregister native hooks and external callbacks,
     * and drop the references it handed to system or app classes. Whatever it forgets stays
     * reachable through the old classloader.</p>
     *
     * <p>Returning {@code false} refuses the reload; the process keeps running the old code.</p>
     *
     * @param param Information about the reload
     * @return {@code true} to let the reload go ahead
     */
    default boolean onHotReloading(@NonNull HotReloadingParam param) {
        return false;
    }

    /**
     * Gets notified after the module has been reloaded (API 102).<br/>
     * Package lifecycle callbacks are not replayed, so this is where the new code installs what it
     * needs.
     *
     * <p>The default implementation unhooks every handle the old generation left behind: the
     * interface promises the old code is gone, and an installed hook keeps it alive through its
     * hooker. Override this to re-install hooks instead, replacing the old ones in place with
     * {@link XposedInterface.HookHandle#replaceHook(XposedInterface.Hooker)} - that is why the old
     * handles are handed over rather than simply torn down.</p>
     *
     * @param param Information about the reload
     */
    default void onHotReloaded(@NonNull HotReloadedParam param) {
        param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
    }
}
