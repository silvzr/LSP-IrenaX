package org.lsposed.lspd.impl;

import androidx.annotation.NonNull;

import org.lsposed.lspd.nativebridge.HookBridge;
import org.lsposed.lspd.util.Utils.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import de.robv.android.xposed.XposedBridge;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.errors.HookFailedError;

public class LSPosedBridge {

    private static final String TAG = "LSPosed-Bridge";

    private static final String castException = "Return value's type from hook callback does not match the hooked method";
    private static final Object[] EMPTY_ARRAY = new Object[0];

    private static final Method getCause;

    static {
        Method tmp;
        try {
            tmp = InvocationTargetException.class.getMethod("getCause");
        } catch (Throwable e) {
            tmp = null;
        }
        getCause = tmp;
    }

    public static class HookerCallback {
        @NonNull
        final Method beforeInvocation;
        @NonNull
        final Method afterInvocation;

        final int beforeParams;
        final int afterParams;

        public HookerCallback(@NonNull Method beforeInvocation, @NonNull Method afterInvocation) {
            this.beforeInvocation = beforeInvocation;
            this.afterInvocation = afterInvocation;
            this.beforeParams = beforeInvocation.getParameterCount();
            this.afterParams = afterInvocation.getParameterCount();
        }
    }

    public static void log(String text) {
        Log.i(TAG, text);
    }

    public static void log(Throwable t) {
        String logStr = Log.getStackTraceString(t);
        Log.e(TAG, logStr);
    }

    public static class NativeHooker<T extends Executable> {
        private final Object params;

        private NativeHooker(Executable method) {
            var isStatic = Modifier.isStatic(method.getModifiers());
            Object returnType;
            if (method instanceof Method) {
                returnType = ((Method) method).getReturnType();
            } else {
                returnType = null;
            }
            params = new Object[]{
                    method,
                    returnType,
                    isStatic,
            };
        }

        // This method is quite critical. We should try not to use system methods to avoid
        // endless recursive
        public Object callback(Object[] args) throws Throwable {
            var array = ((Object[]) params);

            var method = (T) array[0];
            var returnType = (Class<?>) array[1];
            var isStatic = (Boolean) array[2];

            Object thisObject;
            Object[] methodArgs;
            if (isStatic) {
                thisObject = null;
                methodArgs = args;
            } else {
                thisObject = args[0];
                methodArgs = new Object[args.length - 1];
                //noinspection ManualArrayCopy
                for (int i = 0; i < args.length - 1; ++i) {
                    methodArgs[i] = args[i + 1];
                }
            }

            Object[][] callbacksSnapshot = HookBridge.callbackSnapshot(HookerCallback.class, method);
            Object[] modernSnapshot = callbacksSnapshot[0];
            Object[] legacySnapshot = callbacksSnapshot[1];
            Object[] api101Snapshot = callbacksSnapshot[2];

            if (api101Snapshot.length != 0) {
                // API 101 hooks run as the outermost chain; the terminal proceed() falls through to
                // the API 100 + classic dispatch, so the original method still runs exactly once.
                var chain = new ChainImpl<>(method, returnType, api101Snapshot, thisObject, methodArgs,
                        (chainThis, chainArgs) -> runModernAndLegacy(
                                method, returnType, chainThis, chainArgs, modernSnapshot, legacySnapshot));
                try {
                    return chain.proceed();
                } finally {
                    chain.close();
                }
            }
            return runModernAndLegacy(method, returnType, thisObject, methodArgs, modernSnapshot, legacySnapshot);
        }
    }

