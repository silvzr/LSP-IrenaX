package org.lsposed.lspd.impl;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadSystemException;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.lspd.core.ApplicationServiceClient;
import org.lsposed.lspd.core.BuildConfig;
import org.lsposed.lspd.impl.utils.LSPosedDexParser;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.nativebridge.HookBridge;
import org.lsposed.lspd.nativebridge.NativeAPI;
import org.lsposed.lspd.service.ILSPApplicationService;
import org.lsposed.lspd.service.ILSPInjectedModuleService;
import org.lsposed.lspd.util.LspModuleClassLoader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.errors.XposedFrameworkError;
import io.github.libxposed.api.utils.DexParser;


@SuppressLint("NewApi")
public class LSPosedContext implements XposedInterface {

    private static final String TAG = "LSPosedContext";

    public static boolean isSystemServer;
    public static String appDir;
    public static String processName;

    private final String mPackageName;
    private final ApplicationInfo mApplicationInfo;
    private final ILSPInjectedModuleService service;
    private final ExceptionMode mDefaultExceptionMode;
    private final Map<String, SharedPreferences> mRemotePrefs = new ConcurrentHashMap<>();

    /**
     * Set while this generation is on its way out. A hook registered by retired code would outlive
     * the swap and keep the old classloader reachable through its hooker, which is the one thing a
     * reload exists to prevent, so registration is closed once the reload has been accepted.
     */
    private volatile boolean mFrozen = false;

    LSPosedContext(String packageName, ApplicationInfo applicationInfo, ILSPInjectedModuleService service,
                   ExceptionMode defaultExceptionMode) {
        this.mPackageName = packageName;
        this.mApplicationInfo = applicationInfo;
        this.service = service;
        this.mDefaultExceptionMode = defaultExceptionMode;
    }

    void freeze() {
        mFrozen = true;
    }

    void unfreeze() {
        mFrozen = false;
    }

    /**
     * Refuses registration once this generation has been accepted for retirement. Consulted both
     * when a hook builder is handed out and when it is used, because a builder handed out just
     * before the reload was accepted is still in the module's hands.
     */
    void checkNotFrozen() {
        if (mFrozen) {
            throw new IllegalStateException("Cannot register hooks from a retired module generation");
        }
    }

    // module lifecycle dispatch: fire every callback, modules react to what they override.
    //
    // Dispatch walks the live generations, so which entries a callback reaches is decided by the
    // single map entry a reload writes. Subscribing a generation as it is built instead would let a
    // call observe a module twice - or, if the build threw halfway, permanently, because the
    // half-built generation has no name to be retired under.

    private static void forEachEntry(Consumer<XposedModule> action) {
        for (var generation : generations.values()) {
            for (var module : generation.entries) {
                action.accept(module);
            }
        }
    }

    public static void callOnModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        forEachEntry(module -> {
            try {
                module.onModuleLoaded(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onModuleLoaded of " + module.getModuleApplicationInfo().packageName, t);
            }
        });
    }

    public static void callOnPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        forEachEntry(module -> {
            try {
                module.onPackageLoaded(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onPackageLoaded of " + module.getModuleApplicationInfo().packageName, t);
            }
        });
    }

