package io.github.libxposed.api;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

import io.github.libxposed.api.utils.DexParser;

/**
 * Xposed interface for modules to operate on application processes.
 *
 * <p>This is a superset of the libxposed API 100 and API 101 surfaces. Modules compiled
 * against either published version run against this interface: the members of both APIs
 * are present here, and the framework dispatches each call to the matching engine
 * automatically (overloads resolve to the API 100 or API 101 code path at the call site).
 * </p>
 */
@SuppressWarnings("unused")
public interface XposedInterface {

    // ------------------------------------------------------------------
    // API 100 surface
    // ------------------------------------------------------------------

    /**
     * The SDK API version of the libxposed 100 surface.
     */
    int API = 100;

    /**
     * Indicates that the framework is running as root.
     */
    int FRAMEWORK_PRIVILEGE_ROOT = 0;

    /**
     * Indicates that the framework is running in a container with a fake system_server.
     */
    int FRAMEWORK_PRIVILEGE_CONTAINER = 1;

    /**
     * Indicates that the framework is running as a different app, which may have at most shell permission.
     */
    int FRAMEWORK_PRIVILEGE_APP = 2;

    /**
     * Indicates that the framework is embedded in the hooked app,
     * which means {@link #getRemotePreferences} will be null and remote file is unsupported.
     */
    int FRAMEWORK_PRIVILEGE_EMBEDDED = 3;

    // ------------------------------------------------------------------
    // API 101 surface
    // ------------------------------------------------------------------

    /**
     * The API version of the libxposed 101 surface.
     */
    int API_101 = 101;

    /**
     * The API version of this <b>library</b>. Modules should use {@link #getApiVersion()}
     * to check the API version at runtime.
     */
    int LIB_API = API_101;

    /**
     * The framework has the capability to hook system_server and other system processes.
     */
    long PROP_CAP_SYSTEM = 1L;

    /**
     * The framework provides remote preferences and remote files support.
     */
    long PROP_CAP_REMOTE = 1L << 1;

    /**
     * The framework disallows accessing Xposed API via reflection or dynamically loaded code.
     */
    long PROP_RT_API_PROTECTION = 1L << 2;

    // ------------------------------------------------------------------
    // Shared constants
    // ------------------------------------------------------------------

    /**
     * The default hook priority.
     */
    int PRIORITY_DEFAULT = 50;

    /**
     * Execute at the end of the interception chain.
     */
    int PRIORITY_LOWEST = Integer.MIN_VALUE;

    /**
     * Execute at the beginning of the interception chain.
     */
    int PRIORITY_HIGHEST = Integer.MAX_VALUE;

    // ------------------------------------------------------------------
    // API 100: before / after hook callbacks
    // ------------------------------------------------------------------

    /**
     * Contextual interface for before invocation callbacks.
     */
    interface BeforeHookCallback {
        /**
         * Gets the method / constructor to be hooked.
         */
        @NonNull
        Member getMember();

        /**
         * Gets the {@code this} object, or {@code null} if the method is static.
         */
        @Nullable
        Object getThisObject();

        /**
         * Gets the arguments passed to the method / constructor. You can modify the arguments.
         */
        @NonNull
        Object[] getArgs();

        /**
         * Sets the return value of the method and skip the invocation.
         */
        void returnAndSkip(@Nullable Object result);

        /**
         * Throw an exception from the method / constructor and skip the invocation.
         */
        void throwAndSkip(@Nullable Throwable throwable);
    }

    /**
     * Contextual interface for after invocation callbacks.
     */
    interface AfterHookCallback {
        /**
         * Gets the method / constructor to be hooked.
         */
        @NonNull
        Member getMember();

        /**
         * Gets the {@code this} object, or {@code null} if the method is static.
         */
        @Nullable
        Object getThisObject();

        /**
         * Gets all arguments passed to the method / constructor.
         */
        @NonNull
        Object[] getArgs();

        /**
         * Gets the return value of the method or the before invocation callback.
         */
        @Nullable
        Object getResult();

        /**
         * Gets the exception thrown by the method / constructor or the before invocation callback.
         */
        @Nullable
        Throwable getThrowable();

        /**
         * Gets whether the invocation was skipped by the before invocation callback.
         */
        boolean isSkipped();

