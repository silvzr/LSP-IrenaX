package org.lsposed.lspd.nativebridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import dalvik.annotation.optimization.FastNative;

public class HookBridge {

    /**
     * apiMode for {@link #hookMethod} / {@link #unhookMethod}:
     * {@link #API_MODE_LEGACY} = classic Xposed API (de.robv.android.xposed) callbacks,
     * {@link #API_MODE_100} = libxposed API 100 before/after hooker callbacks,
     * {@link #API_MODE_101} = libxposed API 101 intercept hookers.
     */
    public static final int API_MODE_LEGACY = 0;
    public static final int API_MODE_100 = 1;
    public static final int API_MODE_101 = 2;

    public static native boolean hookMethod(int apiMode, Executable hookMethod, Class<?> hooker, int priority, Object callback);

    public static native boolean unhookMethod(int apiMode, Executable hookMethod, Object callback);

    public static native boolean deoptimizeMethod(Executable method);

    public static native <T> T allocateObject(Class<T> clazz) throws InstantiationException;

    public static native <T> T allocateSpecialReceiver(Constructor<?> constructor, Class<T> clazz) throws InstantiationException;

    public static native Method findClassInitializer(Class<?> clazz);

    public static native Object invokeOriginalMethod(Executable method, Object thisObject, Object[] args, boolean isConstructor) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException;

    public static Object invokeOriginalMethod(Executable method, Object thisObject, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        return invokeOriginalMethod(method, thisObject, args,
                method instanceof Constructor && !Modifier.isStatic(method.getModifiers()));
    }

    public static native <T> Object invokeSpecialMethod(Executable method, Class<T> clazz, Object thisObject, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException;

    public static Object invokeSpecialMethod(Executable method, Object thisObject, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException {
        return invokeSpecialMethod(method, null, thisObject, args);
    }

    @FastNative
    public static native boolean instanceOf(Object obj, Class<?> clazz);

    @FastNative
    public static native boolean setTrusted(Object cookie);

    /**
     * Clears the final flag ART reads, so that reflection will write {@code field} again.
     *
     * Android 17 refuses every reflective write to a static final field however accessible the
     * {@link Field} is, and clearing the reflective copy's flag does not help because the check reads
     * the ArtField. The write itself stays with reflection, which keeps its conversions and its
     * exceptions; this only stops it being refused.
     *
     * {@code modifiers} must be {@code field.getModifiers()}. It is checked against the flags about to be written,
     * so a runtime that lays an ArtField out differently is left alone rather than corrupted.
     *
     * Returns false when the field is still final, which is the caller's cue to report the
     * failure it already had. The field stays writable afterwards.
     */
    public static native boolean makeFieldWritable(Field field, int modifiers);

    @FastNative
    public static native int gettid();

    /**
     * Snapshot of all registered callbacks for a hooked method.
     * Index 0: libxposed API 100 hooker callbacks, index 1: classic Xposed callbacks,
     * index 2: libxposed API 101 hookers.
     */
    public static native Object[][] callbackSnapshot(Class<?> hooker_callback, Executable method);

    /**
     * Snapshot of the libxposed API 101 hookers registered for a method whose priority is
     * not greater than {@code maxPriority}, in descending priority order.
     */
    public static native Object[] callbackSnapshot101(Executable method, int maxPriority);
}