    /**
     * Runs the libxposed API 100 (before/after) and classic Xposed dispatch around a single
     * invocation of the original method.
     */
    private static <T extends Executable> Object runModernAndLegacy(
            T method, Class<?> returnType, Object thisObject, Object[] args,
            Object[] modernSnapshot, Object[] legacySnapshot) throws Throwable {
        LSPosedHookCallback<T> callback = new LSPosedHookCallback<>();
        callback.method = method;
        callback.thisObject = thisObject;
        callback.args = args;

        if (modernSnapshot.length == 0 && legacySnapshot.length == 0) {
            try {
                return HookBridge.invokeOriginalMethod(method, thisObject, args);
            } catch (InvocationTargetException ite) {
                throw (Throwable) HookBridge.invokeOriginalMethod(getCause, ite);
            }
        }

        Object[] ctxArray = new Object[modernSnapshot.length];
        XposedBridge.LegacyApiSupport<T> legacy = null;

        // call "before method" callbacks
        int beforeIdx;
        for (beforeIdx = 0; beforeIdx < modernSnapshot.length; beforeIdx++) {
            try {
                var hooker = (HookerCallback) modernSnapshot[beforeIdx];
                if (hooker.beforeParams == 0) {
                    ctxArray[beforeIdx] = hooker.beforeInvocation.invoke(null);
                } else {
                    ctxArray[beforeIdx] = hooker.beforeInvocation.invoke(null, callback);
                }
            } catch (Throwable t) {
                LSPosedBridge.log(t);

                // reset result (ignoring what the unexpectedly exiting callback did)
                callback.setResult(null);
                callback.isSkipped = false;
                continue;
            }

            if (callback.isSkipped) {
                // skip remaining "before" callbacks and corresponding "after" callbacks
                beforeIdx++;
                break;
            }
        }

        if (!callback.isSkipped && legacySnapshot.length != 0) {
            legacy = new XposedBridge.LegacyApiSupport<>(callback, legacySnapshot);
            legacy.handleBefore();
        }

        // call original method if not requested otherwise
        if (!callback.isSkipped) {
            try {
                var result = HookBridge.invokeOriginalMethod(method, thisObject, args);
                callback.setResult(result);
            } catch (InvocationTargetException e) {
                var throwable = (Throwable) HookBridge.invokeOriginalMethod(getCause, e);
                callback.setThrowable(throwable);
            }
        }

        // call "after method" callbacks
        for (int afterIdx = beforeIdx - 1; afterIdx >= 0; afterIdx--) {
            Object lastResult = callback.getResult();
            Throwable lastThrowable = callback.getThrowable();
            var hooker = (HookerCallback) modernSnapshot[afterIdx];
            try {
                if (hooker.afterParams == 0) {
                    hooker.afterInvocation.invoke(null);
                } else if (hooker.afterParams == 1) {
                    hooker.afterInvocation.invoke(null, callback);
                } else {
                    hooker.afterInvocation.invoke(null, callback, ctxArray[afterIdx]);
                }
            } catch (Throwable t) {
                LSPosedBridge.log(t);

                // reset to last result (ignoring what the unexpectedly exiting callback did)
                if (lastThrowable == null) {
                    callback.setResult(lastResult);
                } else {
                    callback.setThrowable(lastThrowable);
                }
            }
        }

        if (legacy != null) {
            legacy.handleAfter();
        }

        // return
        var t = callback.getThrowable();
        if (t != null) {
            throw t;
        } else {
            var result = callback.getResult();
            if (returnType != null && !returnType.isPrimitive() && !HookBridge.instanceOf(result, returnType)) {
                throw new ClassCastException(castException);
            }
            return result;
        }
    }

    public static void dummyCallback() {
    }

    // libxposed API 101 hooking engine (Chain / intercept / HookBuilder)

    @FunctionalInterface
    interface ChainTail {
        Object invoke(Object thisObject, Object[] args) throws Throwable;
    }

    static class ChainImpl<T extends Executable> implements XposedInterface.Chain {
        private final int threadId = HookBridge.gettid();
        private final T executable;
        private final Class<?> returnType;
        private final Object[] hookers;
        private final ChainTail tail;
        private int index = -1;
        private boolean active = true;
        private Object thisObject;
        private Object[] args;

