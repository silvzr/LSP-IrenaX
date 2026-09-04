package io.github.libxposed.api;

import androidx.annotation.NonNull;

/**
 * Super class which all Xposed module entry classes should extend.<br/>
 * Entry classes will be instantiated exactly once for each process.
 *
 * <p>Superset of the API 100 and API 101 entry points:</p>
 * <ul>
 * <li>Modules targeting API 100 declare a constructor taking
 * {@code (XposedInterface, ModuleLoadedParam)}; the framework instantiates them through it.</li>
 * <li>Modules targeting API 101 rely on the no-arg constructor; the framework attaches the
 * framework interface through {@link XposedInterfaceWrapper#attachFramework(XposedInterface)}
 * and then invokes {@link XposedModuleInterface#onModuleLoaded(ModuleLoadedParam)}.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public abstract class XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface {

    /**
     * Instantiates a new Xposed module (API 100 style).<br/>
     * When the module is loaded into the target process, this constructor will be called.
     *
     * @param base  The implementation interface provided by the framework, should not be used by the module
     * @param param Information about the process in which the module is loaded
     */
    public XposedModule(@NonNull XposedInterface base, @NonNull ModuleLoadedParam param) {
        super(base);
    }

    /**
     * Instantiates a new Xposed module (API 101 style).<br/>
     * The framework calls {@link #attachFramework(XposedInterface)} and then
     * {@link XposedModuleInterface#onModuleLoaded(ModuleLoadedParam)} after construction.
     */
    public XposedModule() {
    }
}