        /**
         * Sets the return value of the method and skip the invocation.
         */
        void setResult(@Nullable Object result);

        /**
         * Sets the exception thrown by the method / constructor.
         */
        void setThrowable(@Nullable Throwable throwable);
    }

    /**
     * Handle for canceling a hook (API 100).
     *
     * @param <T> {@link Method} or {@link Constructor}
     */
    interface MethodUnhooker<T> {
        /**
         * Gets the method or constructor being hooked.
         */
        @NonNull
        T getOrigin();

        /**
         * Cancels the hook.
         */
        void unhook();
    }

    // ------------------------------------------------------------------
    // API 101 hook model
    // ------------------------------------------------------------------

    /**
     * Invoker for a method or constructor. Invocations through invokers will bypass access checks.
     */
    interface Invoker<T extends Invoker<T, U>, U extends Executable> {
        /**
         * Type of the invoker, which determines the hook chain to be invoked.
         */
        sealed interface Type permits Type.Origin, Type.Chain {
            /**
             * A convenience constant for {@link Origin}.
             */
            Origin ORIGIN = new Origin();

            /**
             * Invokes the original executable, skipping all hooks.
             */
            record Origin() implements Type {
            }

            /**
             * Invokes the executable starting from the middle of the hook chain, skipping all
             * hooks with priority higher than the given value.
             */
            record Chain(int maxPriority) implements Type {
                /**
                 * Invoking the executable with full hook chain.
                 */
                public static final Chain FULL = new Chain(PRIORITY_HIGHEST);
            }
        }

        /**
         * Sets the type of the invoker, which determines the hook chain to be invoked.
         */
        T setType(@NonNull Type type);