        ChainImpl(T executable, Class<?> returnType, Object[] hookers,
                  Object thisObject, Object[] args, ChainTail tail) {
            this.executable = executable;
            this.returnType = returnType;
            this.hookers = hookers == null ? EMPTY_ARRAY : hookers;
            this.thisObject = thisObject;
            this.args = args == null ? EMPTY_ARRAY : args;
            this.tail = tail;
        }

        void close() {
            active = false;
        }

        private void checkActive() {
            if (HookBridge.gettid() != threadId) {
                throw new IllegalStateException("Chain must be accessed in the same thread as the hooked method");
            }
            if (!active) {
                throw new IllegalStateException("Chain cannot be used after the interception ends");
            }
        }

        @NonNull
        @Override
        public Executable getExecutable() {
            return executable;
        }

        @Override
        public Object getThisObject() {
            return thisObject;
        }

        @NonNull
        @Override
        public List<Object> getArgs() {
            return Collections.unmodifiableList(Arrays.asList(args));
        }

        @Override
        public Object getArg(int index) throws IndexOutOfBoundsException, ClassCastException {
            return args[index];
        }

        @Override
        public Object proceed() throws Throwable {
            checkActive();
            var next = ++index;
            try {
                if (next != hookers.length) {
                    Object result = ((XposedInterface.Hooker) hookers[next]).intercept(this);
                    return checkReturnType(result, returnType);
                }
                return checkReturnType(tail.invoke(thisObject, args), returnType);
            } finally {
                index--;
            }
        }

        @Override
        public Object proceed(@NonNull Object[] args) throws Throwable {
            var oldArgs = this.args;
            this.args = args;
            try {
                return proceed();
            } finally {
                this.args = oldArgs;
            }
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject) throws Throwable {
            var oldThisObject = this.thisObject;
            this.thisObject = thisObject;
            try {
                return proceed();
            } finally {
                this.thisObject = oldThisObject;
            }
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable {
            var oldThisObject = this.thisObject;
            var oldArgs = this.args;
            this.thisObject = thisObject;
            this.args = args;
            try {
                return proceed();
            } finally {
                this.thisObject = oldThisObject;
                this.args = oldArgs;
            }
        }
    }

    static class ProtectiveChain implements XposedInterface.Chain {
        private final XposedInterface.Chain base;
        private boolean proceeded;
        private Object result;
        private Throwable throwable;

        ProtectiveChain(XposedInterface.Chain base) {
            this.base = base;
        }

        @NonNull
        @Override
        public Executable getExecutable() {
            return base.getExecutable();
        }

        @Override
        public Object getThisObject() {
            return base.getThisObject();
        }

        @NonNull
        @Override
        public List<Object> getArgs() {
            return base.getArgs();
        }

        @Override
        public Object getArg(int index) throws IndexOutOfBoundsException, ClassCastException {
            return base.getArg(index);
        }

        @Override
        public Object proceed() throws Throwable {
            proceeded = true;
            try {
                result = base.proceed();
                return result;
            } catch (Throwable t) {
                throwable = t;
                throw t;
            }
        }

        @Override
        public Object proceed(@NonNull Object[] args) throws Throwable {
            proceeded = true;
            try {
                result = base.proceed(args);
                return result;
            } catch (Throwable t) {
                throwable = t;
                throw t;
            }
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject) throws Throwable {
            proceeded = true;
            try {
                result = base.proceedWith(thisObject);
                return result;
            } catch (Throwable t) {
                throwable = t;
                throw t;
            }
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable {
            proceeded = true;
            try {
                result = base.proceedWith(thisObject, args);
                return result;
            } catch (Throwable t) {
                throwable = t;
                throw t;
            }
        }
    }

    static class ProtectiveHooker implements XposedInterface.Hooker {
        private final XposedInterface context;
        private final XposedInterface.Hooker hooker;

        ProtectiveHooker(XposedInterface context, XposedInterface.Hooker hooker) {
            this.context = context;
            this.hooker = hooker;
        }