    public static void callOnPackageReady(XposedModuleInterface.PackageReadyParam param) {
        forEachEntry(module -> {
            try {
                module.onPackageReady(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onPackageReady of " + module.getModuleApplicationInfo().packageName, t);
            }
        });
    }

    public static void callOnSystemServerLoaded(XposedModuleInterface.SystemServerLoadedParam param) {
        forEachEntry(module -> {
            try {
                module.onSystemServerLoaded(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onSystemServerLoaded of " + module.getModuleApplicationInfo().packageName, t);
            }
        });
    }

    public static void callOnSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        forEachEntry(module -> {
            try {
                module.onSystemServerStarting(param);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onSystemServerStarting of " + module.getModuleApplicationInfo().packageName, t);
            }
        });
    }

    /**
     * The entry classes of one module package as they are currently loaded, together with the
     * framework interface they were attached to.
     *
     * <p>A reload replaces this whole object, and publishing it is a single write to the map the
     * dispatch above walks: callbacks see the old generation or the new one, never both and never
     * neither. The entries are a copy-on-write list because an entry can leave the lifecycle on its
     * own from inside a callback this list is being iterated for.</p>
     */
    private static final class Generation {
        final ClassLoader classLoader;
        final LSPosedContext context;
        final List<XposedModule> entries = new CopyOnWriteArrayList<>();

        Generation(ClassLoader classLoader, LSPosedContext context) {
            this.classLoader = classLoader;
            this.context = context;
        }
    }

    /** Live generations by module package name, so a reload can find what it has to retire. */
    private static final Map<String, Generation> generations = new ConcurrentHashMap<>();

    /** One lock per module, so reloads of the same module queue instead of interleaving. */
    private static final Map<String, Object> reloadLocks = new ConcurrentHashMap<>();

    private static final class ModuleLoadedParamImpl implements XposedModuleInterface.ModuleLoadedParam {
        @Override
        public boolean isSystemServer() {
            return LSPosedContext.isSystemServer;
        }

        @NonNull
        @Override
        public String getProcessName() {
            return LSPosedContext.processName;
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    public static boolean loadModule(ActivityThread at, Module module) {
        try {
            Log.d(TAG, "Loading module " + module.packageName);
            var initLoader = XposedModule.class.getClassLoader();
            var mcl = loadModuleApk(module, initLoader);
            if (mcl.loadClass(XposedModule.class.getName()).getClassLoader() != initLoader) {
                Log.e(TAG, "  Cannot load module: " + module.packageName);
                Log.e(TAG, "  The Xposed API classes are compiled into the module's APK.");
                Log.e(TAG, "  This may cause strange issues and must be fixed by the module developer.");
                return false;
            }
            module.file.moduleLibraryNames.forEach(NativeAPI::recordNativeEntrypoint);
            var generation = instantiate(module, mcl, true);
            if (generation.entries.isEmpty()) {
                Log.e(TAG, "  No entry class of " + module.packageName + " could be loaded");
                return false;
            }
            generations.put(module.packageName, generation);
            Log.d(TAG, "Loaded module " + module.packageName + ": " + generation.context);
        } catch (Throwable e) {
            Log.d(TAG, "Loading module " + module.packageName, e);
            return false;
        }
        return true;
    }

    /**
     * Loads a module's code into the process.
     *
     * <p>A module that targets 102 or higher is built against a framework that no longer offers
     * the legacy API, so the loader stops resolving it: legacy state is global and static, which
     * means anything holding on to it outlives a reload in a way the framework cannot clean up.
     * The names to refuse are not the ones written in source whenever dex obfuscation is on, so
     * they are resolved through the same map the rest of the framework uses.</p>
     */
    private static ClassLoader loadModuleApk(Module module, ClassLoader initLoader) {
        var sb = new StringBuilder();
        var abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : abis) {
            sb.append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator);
        }
        var librarySearchPath = sb.toString();
        var blockLegacyApi = module.file != null
                && module.file.targetApiVersion >= XposedInterface.API_102;
        return LspModuleClassLoader.loadApk(module.apkPath, module.file.preLoadedDexes,
                librarySearchPath, initLoader, blockLegacyApi);
    }

    /**
     * Builds a generation from a module's already-loaded code.
     *
     * <p>Nothing is published here: the entries are collected into the generation and only reach
     * the process when the caller puts it in the map. A build that throws part way through, or one
     * whose entries all fail to load, therefore leaves the process exactly as it was.</p>
     *
     * @param firstLoad whether this is the initial load, which is the only time
     *                  {@link XposedModuleInterface#onModuleLoaded} is delivered: the interface
     *                  says a reload does not replay it, so new code hears about the swap through
     *                  {@link XposedModuleInterface#onHotReloaded} instead
     */
    private static Generation instantiate(Module module, ClassLoader mcl, boolean firstLoad) {
        var defaultExceptionMode = module.file.exceptionPassthrough ? ExceptionMode.PASSTHROUGH : ExceptionMode.PROTECTIVE;
        var ctx = new LSPosedContext(module.packageName, module.applicationInfo, module.service, defaultExceptionMode);
        var generation = new Generation(mcl, ctx);
        for (var entry : module.file.moduleClassNames) {
            try {
                var moduleClass = mcl.loadClass(entry);
                Log.d(TAG, "  Loading class " + moduleClass);
                if (!XposedModule.class.isAssignableFrom(moduleClass)) {
                    Log.e(TAG, "    This class doesn't implement any sub-interface of XposedModule, skipping it");
                    continue;
                }
                var moduleContext = instantiateEntry(moduleClass, ctx, generation.entries);
                if (firstLoad) {
                    moduleContext.onModuleLoaded(new ModuleLoadedParamImpl());
                }
            } catch (Throwable e) {
                // One broken entry must not cost the module the others; an update that renames a
                // class is exactly the case a reload has to survive.
                Log.e(TAG, "    Failed to load class " + entry, e);
            }
        }
        return generation;
    }

    private static XposedModule instantiateEntry(Class<?> moduleClass, LSPosedContext ctx,
                                                 List<XposedModule> entries) throws Throwable {
        XposedModule moduleContext;
        try {
            // API 100 modules take a (XposedInterface, ModuleLoadedParam) ctor, API 101 and 102
            // modules use no-arg + attachFramework. try API 100 first, fall back to the no-arg
            // one, decided per module so nobody has to configure anything.
            var moduleEntry = moduleClass.getConstructor(XposedInterface.class,
                    XposedModuleInterface.ModuleLoadedParam.class);
            moduleContext = (XposedModule) moduleEntry.newInstance(ctx, new ModuleLoadedParamImpl());
        } catch (NoSuchMethodException e) {
            var entry = (XposedModule) moduleClass.getConstructor().newInstance();
            // From 102 an entry can leave the lifecycle on its own, and only for itself: the
            // framework holds the reference, so it is the one that has to be able to drop it,
            // while a sibling entry of the same module keeps receiving its callbacks.
            entry.attachFramework(ctx, () -> entries.remove(entry));
            moduleContext = entry;
        }
        entries.add(moduleContext);
        return moduleContext;
    }

    /**
     * Reloads a module into this process (API 102).
     *
     * <p>The old generation is asked first and nothing is disturbed until it agrees. Then its hook
     * registration is closed and its installed hooks are collected, and only after that is the new
     * generation built from the module's current APK - by which point the daemon has already
     * re-read it. The two generations are never both able to answer a call: the swap is one write
     * to the generation map, and the handles the old one left are handed to the new code to retire
     * or take over.</p>
     *
     * <p>Reloads are serialised per module, so two updates arriving together cannot both freeze the
     * same generation and then race to replace it. A request that arrives while another is running
     * waits its turn rather than being answered from a half-retired generation.</p>
     *
     * <p>The outcome is reported to the daemon either way: the request that reaches this process is
     * oneway, so the process is the only side that can say whether the new code actually took
     * over.</p>
     *
     * @param packageName the module to reload
     * @param extras      what the caller passed along, or {@code null}
     * @return whether the module was reloaded
     */
    public static boolean requestHotReload(String packageName, Bundle extras) {
        var status = ILSPApplicationService.HOT_RELOAD_FAILED;
        String message = null;
        try {
            synchronized (reloadLocks.computeIfAbsent(packageName, k -> new Object())) {
                status = reloadLocked(packageName, extras);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hot reload " + packageName, t);
            message = String.valueOf(t);
        }
        reportHotReload(packageName, status, message);
        return status == ILSPApplicationService.HOT_RELOAD_SUCCEEDED;
    }

    /** @return one of the {@code ILSPApplicationService.HOT_RELOAD_*} outcomes */
    private static int reloadLocked(String packageName, Bundle extras) {
        var previous = generations.get(packageName);
        if (previous == null) {
            Log.d(TAG, "Hot reload of " + packageName + " requested, but it is not loaded here");
            return ILSPApplicationService.HOT_RELOAD_NOT_LOADED;
        }

        // An entry that detached is no longer in the generation, so iterating it is also asking
        // only the entries that still want to hear about lifecycle events.
        var reloading = new HotReloadingParamImpl(extras, previous.classLoader);
        for (var entry : previous.entries) {
            boolean accepted;
            try {
                accepted = entry.onHotReloading(reloading);
            } catch (Throwable t) {
                Log.e(TAG, "Error when calling onHotReloading of " + packageName, t);
                return ILSPApplicationService.HOT_RELOAD_FAILED;
            }
            if (!accepted) {
                Log.d(TAG, "Hot reload of " + packageName + " refused");
                return ILSPApplicationService.HOT_RELOAD_REFUSED;
            }
        }
        if (previous.entries.isEmpty()) {
            // Nothing is left that could agree, and an unanswered question is not a yes.
            Log.d(TAG, "Hot reload of " + packageName + " has no entry left to ask");
            return ILSPApplicationService.HOT_RELOAD_NOT_LOADED;
        }

        // Close registration before the handle list is read: a hook old code registered from here
        // on would survive the swap and hold the retired classloader in place through its hooker.
        // The registry lock is what a registration holds while it is under way, so taking it here
        // makes a registration either finish before the freeze (and be in the list) or re-check
        // the frozen flag under the same lock in doHook and give up.
        List<XposedInterface.HookHandle> oldHandles;
        synchronized (LSPosedBridge.HookRegistry.lockOf(packageName)) {
            previous.context.freeze();
            oldHandles = LSPosedBridge.HookRegistry.liveHandles(packageName);
        }

        var swapped = false;
        try {
            var module = findRefreshedModule(packageName);
            if (module == null) {
                Log.e(TAG, "Hot reload of " + packageName + " found no module to load");
                return ILSPApplicationService.HOT_RELOAD_NOT_LOADED;
            }
            var mcl = loadModuleApk(module, XposedModule.class.getClassLoader());
            var next = instantiate(module, mcl, false);
            if (next.entries.isEmpty()) {
                // Swapping in a generation with nothing live in it would silently stop the module
                // from working in this process, which is worse than staying on the old build.
                Log.e(TAG, "Hot reload of " + packageName + " produced no usable entry; keeping the old build");
                return ILSPApplicationService.HOT_RELOAD_FAILED;
            }
            // The library names are re-recorded because an updated module may ship new ones; the
            // old entry points keep working until the process ends.
            module.file.moduleLibraryNames.forEach(NativeAPI::recordNativeEntrypoint);

            // The swap: one write, and every callback from here on reaches the new entries only.
            generations.put(packageName, next);
            swapped = true;

            var reloaded = new HotReloadedParamImpl(extras, reloading.savedInstanceState, oldHandles);
            for (var entry : next.entries) {
                try {
                    entry.onHotReloaded(reloaded);
                } catch (Throwable t) {
                    Log.e(TAG, "Error when calling onHotReloaded of " + packageName, t);
                }
            }
            Log.i(TAG, "Hot reloaded module " + packageName);
            return ILSPApplicationService.HOT_RELOAD_SUCCEEDED;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hot reload " + packageName, t);
            return ILSPApplicationService.HOT_RELOAD_FAILED;
        } finally {
            // A reload that did not go through leaves the old code in charge, so it gets its
            // registration back; a completed one stays retired for good. Take the registry lock
            // around the unfreeze, so a registration cannot observe a stale frozen flag from a
            // reload that has already failed.
            synchronized (LSPosedBridge.HookRegistry.lockOf(packageName)) {
                if (!swapped) previous.context.unfreeze();
            }
        }
    }

    /**
     * Tells the daemon how a reload it asked for ended. It cannot find out on its own: the request
     * is oneway, so without this a refused or failed reload is written down as a success.
     */
    private static void reportHotReload(String packageName, int status, String message) {
        var client = ApplicationServiceClient.serviceClient;
        if (client == null) return;
        try {
            client.reportHotReloadResult(packageName, status, message);
        } catch (Throwable t) {
            Log.w(TAG, "Cannot report the reload result of " + packageName, t);
        }
    }

    private static Module findRefreshedModule(String packageName) {
        var client = ApplicationServiceClient.serviceClient;
        if (client == null) return null;
        for (var module : client.getModulesList()) {
            if (packageName.equals(module.packageName)) return module;
        }
        return null;
    }

    private static final class HotReloadingParamImpl implements XposedModuleInterface.HotReloadingParam {
        private final Bundle extras;
        private final ClassLoader retiredLoader;
        private Object savedInstanceState;

        HotReloadingParamImpl(Bundle extras, ClassLoader retiredLoader) {
            this.extras = extras;
            this.retiredLoader = retiredLoader;
        }

        @Override
        public Bundle getExtras() {
            return extras;
        }

        @Override
        public void setSavedInstanceState(Object outState) {
            if (isFromRetiredLoader(outState)) {
                throw new IllegalArgumentException(
                        "Saved state must not hold objects created by the module classloader being retired");
            }
            this.savedInstanceState = outState;
        }

        /**
         * Shallow test for whether an object was created by the generation being retired, or by a
         * loader derived from it. It looks at the object itself and nothing it refers to, so it is
         * a diagnostic rather than a guarantee: an object that slips through undetected is still a
         * module lifecycle bug.
         */
        private boolean isFromRetiredLoader(Object object) {
            if (object == null) return false;
            for (var cl = object.getClass().getClassLoader(); cl != null; cl = cl.getParent()) {
                if (cl == retiredLoader) return true;
            }
            return false;
        }
    }

    private static final class HotReloadedParamImpl implements XposedModuleInterface.HotReloadedParam {
        private final Bundle extras;
        private final Object savedInstanceState;
        private final List<XposedInterface.HookHandle> oldHookHandles;

        HotReloadedParamImpl(Bundle extras, Object savedInstanceState, List<XposedInterface.HookHandle> oldHookHandles) {
            this.extras = extras;
            this.savedInstanceState = savedInstanceState;
            this.oldHookHandles = oldHookHandles;
        }

        @Override
        public boolean isSystemServer() {
            return LSPosedContext.isSystemServer;
        }

        @NonNull
        @Override
        public String getProcessName() {
            return LSPosedContext.processName;
        }

        @Override
        public Bundle getExtras() {
            return extras;
        }

        @Override
        public Object getSavedInstanceState() {
            return savedInstanceState;
        }

        @NonNull
        @Override
        public List<XposedInterface.HookHandle> getOldHookHandles() {
            return oldHookHandles;
        }
    }

    @NonNull
    @Override
    public String getFrameworkName() {
        return BuildConfig.FRAMEWORK_NAME;
    }

    @NonNull
    @Override
    public String getFrameworkVersion() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public long getFrameworkVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public long getFrameworkProperties() {
        try {
            return service.getFrameworkProperties();
        } catch (RemoteException e) {
            throw new XposedFrameworkError(e);
        }
    }

    @Override
    public int getFrameworkPrivilege() {
        try {
            return service.getFrameworkPrivilege();
        } catch (RemoteException ignored) {
            return -1;
        }
    }

    // Hooking (API 101)

    @Override
    @NonNull
    public HookBuilder hook(@NonNull Executable origin) {
        checkNotFrozen();
        return LSPosedBridge.newHookBuilder(this, origin, mPackageName, mDefaultExceptionMode);
    }

    @Override
    @NonNull
    public HookBuilder hookClassInitializer(@NonNull Class<?> origin) {
        checkNotFrozen();
        return LSPosedBridge.newClassInitializerHookBuilder(this, origin, mPackageName, mDefaultExceptionMode);
    }

    @Override
    public boolean deoptimize(@NonNull Executable executable) {
        return LSPosedBridge.doDeoptimize(executable);
    }

    @NonNull
    @Override
    public Invoker<?, Method> getInvoker(@NonNull Method method) {
        return LSPosedBridge.newInvoker(method);
    }

    @NonNull
    @Override
    public <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor) {
        return LSPosedBridge.newInvoker(constructor);
    }

    // Hooking (API 100)

    @Override
    @NonNull
    public MethodUnhooker<Method> hook(@NonNull Method origin, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHook(origin, PRIORITY_DEFAULT, hooker);
    }

    @Override
    @NonNull
    public MethodUnhooker<Method> hook(@NonNull Method origin, int priority, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHook(origin, priority, hooker);
    }

    @Override
    @NonNull
    public <T> MethodUnhooker<Constructor<T>> hook(@NonNull Constructor<T> origin, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHook(origin, PRIORITY_DEFAULT, hooker);
    }

    @Override
    @NonNull
    public <T> MethodUnhooker<Constructor<T>> hook(@NonNull Constructor<T> origin, int priority, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHook(origin, priority, hooker);
    }

    private static boolean doDeoptimize(@NonNull Executable method) {
        if (Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalArgumentException("Cannot deoptimize abstract methods: " + method);
        } else if (Proxy.isProxyClass(method.getDeclaringClass())) {
            throw new IllegalArgumentException("Cannot deoptimize methods from proxy class: " + method);
        }
        return HookBridge.deoptimizeMethod(method);
    }

    @Override
    public boolean deoptimize(@NonNull Method method) {
        return doDeoptimize(method);
    }

    @Override
    public <T> boolean deoptimize(@NonNull Constructor<T> constructor) {
        return doDeoptimize(constructor);
    }

    @NonNull
    @Override
    public <T> MethodUnhooker<Constructor<T>> hookClassInitializer(@NonNull Class<T> origin, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHookClassInitializer(origin, PRIORITY_DEFAULT, hooker);
    }

    @NonNull
    @Override
    public <T> MethodUnhooker<Constructor<T>> hookClassInitializer(@NonNull Class<T> origin, int priority, @NonNull Class<? extends Hooker> hooker) {
        return LSPosedBridge.doHookClassInitializer(origin, priority, hooker);
    }

    @Nullable
    @Override
    public Object invokeOrigin(@NonNull Method method, @Nullable Object thisObject, Object[] args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        return HookBridge.invokeOriginalMethod(method, thisObject, args);
    }

    @Override
    public <T> void invokeOrigin(@NonNull Constructor<T> constructor, @NonNull T thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        HookBridge.invokeOriginalMethod(constructor, thisObject, args);
    }

    @Nullable
    @Override
    public Object invokeSpecial(@NonNull Method method, @NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("Cannot invoke special on static method: " + method);
        }
        try {
            return HookBridge.invokeSpecialMethod(method, thisObject, args);
        } catch (InstantiationException e) {
            throw new InstantiationError(e.getMessage());
        }
    }

