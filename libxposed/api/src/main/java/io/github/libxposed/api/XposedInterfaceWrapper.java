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
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import io.github.libxposed.api.utils.DexParser;

/**
 * Wrapper of {@link XposedInterface} used by modules to shield framework implementation details.
 *
 * <p>Superset of the API 100 and API 101 wrappers: the base interface can be supplied either
 * through the package-private constructor (API 100) or through {@link #attachFramework(XposedInterface)}
 * (API 101).</p>
 */
public class XposedInterfaceWrapper implements XposedInterface {

    private volatile XposedInterface mBase;

    /**
     * Instantiates a wrapper without a base (API 101 style); the framework will attach the base
     * through {@link #attachFramework(XposedInterface)}.
     */
    public XposedInterfaceWrapper() {
    }

    /**
     * Instantiates a wrapper with a base (API 100 style).
     */
    XposedInterfaceWrapper(@NonNull XposedInterface base) {
        mBase = base;
    }

    /**
     * Attaches the framework interface to the module. Modules should never call this method.
     *
     * @param base The framework interface
     */
    @SuppressWarnings("unused")
    public final void attachFramework(@NonNull XposedInterface base) {
        if (mBase != null) {
            throw new IllegalStateException("Framework already attached");
        }
        mBase = base;
    }

    private void ensureAttached() {
        if (mBase == null) {
            throw new IllegalStateException("Framework not attached");
        }
    }

    @Override
    public final int getApiVersion() {
        ensureAttached();
        return XposedInterface.super.getApiVersion();
    }

    @NonNull
    @Override
    public final String getFrameworkName() {
        ensureAttached();
        return mBase.getFrameworkName();
    }

    @NonNull
    @Override
    public final String getFrameworkVersion() {
        ensureAttached();
        return mBase.getFrameworkVersion();
    }

    @Override
    public final long getFrameworkVersionCode() {
        ensureAttached();
        return mBase.getFrameworkVersionCode();
    }

    @Override
    public final long getFrameworkProperties() {
        ensureAttached();
        return mBase.getFrameworkProperties();
    }

    @Override
    public final int getFrameworkPrivilege() {
        ensureAttached();
        return mBase.getFrameworkPrivilege();
    }

    // API 101 hooking

    @NonNull
    @Override
    public final HookBuilder hook(@NonNull Executable origin) {
        ensureAttached();
        return mBase.hook(origin);
    }

    @NonNull
    @Override
    public final HookBuilder hookClassInitializer(@NonNull Class<?> origin) {
        ensureAttached();
        return mBase.hookClassInitializer(origin);
    }

    @Override
    public final boolean deoptimize(@NonNull Executable executable) {
        ensureAttached();
        return mBase.deoptimize(executable);
    }

    @NonNull
    @Override
    public final Invoker<?, Method> getInvoker(@NonNull Method method) {
        ensureAttached();
        return mBase.getInvoker(method);
    }