        @Override
        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
            var protectiveChain = new ProtectiveChain(chain);
            try {
                return hooker.intercept(protectiveChain);
            } catch (Throwable t) {
                if (protectiveChain.throwable == t) {
                    throw t;
                }
                context.log(android.util.Log.WARN, "ProtectiveHooker", "Exception in hooker", t);
                if (!protectiveChain.proceeded) {
                    return chain.proceed();
                }
                if (protectiveChain.throwable != null) {
                    throw protectiveChain.throwable;
                }
                return protectiveChain.result;
            }
        }
    }

    static class HookBuilderImpl implements XposedInterface.HookBuilder {
        private final XposedInterface context;
        private final Executable executable;
        private final XposedInterface.ExceptionMode defaultExceptionMode;
        private int priority = XposedInterface.PRIORITY_DEFAULT;
        private XposedInterface.ExceptionMode exceptionMode = XposedInterface.ExceptionMode.DEFAULT;

        HookBuilderImpl(XposedInterface context, Executable executable,
                        XposedInterface.ExceptionMode defaultExceptionMode) {
            this.context = context;
            this.executable = executable;
            this.defaultExceptionMode = defaultExceptionMode;
        }

        @Override
        public XposedInterface.HookBuilder setPriority(int priority) {
            this.priority = priority;
            return this;
        }

        @Override
        public XposedInterface.HookBuilder setExceptionMode(@NonNull XposedInterface.ExceptionMode mode) {
            this.exceptionMode = mode;
            return this;
        }

        @NonNull
        @Override
        public XposedInterface.HookHandle intercept(@NonNull XposedInterface.Hooker hooker) {
            if (exceptionMode == XposedInterface.ExceptionMode.PROTECTIVE
                    || exceptionMode == XposedInterface.ExceptionMode.DEFAULT
                    && defaultExceptionMode == XposedInterface.ExceptionMode.PROTECTIVE) {
                hooker = new ProtectiveHooker(context, hooker);
            }
            return doHook(executable, priority, hooker);
        }
    }

    static class HookHandleImpl implements XposedInterface.HookHandle {
        private final Executable executable;
        private final XposedInterface.Hooker hooker;

        HookHandleImpl(Executable executable, XposedInterface.Hooker hooker) {
            this.executable = executable;
            this.hooker = hooker;
        }

        @NonNull
        @Override
        public Executable getExecutable() {
            return executable;
        }

        @Override
        public void unhook() {
            HookBridge.unhookMethod(HookBridge.API_MODE_101, executable, hooker);
        }
    }

    static class BaseInvoker<T extends Executable> {
        final T executable;
        protected Object target;

        BaseInvoker(T executable) {
            this.executable = executable;
            this.target = XposedInterface.Invoker.Type.Chain.FULL;
            executable.setAccessible(true);
        }

        public Object invoke(Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            var type = (XposedInterface.Invoker.Type) target;
            Objects.requireNonNull(type);
            if (type instanceof XposedInterface.Invoker.Type.Origin) {
                return HookBridge.invokeOriginalMethod(executable, thisObject, args, executable instanceof Constructor);
            }
            if (!(type instanceof XposedInterface.Invoker.Type.Chain chainType)) {
                throw new IllegalStateException("Unknown invoker type");
            }
            return invokeChain(thisObject, args, chainType.maxPriority(), false);
        }

        public Object invokeSpecial(@NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            if (Modifier.isStatic(executable.getModifiers())) {
                throw new IllegalArgumentException("Cannot invoke special on static method: " + executable);
            }
            var type = (XposedInterface.Invoker.Type) target;
            Objects.requireNonNull(type);
            if (type instanceof XposedInterface.Invoker.Type.Origin) {
                try {
                    return HookBridge.invokeSpecialMethod(executable, null, thisObject, args);
                } catch (InstantiationException e) {
                    throw new InstantiationError(e.getMessage());
                }
            }
            if (!(type instanceof XposedInterface.Invoker.Type.Chain chainType)) {
                throw new IllegalStateException("Unknown invoker type");
            }
            return invokeChain(thisObject, args, chainType.maxPriority(), true);
        }