    @Override
    public <T> void invokeSpecial(@NonNull Constructor<T> constructor, @NonNull T thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        if (Modifier.isStatic(constructor.getModifiers())) {
            throw new IllegalArgumentException("Cannot invoke special on static constructor: " + constructor);
        }
        try {
            HookBridge.invokeSpecialMethod(constructor, thisObject, args);
        } catch (InstantiationException e) {
            throw new InstantiationError(e.getMessage());
        }
    }

    @NonNull
    @Override
    public <T> T newInstanceOrigin(@NonNull Constructor<T> constructor, Object... args) throws InvocationTargetException, IllegalAccessException, InstantiationException {
        return (T) HookBridge.invokeOriginalMethod(constructor, null, args);
    }

    @NonNull
    @Override
    public <T, U> U newInstanceSpecial(@NonNull Constructor<T> constructor, @NonNull Class<U> subClass, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
        var superClass = constructor.getDeclaringClass();
        if (!superClass.isAssignableFrom(subClass)) {
            throw new IllegalArgumentException(subClass + " is not inherited from " + superClass);
        }
        return (U) HookBridge.invokeSpecialMethod(constructor, subClass, null, args);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg) {
        log(priority, tag, msg, null);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String message, @Nullable Throwable throwable) {
        if (message.isEmpty() && throwable == null) {
            return;
        }

        var estimatedLength = Math.max(0xFC2 - (tag == null ? 0 : tag.length()), 100);
        var output = new StringWriter(estimatedLength);
        var writer = new PrintWriter(output);

        var moduleTag = String.valueOf(tag);
        writer.println(String.format("[%s,%s] %s", mPackageName, moduleTag, message));

        if (throwable != null) {
            Throwable candidate;
            for (candidate = throwable; candidate != null && !(candidate instanceof UnknownHostException); candidate = candidate.getCause()) {
                if (candidate instanceof DeadSystemException) {
                    writer.println("DeadSystemException: The system died; earlier logs will point to the root cause");
                    break;
                }
            }
            if (candidate == null) {
                throwable.printStackTrace(writer);
            }
        }

        writer.flush();
        Log.println(priority, TAG, output.toString());
    }