    @NonNull
    @Override
    public final <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor) {
        ensureAttached();
        return mBase.getInvoker(constructor);
    }

    // API 100 hooking

    @NonNull
    @Override
    public final MethodUnhooker<Method> hook(@NonNull Method origin, @NonNull Class<? extends Hooker> hooker) {
        ensureAttached();
        return mBase.hook(origin, hooker);
    }

    @NonNull
    @Override
    public final MethodUnhooker<Method> hook(@NonNull Method origin, int priority, @NonNull Class<? extends Hooker> hooker) {
        ensureAttached();
        return mBase.hook(origin, priority, hooker);
    }

    @NonNull
    @Override
    public final <T> MethodUnhooker<Constructor<T>> hook(@NonNull Constructor<T> origin, @NonNull Class<? extends Hooker> hooker) {
        ensureAttached();
        return mBase.hook(origin, hooker);
    }

    @NonNull
    @Override
    public final <T> MethodUnhooker<Constructor<T>> hook(@NonNull Constructor<T> origin, int priority, @NonNull Class<? extends Hooker> hooker) {
        ensureAttached();
        return mBase.hook(origin, priority, hooker);
    }

    @NonNull
    @Override
    public final <T> MethodUnhooker<Constructor<T>> hookClassInitializer(@NonNull Class<T> origin, @NonNull Class<? extends Hooker> hooker) {
        ensureAttached();
        return mBase.hookClassInitializer(origin, hooker);
    }

    @NonNull
    @Override
    public final <T> MethodUnhooker<Constructor<T>> hookClassInitializer(@NonNull Class<T> origin, int priority, @NonNull Class<? extends Hooker> hooker) {
        ensureAttached();
        return mBase.hookClassInitializer(origin, priority, hooker);
    }

    @Override
    public final boolean deoptimize(@NonNull Method method) {
        ensureAttached();
        return mBase.deoptimize(method);
    }

    @Override
    public final <T> boolean deoptimize(@NonNull Constructor<T> constructor) {
        ensureAttached();
        return mBase.deoptimize(constructor);
    }

    @Nullable
    @Override
    public final Object invokeOrigin(@NonNull Method method, @Nullable Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        ensureAttached();
        return mBase.invokeOrigin(method, thisObject, args);
    }

    @Override
    public final <T> void invokeOrigin(@NonNull Constructor<T> constructor, @NonNull T thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        ensureAttached();
        mBase.invokeOrigin(constructor, thisObject, args);
    }

    @Nullable
    @Override
    public final Object invokeSpecial(@NonNull Method method, @NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        ensureAttached();
        return mBase.invokeSpecial(method, thisObject, args);
    }

    @Override
    public final <T> void invokeSpecial(@NonNull Constructor<T> constructor, @NonNull T thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
        ensureAttached();
        mBase.invokeSpecial(constructor, thisObject, args);
    }

    @NonNull
    @Override
    public final <T> T newInstanceOrigin(@NonNull Constructor<T> constructor, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
        ensureAttached();
        return mBase.newInstanceOrigin(constructor, args);
    }

    @NonNull
    @Override
    public final <T, U> U newInstanceSpecial(@NonNull Constructor<T> constructor, @NonNull Class<U> subClass, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
        ensureAttached();
        return mBase.newInstanceSpecial(constructor, subClass, args);
    }

    // Logging

    @Override
    public final void log(int priority, @Nullable String tag, @NonNull String msg) {
        ensureAttached();
        mBase.log(priority, tag, msg);
    }

    @Override
    public final void log(int priority, @Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        ensureAttached();
        mBase.log(priority, tag, msg, tr);
    }

    @Deprecated
    @Override
    public final void log(@NonNull String message) {
        ensureAttached();
        mBase.log(message);
    }

    @Deprecated
    @Override
    public final void log(@NonNull String message, @NonNull Throwable throwable) {
        ensureAttached();
        mBase.log(message, throwable);
    }

    // Module info

    @NonNull
    @Override
    public final ApplicationInfo getModuleApplicationInfo() {
        ensureAttached();
        return mBase.getModuleApplicationInfo();
    }

    @NonNull
    @Override
    public final ApplicationInfo getApplicationInfo() {
        ensureAttached();
        return mBase.getApplicationInfo();
    }

    @Nullable
    @Override
    public final DexParser parseDex(@NonNull ByteBuffer dexData, boolean includeAnnotations) throws IOException {
        ensureAttached();
        return mBase.parseDex(dexData, includeAnnotations);
    }

    // Remote data

    @NonNull
    @Override
    public final SharedPreferences getRemotePreferences(@NonNull String name) {
        ensureAttached();
        return mBase.getRemotePreferences(name);
    }

    @NonNull
    @Override
    public final String[] listRemoteFiles() {
        ensureAttached();
        return mBase.listRemoteFiles();
    }

    @NonNull
    @Override
    public final ParcelFileDescriptor openRemoteFile(@NonNull String name) throws FileNotFoundException {
        ensureAttached();
        return mBase.openRemoteFile(name);
    }
}