        Object invokeChain(Object thisObject, Object[] args, int maxPriority, boolean special)
                throws InvocationTargetException, IllegalAccessException {
            var hookers = HookBridge.callbackSnapshot101(executable, maxPriority);
            ChainTail tail;
            if (special) {
                tail = (thiz, chainArgs) -> {
                    try {
                        return HookBridge.invokeSpecialMethod(executable, null, thiz, chainArgs);
                    } catch (InstantiationException e) {
                        throw new InstantiationError(e.getMessage());
                    }
                };
            } else {
                tail = (thiz, chainArgs) -> invokeOriginal(executable, thiz, chainArgs, executable instanceof Constructor);
            }
            var chain = new ChainImpl<>(executable, returnTypeOf(executable), hookers, thisObject, args, tail);
            try {
                return chain.proceed();
            } catch (Error | RuntimeException | InvocationTargetException | IllegalAccessException e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            } finally {
                chain.close();
            }
        }

        void setInvokerType(@NonNull XposedInterface.Invoker.Type type) {
            target = type;
        }
    }

    static class MethodInvokerImpl extends BaseInvoker<Method> implements XposedInterface.Invoker<MethodInvokerImpl, Method> {
        MethodInvokerImpl(Method executable) {
            super(executable);
        }

        @Override
        public MethodInvokerImpl setType(@NonNull XposedInterface.Invoker.Type type) {
            setInvokerType(type);
            return this;
        }
    }

    static class CtorInvokerImpl<T> extends BaseInvoker<Constructor<T>> implements XposedInterface.CtorInvoker<T> {
        CtorInvokerImpl(Constructor<T> executable) {
            super(executable);
        }

        @NonNull
        @Override
        public T newInstance(Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
            var instance = HookBridge.allocateObject(executable.getDeclaringClass());
            invoke(instance, args);
            return instance;
        }

        @NonNull
        @Override
        public <U> U newInstanceSpecial(@NonNull Class<U> subClass, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
            var type = (XposedInterface.Invoker.Type) super.target;
            Objects.requireNonNull(type);
            if (type instanceof XposedInterface.Invoker.Type.Origin) {
                return (U) HookBridge.invokeSpecialMethod(executable, subClass, null, args);
            }
            if (!(type instanceof XposedInterface.Invoker.Type.Chain chainType)) {
                throw new IllegalStateException("Unknown invoker type");
            }
            var instance = HookBridge.allocateSpecialReceiver(executable, subClass);
            invokeChain(instance, args, chainType.maxPriority(), true);
            return instance;
        }

        @Override
        public XposedInterface.CtorInvoker<T> setType(@NonNull XposedInterface.Invoker.Type type) {
            setInvokerType(type);
            return this;
        }
    }

    public static XposedInterface.HookHandle doHook(
            Executable hookMethod,
            int priority,
            XposedInterface.Hooker hooker
    ) {
        if (Modifier.isAbstract(hookMethod.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + hookMethod);
        } else if (hookMethod.getDeclaringClass().getClassLoader() == LSPosedContext.class.getClassLoader()) {
            throw new IllegalArgumentException("Do not allow hooking inner methods");
        } else if (hookMethod.getDeclaringClass() == Method.class && hookMethod.getName().equals("invoke")) {
            throw new IllegalArgumentException("Cannot hook Method.invoke");
        } else if (hooker == null) {
            throw new IllegalArgumentException("hooker should not be null!");
        }

        if (HookBridge.hookMethod(HookBridge.API_MODE_101, hookMethod, LSPosedBridge.NativeHooker.class, priority, hooker)) {
            return new HookHandleImpl(hookMethod, hooker);
        }
        throw new io.github.libxposed.api.error.HookFailedError("Cannot hook " + hookMethod);
    }