        /**
         * Invokes the method (or the constructor as a method) through the hook chain determined by
         * the invoker's type.
         */
        Object invoke(Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException;

        /**
         * Invokes the special (non-virtual) method (or the constructor as a method) on a given
         * object instance, bypassing overridden methods in subclasses.
         */
        Object invokeSpecial(@NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException;
    }

    /**
     * Invoker for a constructor.
     */
    interface CtorInvoker<T> extends Invoker<CtorInvoker<T>, Constructor<T>> {
        /**
         * Creates a new instance through the hook chain determined by the invoker's type.
         */
        @NonNull
        T newInstance(Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException;

        /**
         * Creates a new instance of the given subclass, but initializes it with a parent constructor.
         */
        @NonNull
        <U> U newInstanceSpecial(@NonNull Class<U> subClass, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException;
    }

    /**
     * Interceptor chain for a method or constructor. Chain objects cannot be shared among threads or
     * reused after {@link Hooker#intercept(Chain)} ends.
     */
    interface Chain {
        /**
         * Gets the method / constructor being hooked.
         */
        @NonNull
        Executable getExecutable();

        /**
         * Gets the {@code this} pointer for the call, or {@code null} for static methods.
         */
        Object getThisObject();

        /**
         * Gets the arguments. The returned list is immutable.
         */
        @NonNull
        List<Object> getArgs();

        /**
         * Gets the argument at the given index.
         */
        Object getArg(int index) throws IndexOutOfBoundsException, ClassCastException;

        /**
         * Proceeds to the next interceptor in the chain with the same arguments and {@code this} pointer.
         */
        Object proceed() throws Throwable;

        /**
         * Proceeds to the next interceptor in the chain with the given arguments.
         */
        Object proceed(@NonNull Object[] args) throws Throwable;

        /**
         * Proceeds to the next interceptor in the chain with the same arguments and given {@code this} pointer.
         */
        Object proceedWith(@NonNull Object thisObject) throws Throwable;

        /**
         * Proceeds to the next interceptor in the chain with the given arguments and {@code this} pointer.
         */
        Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable;
    }

    /**
     * Hooker for a method or constructor.
     *
     * <p>Modules targeting API 101 implement {@link #intercept(Chain)}. Modules targeting API 100
     * instead provide public static {@code before} / {@code after} methods; the framework keeps
     * dispatching those through the API 100 path, so the {@code intercept} default below is never
     * invoked for them.</p>
     */
    interface Hooker {
        /**
         * Intercepts a method / constructor call.
         *
         * @param chain The interceptor chain for the call
         * @return The result to be returned from the interceptor
         * @throws Throwable Throw any exception from the interceptor
         */
        default Object intercept(@NonNull Chain chain) throws Throwable {
            throw new AbstractMethodError("Hooker does not implement intercept");
        }
    }

    /**
     * Handle for a hook (API 101).
     */
    interface HookHandle {
        /**
         * Gets the method / constructor being hooked.
         */
        @NonNull
        Executable getExecutable();

        /**
         * Cancels the hook. This method is idempotent.
         */
        void unhook();
    }

    /**
     * Exception handling mode for hookers (API 101).
     */
    enum ExceptionMode {
        /**
         * Follows the global exception mode configured in {@code module.prop}. Defaults to {@link #PROTECTIVE}
         * if not specified.
         */
        DEFAULT,
        /**
         * Any exception thrown by the <b>hooker</b> will be caught and logged, and the call will proceed as
         * if no hook exists.
         */
        PROTECTIVE,
        /**
         * Any exception thrown by the hooker will be propagated to the caller as usual.
         */
        PASSTHROUGH,
    }

    /**
     * Builder for configuring a hook (API 101).
     */
    interface HookBuilder {
        /**
         * Sets the priority of the hook.
         */
        HookBuilder setPriority(int priority);

        /**
         * Sets the exception handling mode for the hook.
         */
        HookBuilder setExceptionMode(@NonNull ExceptionMode mode);

        /**
         * Sets the hooker for the method / constructor and builds the hook.
         */
        @NonNull
        HookHandle intercept(@NonNull Hooker hooker);
    }

    // ------------------------------------------------------------------
    // Framework details
    // ------------------------------------------------------------------

    /**
     * Gets the runtime Xposed API version. Framework implementations must <b>not</b> override this method.
     */
    default int getApiVersion() {
        return LIB_API;
    }

    /**
     * Gets the Xposed framework name of current implementation.
     */
    @NonNull
    String getFrameworkName();

    /**
     * Gets the Xposed framework version of current implementation.
     */
    @NonNull
    String getFrameworkVersion();

    /**
     * Gets the Xposed framework version code of current implementation.
     */
    long getFrameworkVersionCode();

    /**
     * Gets the Xposed framework properties (API 101).
     * Properties with prefix {@code PROP_RT_} may change among launches.
     */
    long getFrameworkProperties();

    /**
     * Gets the Xposed framework privilege of current implementation (API 100).
     */
    int getFrameworkPrivilege();

    // ------------------------------------------------------------------
    // Hooking (API 101)
    // ------------------------------------------------------------------

    /**
     * Hook a method / constructor (API 101).
     *
     * @param origin The executable to be hooked
     * @return The builder for the hook
     */
    @NonNull
    HookBuilder hook(@NonNull Executable origin);

    /**
     * Hook the static initializer of a class (API 101).
     *
     * @param origin The class whose static initializer is to be hooked
     * @return The builder for the hook
     */
    @NonNull
    HookBuilder hookClassInitializer(@NonNull Class<?> origin);

    /**
     * Deoptimizes a method / constructor in case hooked callee is not called because of inline (API 101).
     */
    boolean deoptimize(@NonNull Executable executable);

    /**
     * Get a method invoker for the given method (API 101).
     */
    @NonNull
    Invoker<?, Method> getInvoker(@NonNull Method method);

    /**
     * Get a constructor invoker for the given constructor (API 101).
     */
    @NonNull
    <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor);

    // ------------------------------------------------------------------
    // Hooking (API 100)
    // ------------------------------------------------------------------

    /**
     * Hook a method with default priority (API 100).
     */
    @NonNull
    MethodUnhooker<Method> hook(@NonNull Method origin, @NonNull Class<? extends Hooker> hooker);

    /**
     * Hook a method with specified priority (API 100).
     */
    @NonNull
    MethodUnhooker<Method> hook(@NonNull Method origin, int priority, @NonNull Class<? extends Hooker> hooker);

    /**
     * Hook a constructor with default priority (API 100).
     */
    @NonNull
    <T> MethodUnhooker<Constructor<T>> hook(@NonNull Constructor<T> origin, @NonNull Class<? extends Hooker> hooker);

    /**
     * Hook a constructor with specified priority (API 100).
     */
    @NonNull
    <T> MethodUnhooker<Constructor<T>> hook(@NonNull Constructor<T> origin, int priority, @NonNull Class<? extends Hooker> hooker);

    /**
     * Hook the static initializer of a class with default priority (API 100).
     */
    @NonNull
    <T> MethodUnhooker<Constructor<T>> hookClassInitializer(@NonNull Class<T> origin, @NonNull Class<? extends Hooker> hooker);

    /**
     * Hook the static initializer of a class with specified priority (API 100).
     */
    @NonNull
    <T> MethodUnhooker<Constructor<T>> hookClassInitializer(@NonNull Class<T> origin, int priority, @NonNull Class<? extends Hooker> hooker);

    /**
     * Deoptimize a method to avoid callee being inlined (API 100).
     */
    boolean deoptimize(@NonNull Method method);

    /**
     * Deoptimize a constructor to avoid callee being inlined (API 100).
     */
    <T> boolean deoptimize(@NonNull Constructor<T> constructor);

    /**
     * Basically the same as {@link Method#invoke}, but calls the original method
     * as it was before the interception by Xposed (API 100).
     */
    @Nullable
    Object invokeOrigin(@NonNull Method method, @Nullable Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException;

    /**
     * Basically the same as {@link Constructor#newInstance}, but calls the original constructor
     * as it was before the interception by Xposed (API 100).
     */
    <T> void invokeOrigin(@NonNull Constructor<T> constructor, @NonNull T thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException;

    /**
     * Invokes a special (non-virtual) method on a given object instance (API 100).
     */
    @Nullable
    Object invokeSpecial(@NonNull Method method, @NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException;

    /**
     * Invokes a special (non-virtual) constructor on a given object instance (API 100).
     */
    <T> void invokeSpecial(@NonNull Constructor<T> constructor, @NonNull T thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException;

    /**
     * Basically the same as {@link Constructor#newInstance}, but calls the original constructor
     * as it was before the interception by Xposed (API 100).
     */
    @NonNull
    <T> T newInstanceOrigin(@NonNull Constructor<T> constructor, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException;

    /**
     * Creates a new instance of the given subclass, but initialize it with a parent constructor (API 100).
     */
    @NonNull
    <T, U> U newInstanceSpecial(@NonNull Constructor<T> constructor, @NonNull Class<U> subClass, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException;

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    /**
     * Writes a message to the Xposed log (API 101).
     */
    void log(int priority, @Nullable String tag, @NonNull String msg);

    /**
     * Writes a message to the Xposed log.
     */
    void log(int priority, @Nullable String tag, @NonNull String msg, @Nullable Throwable tr);

    /**
     * Writes a message to the Xposed log (API 100).
     *
     * @deprecated Use {@link #log(int, String, String, Throwable)} instead.
     */
    @Deprecated
    void log(@NonNull String message);

    /**
     * Writes a message with a stack trace to the Xposed log (API 100).
     *
     * @deprecated Use {@link #log(int, String, String, Throwable)} instead.
     */
    @Deprecated
    void log(@NonNull String message, @NonNull Throwable throwable);

    // ------------------------------------------------------------------
    // Module info
    // ------------------------------------------------------------------

    /**
     * Gets the application info of the module (API 101).
     */
    @NonNull
    ApplicationInfo getModuleApplicationInfo();

    /**
     * Gets the application info of the module (API 100).
     */
    @NonNull
    ApplicationInfo getApplicationInfo();

    /**
     * Parse a dex file in memory (API 100).
     */
    @Nullable
    DexParser parseDex(@NonNull ByteBuffer dexData, boolean includeAnnotations) throws IOException;

    // ------------------------------------------------------------------
    // Remote data
    // ------------------------------------------------------------------

    /**
     * Gets remote preferences stored in Xposed framework. Note that those are read-only in hooked apps.
     */
    @NonNull
    SharedPreferences getRemotePreferences(@NonNull String group);

    /**
     * List all files in the module's shared data directory.
     */
    @NonNull
    String[] listRemoteFiles();

    /**
     * Open a file in the module's shared data directory. The file is opened in read-only mode.
     */
    @NonNull
    ParcelFileDescriptor openRemoteFile(@NonNull String name) throws FileNotFoundException;
}