    @Override
    @Deprecated
    public void log(@NonNull String message) {
        log(Log.INFO, "null", message, null);
    }

    @Override
    @Deprecated
    public void log(@NonNull String message, @NonNull Throwable throwable) {
        log(Log.ERROR, "null", message, throwable);
    }

    @Override
    public DexParser parseDex(@NonNull ByteBuffer dexData, boolean includeAnnotations) throws IOException {
        return new LSPosedDexParser(dexData, includeAnnotations);
    }

    @NonNull
    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        return mApplicationInfo;
    }

    @NonNull
    @Override
    public ApplicationInfo getApplicationInfo() {
        return mApplicationInfo;
    }

    @NonNull
    @Override
    public SharedPreferences getRemotePreferences(String name) {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        return mRemotePrefs.computeIfAbsent(name, n -> {
            try {
                return new LSPosedRemotePreferences(service, n);
            } catch (RemoteException e) {
                log(Log.ERROR, "null", "Failed to get remote preferences", e);
                throw new XposedFrameworkError(e);
            }
        });
    }

    @NonNull
    @Override
    public String[] listRemoteFiles() {
        try {
            return service.getRemoteFileList();
        } catch (RemoteException e) {
            log(Log.ERROR, "null", "Failed to list remote files", e);
            throw new XposedFrameworkError(e);
        }
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        try {
            return service.openRemoteFile(name);
        } catch (RemoteException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }
}