    public static XposedInterface.HookBuilder newHookBuilder(
            XposedInterface context,
            Executable executable,
            XposedInterface.ExceptionMode defaultExceptionMode
    ) {
        Objects.requireNonNull(executable, "origin must not be null");
        return new HookBuilderImpl(context, executable, defaultExceptionMode);
    }

    public static XposedInterface.HookBuilder newClassInitializerHookBuilder(
            XposedInterface context,
            Class<?> clazz,
            XposedInterface.ExceptionMode defaultExceptionMode
    ) {
        Objects.requireNonNull(clazz, "origin must not be null");
        if (clazz.getClassLoader() == LSPosedContext.class.getClassLoader()) {
            throw new IllegalArgumentException("Do not allow hooking inner classes");
        }
        synchronized (clazz) {
            Method classInitializer = HookBridge.findClassInitializer(clazz);
            if (classInitializer == null) {
                throw new IllegalArgumentException("Cannot find class initializer for " + clazz);
            }
            return new HookBuilderImpl(context, classInitializer, defaultExceptionMode);
        }
    }

    public static boolean doDeoptimize(@NonNull Executable executable) {
        Objects.requireNonNull(executable, "executable must not be null");
        if (Modifier.isAbstract(executable.getModifiers())) {
            throw new IllegalArgumentException("Cannot deoptimize abstract methods: " + executable);
        } else if (Proxy.isProxyClass(executable.getDeclaringClass())) {
            throw new IllegalArgumentException("Cannot deoptimize methods from proxy class: " + executable);
        }
        return HookBridge.deoptimizeMethod(executable);
    }

    public static XposedInterface.Invoker<?, Method> newInvoker(@NonNull Method method) {
        Objects.requireNonNull(method, "method must not be null");
        return new MethodInvokerImpl(method);
    }

    public static <T> XposedInterface.CtorInvoker<T> newInvoker(@NonNull Constructor<T> constructor) {
        Objects.requireNonNull(constructor, "constructor must not be null");
        return new CtorInvokerImpl<>(constructor);
    }

    // libxposed API 100 hooking engine (before / after hooker classes)

    private static Class<?> returnTypeOf(Executable executable) {
        if (!(executable instanceof Method method)) {
            return null;
        }
        var returnType = method.getReturnType();
        return returnType.isPrimitive() ? null : returnType;
    }

    private static Object checkReturnType(Object result, Class<?> returnType) {
        // IsInstanceOf always fails for primitive types (there's no instance of boolean.class),
        // so skip the check for them like runModernAndLegacy does.
        if (returnType != null && !returnType.isPrimitive() && !HookBridge.instanceOf(result, returnType)) {
            throw new ClassCastException(castException);
        }
        return result;
    }

    private static Object unwrapInvocationTarget(InvocationTargetException e) throws Throwable {
        if (getCause == null) {
            throw e;
        }
        Throwable cause = (Throwable) HookBridge.invokeOriginalMethod(getCause, e, EMPTY_ARRAY, false);
        if (cause != null) {
            throw cause;
        }
        throw e;
    }

    private static <T extends Executable> Object invokeOriginal(
            T method,
            Object thisObject,
            Object[] args,
            boolean isConstructor
    ) throws Throwable {
        try {
            return HookBridge.invokeOriginalMethod(method, thisObject, args, isConstructor);
        } catch (InvocationTargetException e) {
            return unwrapInvocationTarget(e);
        }
    }

    public static <T extends Executable> XposedInterface.MethodUnhooker<T>
    doHook(T hookMethod, int priority, Class<? extends XposedInterface.Hooker> hooker) {
        if (Modifier.isAbstract(hookMethod.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + hookMethod);
        } else if (hookMethod.getDeclaringClass().getClassLoader() == LSPosedContext.class.getClassLoader()) {
            throw new IllegalArgumentException("Do not allow hooking inner methods");
        } else if (hookMethod.getDeclaringClass() == Method.class && hookMethod.getName().equals("invoke")) {
            throw new IllegalArgumentException("Cannot hook Method.invoke");
        } else if (hooker == null) {
            throw new IllegalArgumentException("hooker should not be null!");
        }

        Method beforeInvocation = null, afterInvocation = null;
        var modifiers = Modifier.PUBLIC | Modifier.STATIC;
        for (var method : hooker.getDeclaredMethods()) {

            if ((method.getName().equals("before") || method.getAnnotation(BeforeInvocation.class) != null)
                    && ((method.getModifiers() & modifiers) == modifiers)) {
                var params = method.getParameterTypes();
                if (params.length == 0 || params.length == 1
                        && params[0].equals(XposedInterface.BeforeHookCallback.class)) {
                    if (beforeInvocation != null) {
                        throw new IllegalArgumentException("More than one valid callback methods named before");
                    }
                    beforeInvocation = method;
                }
            }

            if ((method.getName().equals("after") || method.getAnnotation(AfterInvocation.class) != null)
                    && ((method.getModifiers() & modifiers) == modifiers) && method.getReturnType().equals(void.class)) {
                var params = method.getParameterTypes();
                if (params.length == 0 ||
                        ((params.length == 1 || params.length == 2) && params[0].equals(XposedInterface.AfterHookCallback.class))) {
                    if (afterInvocation != null) {
                        throw new IllegalArgumentException("More than one valid callback methods named after");
                    }
                    afterInvocation = method;
                }
            }
        }
        if (beforeInvocation == null && afterInvocation == null) {
            throw new IllegalArgumentException("No method annotated named before or after");
        }
        try {
            if (beforeInvocation == null) {
                beforeInvocation = LSPosedBridge.class.getMethod("dummyCallback");
            } else if (afterInvocation == null) {
                afterInvocation = LSPosedBridge.class.getMethod("dummyCallback");
            } else {
                var ret = beforeInvocation.getReturnType();
                var params = afterInvocation.getParameterTypes();
                if (ret != void.class && params.length == 2 && !ret.equals(params[1])) {
                    throw new IllegalArgumentException("before and after method format is invalid");
                }
            }
        } catch (NoSuchMethodException e) {
            throw new HookFailedError(e);
        }

        var callback = new LSPosedBridge.HookerCallback(beforeInvocation, afterInvocation);
        if (HookBridge.hookMethod(HookBridge.API_MODE_100, hookMethod, LSPosedBridge.NativeHooker.class, priority, callback)) {
            return new XposedInterface.MethodUnhooker<>() {
                @NonNull
                @Override
                public T getOrigin() {
                    return hookMethod;
                }

                @Override
                public void unhook() {
                    HookBridge.unhookMethod(HookBridge.API_MODE_100, hookMethod, callback);
                }
            };
        }
        throw new HookFailedError("Cannot hook " + hookMethod);
    }

    public static <T> XposedInterface.MethodUnhooker<Constructor<T>>
    doHookClassInitializer(Class<T> clazz, int priority, Class<? extends XposedInterface.Hooker> hooker) {
        if (clazz.getClassLoader() == LSPosedContext.class.getClassLoader()) {
            throw new IllegalArgumentException("Do not allow hooking inner classes");
        }
        synchronized (clazz) {
            Method classInitializer = HookBridge.findClassInitializer(clazz);
            if (classInitializer == null) {
                throw new IllegalArgumentException("Cannot find class initializer for " + clazz);
            }
            // API 100 sees the class initializer as Constructor<T>, the native side reports a
            // Method. the generic param is erased anyway, so the unchecked cast is safe, iirc.
            return (XposedInterface.MethodUnhooker<Constructor<T>>) (XposedInterface.MethodUnhooker<?>)
                    doHook(classInitializer, priority, hooker);
        }
    }
}