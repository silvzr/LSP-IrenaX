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
 * Copyright (C) 2022 LSPosed Contributors
 */

#include "hook_bridge.h"
#include "native_util.h"
#include "lsplant.hpp"
#include <parallel_hashmap/phmap.h>
#include <limits>
#include <memory>
#include <shared_mutex>
#include <mutex>
#include <set>

using namespace lsplant;

namespace {
struct ModuleCallback {
    jmethodID before_method;
    jmethodID after_method;
};

struct HookItem {
    std::multimap<jint, jobject, std::greater<>> legacy_callbacks;
    std::multimap<jint, ModuleCallback, std::greater<>> modern_callbacks;
private:
    std::atomic<jobject> backup {nullptr};
    static_assert(decltype(backup)::is_always_lock_free);
    inline static jobject FAILED = reinterpret_cast<jobject>(std::numeric_limits<uintptr_t>::max());
public:
    jobject GetBackup() {
        backup.wait(nullptr, std::memory_order::acquire);
        if (auto bk = backup.load(std::memory_order_relaxed); bk != FAILED) {
            return bk;
        } else {
            return nullptr;
        }
    }
    void SetBackup(jobject newBackup) {
        jobject null = nullptr;
        backup.compare_exchange_strong(null, newBackup ? newBackup : FAILED,
                                       std::memory_order_acq_rel, std::memory_order_relaxed);
        backup.notify_all();
    }
};

template <class K, class V, class Hash = phmap::priv::hash_default_hash<K>,
        class Eq = phmap::priv::hash_default_eq<K>,
        class Alloc = phmap::priv::Allocator<phmap::priv::Pair<const K, V>>, size_t N = 4>
using SharedHashMap = phmap::parallel_flat_hash_map<K, V, Hash, Eq, Alloc, N, std::shared_mutex>;

SharedHashMap<jmethodID, std::unique_ptr<HookItem>> hooked_methods;

jmethodID callback_ctor = nullptr;
jfieldID before_method_field = nullptr;
jfieldID after_method_field = nullptr;

struct InvokeCache {
    jclass object_class;
    jclass executable_class;
    jclass method_class;
    jclass constructor_class;

    jclass boolean_class;
    jclass byte_class;
    jclass character_class;
    jclass double_class;
    jclass float_class;
    jclass integer_class;
    jclass long_class;
    jclass number_class;
    jclass short_class;
    jclass void_class;

    jclass void_type;
    jclass boolean_type;
    jclass byte_type;
    jclass char_type;
    jclass double_type;
    jclass float_type;
    jclass int_type;
    jclass long_type;
    jclass short_type;

    jmethodID executable_get_declaring_class;
    jmethodID executable_get_parameter_types;
    jmethodID method_get_return_type;
    jmethodID method_invoke;
    jmethodID constructor_new_instance;

    jmethodID boolean_value;
    jmethodID byte_value;
    jmethodID char_value;
    jmethodID double_value;
    jmethodID float_value;
    jmethodID int_value;
    jmethodID long_value;
    jmethodID short_value;

    jmethodID boolean_value_of;
    jmethodID byte_value_of;
    jmethodID char_value_of;
    jmethodID double_value_of;
    jmethodID float_value_of;
    jmethodID int_value_of;
    jmethodID long_value_of;
    jmethodID short_value_of;
};

jclass NewGlobalClassRef(JNIEnv *env, const char *name) {
    auto local = env->FindClass(name);
    if (local == nullptr) return nullptr;
    auto global = (jclass) env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    return global;
}

template <typename T>
T NewGlobalRef(JNIEnv *env, T local) {
    if (local == nullptr) return nullptr;
    auto global = (T) env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    return global;
}

jclass GetPrimitiveType(JNIEnv *env, jclass wrapper_class) {
    auto type_field = env->GetStaticFieldID(wrapper_class, "TYPE", "Ljava/lang/Class;");
    if (type_field == nullptr) return nullptr;
    return NewGlobalRef(env, (jclass) env->GetStaticObjectField(wrapper_class, type_field));
}

InvokeCache &GetInvokeCache(JNIEnv *env) {
    static auto cache = [env] {
        InvokeCache c {};
        c.object_class = NewGlobalClassRef(env, "java/lang/Object");
        c.executable_class = NewGlobalClassRef(env, "java/lang/reflect/Executable");
        c.method_class = NewGlobalClassRef(env, "java/lang/reflect/Method");
        c.constructor_class = NewGlobalClassRef(env, "java/lang/reflect/Constructor");
        c.boolean_class = NewGlobalClassRef(env, "java/lang/Boolean");
        c.byte_class = NewGlobalClassRef(env, "java/lang/Byte");
        c.character_class = NewGlobalClassRef(env, "java/lang/Character");
        c.double_class = NewGlobalClassRef(env, "java/lang/Double");
        c.float_class = NewGlobalClassRef(env, "java/lang/Float");
        c.integer_class = NewGlobalClassRef(env, "java/lang/Integer");
        c.long_class = NewGlobalClassRef(env, "java/lang/Long");
        c.number_class = NewGlobalClassRef(env, "java/lang/Number");
        c.short_class = NewGlobalClassRef(env, "java/lang/Short");
        c.void_class = NewGlobalClassRef(env, "java/lang/Void");

        c.void_type = GetPrimitiveType(env, c.void_class);
        c.boolean_type = GetPrimitiveType(env, c.boolean_class);
        c.byte_type = GetPrimitiveType(env, c.byte_class);
        c.char_type = GetPrimitiveType(env, c.character_class);
        c.double_type = GetPrimitiveType(env, c.double_class);
        c.float_type = GetPrimitiveType(env, c.float_class);
        c.int_type = GetPrimitiveType(env, c.integer_class);
        c.long_type = GetPrimitiveType(env, c.long_class);
        c.short_type = GetPrimitiveType(env, c.short_class);

        c.executable_get_declaring_class = env->GetMethodID(c.executable_class, "getDeclaringClass",
                                                            "()Ljava/lang/Class;");
        c.executable_get_parameter_types = env->GetMethodID(c.executable_class, "getParameterTypes",
                                                            "()[Ljava/lang/Class;");
        c.method_get_return_type = env->GetMethodID(c.method_class, "getReturnType", "()Ljava/lang/Class;");
        c.method_invoke = env->GetMethodID(c.method_class, "invoke",
                                           "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
        c.constructor_new_instance = env->GetMethodID(c.constructor_class, "newInstance",
                                                      "([Ljava/lang/Object;)Ljava/lang/Object;");
        c.boolean_value = env->GetMethodID(c.boolean_class, "booleanValue", "()Z");
        c.byte_value = env->GetMethodID(c.byte_class, "byteValue", "()B");
        c.char_value = env->GetMethodID(c.character_class, "charValue", "()C");
        c.double_value = env->GetMethodID(c.number_class, "doubleValue", "()D");
        c.float_value = env->GetMethodID(c.number_class, "floatValue", "()F");
        c.int_value = env->GetMethodID(c.number_class, "intValue", "()I");
        c.long_value = env->GetMethodID(c.number_class, "longValue", "()J");
        c.short_value = env->GetMethodID(c.number_class, "shortValue", "()S");
        c.boolean_value_of = env->GetStaticMethodID(c.boolean_class, "valueOf", "(Z)Ljava/lang/Boolean;");
        c.byte_value_of = env->GetStaticMethodID(c.byte_class, "valueOf", "(B)Ljava/lang/Byte;");
        c.char_value_of = env->GetStaticMethodID(c.character_class, "valueOf", "(C)Ljava/lang/Character;");
        c.double_value_of = env->GetStaticMethodID(c.double_class, "valueOf", "(D)Ljava/lang/Double;");
        c.float_value_of = env->GetStaticMethodID(c.float_class, "valueOf", "(F)Ljava/lang/Float;");
        c.int_value_of = env->GetStaticMethodID(c.integer_class, "valueOf", "(I)Ljava/lang/Integer;");
        c.long_value_of = env->GetStaticMethodID(c.long_class, "valueOf", "(J)Ljava/lang/Long;");
        c.short_value_of = env->GetStaticMethodID(c.short_class, "valueOf", "(S)Ljava/lang/Short;");
        return c;
    }();
    return cache;
}

void Throw(JNIEnv *env, const char *type, const char *message) {
    ScopedLocalRef<jclass> clazz(env, env->FindClass(type));
    if (clazz) env->ThrowNew(clazz.get(), message);
}

void ThrowArgumentTypeMismatch(JNIEnv *env) {
    Throw(env, "java/lang/IllegalArgumentException", "argument type mismatch");
}

jchar TypeShorty(JNIEnv *env, InvokeCache &cache, jclass type) {
    if (env->IsSameObject(type, cache.int_type)) return 'I';
    if (env->IsSameObject(type, cache.long_type)) return 'J';
    if (env->IsSameObject(type, cache.float_type)) return 'F';
    if (env->IsSameObject(type, cache.double_type)) return 'D';
    if (env->IsSameObject(type, cache.boolean_type)) return 'Z';
    if (env->IsSameObject(type, cache.byte_type)) return 'B';
    if (env->IsSameObject(type, cache.char_type)) return 'C';
    if (env->IsSameObject(type, cache.short_type)) return 'S';
    if (env->IsSameObject(type, cache.void_type)) return 'V';
    return 'L';
}

bool ThrowInvocationTargetException(JNIEnv *env) {
    ScopedLocalRef<jthrowable> exception(env, env->ExceptionOccurred());
    if (!exception) return false;

    env->ExceptionClear();
    ScopedLocalRef<jclass> clazz(env, env->FindClass("java/lang/reflect/InvocationTargetException"));
    if (!clazz) return true;

    auto constructor = env->GetMethodID(clazz.get(), "<init>", "(Ljava/lang/Throwable;)V");
    ScopedLocalRef<jobject> wrapped(env, env->NewObject(clazz.get(), constructor, exception.get()));
    if (wrapped) env->Throw((jthrowable) wrapped.get());
    return true;
}

bool UnboxPrimitiveArg(JNIEnv *env, InvokeCache &cache, jchar type, jobject element,
                       jvalue &value) {
    if (element == nullptr) {
        Throw(env, "java/lang/NullPointerException", "argument == null");
        return false;
    }
    auto is_boolean = env->IsInstanceOf(element, cache.boolean_class);
    auto is_byte = env->IsInstanceOf(element, cache.byte_class);
    auto is_char = env->IsInstanceOf(element, cache.character_class);
    auto is_double = env->IsInstanceOf(element, cache.double_class);
    auto is_float = env->IsInstanceOf(element, cache.float_class);
    auto is_int = env->IsInstanceOf(element, cache.integer_class);
    auto is_long = env->IsInstanceOf(element, cache.long_class);
    auto is_short = env->IsInstanceOf(element, cache.short_class);
    switch (type) {
        case 'Z':
            if (!is_boolean) {
                ThrowArgumentTypeMismatch(env);
                return false;
            }
            value.z = env->CallBooleanMethod(element, cache.boolean_value);
            return !env->ExceptionCheck();
        case 'B':
            if (!is_byte) {
                ThrowArgumentTypeMismatch(env);
                return false;
            }
            value.b = env->CallByteMethod(element, cache.byte_value);
            return !env->ExceptionCheck();
        case 'C':
            if (!is_char) {
                ThrowArgumentTypeMismatch(env);
                return false;
            }
            value.c = env->CallCharMethod(element, cache.char_value);
            return !env->ExceptionCheck();
        case 'S':
            if (is_byte) {
                value.s = env->CallByteMethod(element, cache.byte_value);
                return !env->ExceptionCheck();
            }
            if (is_short) {
                value.s = env->CallShortMethod(element, cache.short_value);
                return !env->ExceptionCheck();
            }
            ThrowArgumentTypeMismatch(env);
            return false;
        case 'I':
            if (is_char) {
                value.i = env->CallCharMethod(element, cache.char_value);
                return !env->ExceptionCheck();
            }
            if (is_byte || is_short || is_int) {
                value.i = env->CallIntMethod(element, cache.int_value);
                return !env->ExceptionCheck();
            }
            ThrowArgumentTypeMismatch(env);
            return false;
        case 'J':
            if (is_char) {
                value.j = env->CallCharMethod(element, cache.char_value);
                return !env->ExceptionCheck();
            }
            if (is_byte || is_short || is_int || is_long) {
                value.j = env->CallLongMethod(element, cache.long_value);
                return !env->ExceptionCheck();
            }
            ThrowArgumentTypeMismatch(env);
            return false;
        case 'F':
            if (is_char) {
                value.f = env->CallCharMethod(element, cache.char_value);
                return !env->ExceptionCheck();
            }
            if (is_byte || is_short || is_int || is_long || is_float) {
                value.f = env->CallFloatMethod(element, cache.float_value);
                return !env->ExceptionCheck();
            }
            ThrowArgumentTypeMismatch(env);
            return false;
        case 'D':
            if (is_char) {
                value.d = env->CallCharMethod(element, cache.char_value);
                return !env->ExceptionCheck();
            }
            if (is_byte || is_short || is_int || is_long || is_float || is_double) {
                value.d = env->CallDoubleMethod(element, cache.double_value);
                return !env->ExceptionCheck();
            }
            ThrowArgumentTypeMismatch(env);
            return false;
        default:
            ThrowArgumentTypeMismatch(env);
            return false;
    }
}

struct MappedArgs {
    JNIEnv *env;
    jsize length;
    jvalue *values;
    jboolean *local_refs;

    MappedArgs(JNIEnv *env, jsize length, jvalue *values, jboolean *local_refs)
        : env(env), length(length), values(values), local_refs(local_refs) {
        for (jsize i = 0; i != length; ++i) {
            this->local_refs[i] = JNI_FALSE;
        }
    }

    ~MappedArgs() {
        for (jsize i = 0; i != length; ++i) {
            if (local_refs[i] && values[i].l != nullptr) env->DeleteLocalRef(values[i].l);
        }
    }

    void KeepLocalRef(jsize index, jobject ref) {
        values[index].l = ref;
        local_refs[index] = JNI_TRUE;
    }
};

bool MapInvokeArgs(JNIEnv *env, InvokeCache &cache, jobjectArray parameter_types,
                   jobjectArray args, MappedArgs &mapped_args) {
    for (jsize i = 0; i != mapped_args.length; ++i) {
        ScopedLocalRef<jobject> element(env, args == nullptr ? nullptr : env->GetObjectArrayElement(args, i));
        if (env->ExceptionCheck()) return false;

        ScopedLocalRef<jclass> parameter_type(env, (jclass) env->GetObjectArrayElement(parameter_types, i));
        if (env->ExceptionCheck()) return false;

        auto type = TypeShorty(env, cache, parameter_type.get());
        if (type == 'L') {
            if (element && !env->IsInstanceOf(element.get(), parameter_type.get())) {
                ThrowArgumentTypeMismatch(env);
                return false;
            }
            mapped_args.KeepLocalRef(i, element.release());
        } else if (!UnboxPrimitiveArg(env, cache, type, element.get(), mapped_args.values[i])
                   || env->ExceptionCheck()) {
            return false;
        }
    }
    return true;
}

ScopedLocalRef<jobject> AllocateReceiver(JNIEnv *env, jclass cls) {
    return ScopedLocalRef<jobject>(env, env->AllocObject(cls));
}

jobject BoxPrimitiveResult(JNIEnv *env, InvokeCache &cache, jchar type, jvalue value) {
    switch (type) {
        case 'I':
            return env->CallStaticObjectMethod(cache.integer_class, cache.int_value_of, value.i);
        case 'D':
            return env->CallStaticObjectMethod(cache.double_class, cache.double_value_of, value.d);
        case 'J':
            return env->CallStaticObjectMethod(cache.long_class, cache.long_value_of, value.j);
        case 'F':
            return env->CallStaticObjectMethod(cache.float_class, cache.float_value_of, value.f);
        case 'S':
            return env->CallStaticObjectMethod(cache.short_class, cache.short_value_of, value.s);
        case 'B':
            return env->CallStaticObjectMethod(cache.byte_class, cache.byte_value_of, value.b);
        case 'C':
            return env->CallStaticObjectMethod(cache.character_class, cache.char_value_of, value.c);
        case 'Z':
            return env->CallStaticObjectMethod(cache.boolean_class, cache.boolean_value_of, value.z);
        default:
            return nullptr;
    }
}

jobject CallNonvirtualAndBox(JNIEnv *env, InvokeCache &cache, jchar return_type, jobject thiz,
                             jclass declaring_class, jmethodID target, jvalue *args) {
    jvalue result {};
    switch (return_type) {
        case 'I':
            result.i = env->CallNonvirtualIntMethodA(thiz, declaring_class, target, args);
            break;
        case 'D':
            result.d = env->CallNonvirtualDoubleMethodA(thiz, declaring_class, target, args);
            break;
        case 'J':
            result.j = env->CallNonvirtualLongMethodA(thiz, declaring_class, target, args);
            break;
        case 'F':
            result.f = env->CallNonvirtualFloatMethodA(thiz, declaring_class, target, args);
            break;
        case 'S':
            result.s = env->CallNonvirtualShortMethodA(thiz, declaring_class, target, args);
            break;
        case 'B':
            result.b = env->CallNonvirtualByteMethodA(thiz, declaring_class, target, args);
            break;
        case 'C':
            result.c = env->CallNonvirtualCharMethodA(thiz, declaring_class, target, args);
            break;
        case 'Z':
            result.z = env->CallNonvirtualBooleanMethodA(thiz, declaring_class, target, args);
            break;
        case 'L': {
            auto value = env->CallNonvirtualObjectMethodA(thiz, declaring_class, target, args);
            return ThrowInvocationTargetException(env) ? nullptr : value;
        }
        default:
        case 'V':
            env->CallNonvirtualVoidMethodA(thiz, declaring_class, target, args);
            ThrowInvocationTargetException(env);
            return nullptr;
    }

    if (ThrowInvocationTargetException(env)) return nullptr;
    return BoxPrimitiveResult(env, cache, return_type, result);
}

jobject InvokeSpecial(JNIEnv *env, jobject method, jclass alloc_class, jobject thiz, jobjectArray args) {
    auto &cache = GetInvokeCache(env);
    ScopedLocalRef<jclass> declaring_class(env, (jclass) env->CallObjectMethod(
            method, cache.executable_get_declaring_class));
    if (env->ExceptionCheck()) return nullptr;

    auto is_constructor = env->IsInstanceOf(method, cache.constructor_class);
    ScopedLocalRef<jobject> allocated_receiver(env);
    if (thiz == nullptr && is_constructor && alloc_class != nullptr) {
        allocated_receiver = AllocateReceiver(env, alloc_class);
        if (env->ExceptionCheck()) return nullptr;
        thiz = allocated_receiver.get();
    }
    if (thiz == nullptr) {
        Throw(env, "java/lang/NullPointerException", "null receiver");
        return nullptr;
    }
    if (!env->IsInstanceOf(thiz, declaring_class.get())) {
        Throw(env, "java/lang/IllegalArgumentException", "object is not an instance of declaring class");
        return nullptr;
    }

    ScopedLocalRef<jobjectArray> parameter_types(env, (jobjectArray) env->CallObjectMethod(
            method, cache.executable_get_parameter_types));
    if (env->ExceptionCheck()) return nullptr;

    auto target = env->FromReflectedMethod(method);
    if (env->ExceptionCheck()) return nullptr;

    auto param_len = env->GetArrayLength(parameter_types.get());
    auto args_len = args == nullptr ? 0 : env->GetArrayLength(args);
    if (args_len != param_len) {
        Throw(env, "java/lang/IllegalArgumentException", "args.length != parameters.length");
        return nullptr;
    }

    jvalue *mapped_values = nullptr;
    jboolean *local_refs = nullptr;
    if (param_len != 0) {
        mapped_values = static_cast<jvalue *>(__builtin_alloca(sizeof(jvalue) * param_len));
        local_refs = static_cast<jboolean *>(__builtin_alloca(sizeof(jboolean) * param_len));
    }
    MappedArgs mapped_args(env, param_len, mapped_values, local_refs);
    if (!MapInvokeArgs(env, cache, parameter_types.get(), args, mapped_args)) {
        return nullptr;
    }

    jchar return_type = 'V';
    if (!is_constructor) {
        ScopedLocalRef<jclass> return_class(env, (jclass) env->CallObjectMethod(
                method, cache.method_get_return_type));
        if (env->ExceptionCheck()) return nullptr;
        return_type = TypeShorty(env, cache, return_class.get());
    }

    auto value = CallNonvirtualAndBox(env, cache, return_type, thiz, declaring_class.get(), target,
                                      mapped_args.values);
    if (return_type == 'V' && is_constructor && alloc_class != nullptr && !env->ExceptionCheck()) {
        return allocated_receiver.release();
    }
    return value;
}

jobject InvokeBackup(JNIEnv *env, InvokeCache &cache, jobject executable, jobject backup,
                     jobject thiz, jobjectArray args, bool is_constructor) {
    if (!is_constructor || thiz != nullptr) {
        return env->CallObjectMethod(backup, cache.method_invoke, thiz, args);
    }

    ScopedLocalRef<jclass> declaring_class(env, (jclass) env->CallObjectMethod(
            executable, cache.executable_get_declaring_class));
    if (env->ExceptionCheck()) return nullptr;

    auto receiver = AllocateReceiver(env, declaring_class.get());
    if (env->ExceptionCheck()) return nullptr;

    env->CallObjectMethod(backup, cache.method_invoke, receiver.get(), args);
    if (env->ExceptionCheck()) return nullptr;
    return receiver.release();
}

jobject NewInstance(JNIEnv *env, InvokeCache &cache, jobject constructor, jobjectArray args) {
    ScopedLocalRef<jobjectArray> empty_args(env);
    if (args == nullptr) {
        empty_args.reset(env->NewObjectArray(0, cache.object_class, nullptr));
        args = empty_args.get();
    }
    return env->CallObjectMethod(constructor, cache.constructor_new_instance, args);
}
}

namespace lspd {
LSP_DEF_NATIVE_METHOD(jboolean, HookBridge, hookMethod, jboolean useModernApi, jobject hookMethod,
                      jclass hooker, jint priority, jobject callback) {
    bool newHook = false;
#ifndef NDEBUG
    struct finally {
        std::chrono::steady_clock::time_point start = std::chrono::steady_clock::now();
        bool &newHook;
        ~finally() {
            auto finish = std::chrono::steady_clock::now();
            if (newHook) {
                LOGV("New hook took {}us",
                     std::chrono::duration_cast<std::chrono::microseconds>(finish - start).count());
            }
        }
    } finally {
        .newHook = newHook
    };
#endif
    auto target = env->FromReflectedMethod(hookMethod);
    HookItem * hook_item = nullptr;
    hooked_methods.lazy_emplace_l(target, [&hook_item](auto &it) {
        hook_item = it.second.get();
    }, [&hook_item, &target, &newHook](const auto &ctor) {
        auto ptr = std::make_unique<HookItem>();
        hook_item = ptr.get();
        ctor(target, std::move(ptr));
        newHook = true;
    });
    if (newHook) {
        auto init = env->GetMethodID(hooker, "<init>", "(Ljava/lang/reflect/Executable;)V");
        auto callback_method = env->ToReflectedMethod(hooker, env->GetMethodID(hooker, "callback",
                                                                               "([Ljava/lang/Object;)Ljava/lang/Object;"),
                                                      false);
        auto hooker_object = env->NewObject(hooker, init, hookMethod);
        hook_item->SetBackup(lsplant::Hook(env, hookMethod, hooker_object, callback_method));
        env->DeleteLocalRef(hooker_object);
    }
    jobject backup = hook_item->GetBackup();
    if (!backup) return JNI_FALSE;
    JNIMonitor monitor(env, backup);
    if (useModernApi) {
        if (before_method_field == nullptr) {
            auto callback_class = JNI_GetObjectClass(env, callback);
            callback_ctor = JNI_GetMethodID(env, callback_class, "<init>", "(Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;)V");
            before_method_field = JNI_GetFieldID(env, callback_class, "beforeInvocation", "Ljava/lang/reflect/Method;");
            after_method_field = JNI_GetFieldID(env, callback_class, "afterInvocation", "Ljava/lang/reflect/Method;");
        }
        auto before_method = JNI_GetObjectField(env, callback, before_method_field);
        auto after_method = JNI_GetObjectField(env, callback, after_method_field);
        auto callback_type = ModuleCallback {
                .before_method = env->FromReflectedMethod(before_method.get()),
                .after_method = env->FromReflectedMethod(after_method.get()),
        };
        hook_item->modern_callbacks.emplace(priority, callback_type);
    } else {
        hook_item->legacy_callbacks.emplace(priority, env->NewGlobalRef(callback));
    }
    return JNI_TRUE;
}

LSP_DEF_NATIVE_METHOD(jboolean, HookBridge, unhookMethod, jboolean useModernApi, jobject hookMethod, jobject callback) {
    auto target = env->FromReflectedMethod(hookMethod);
    HookItem * hook_item = nullptr;
    hooked_methods.if_contains(target, [&hook_item](const auto &it) {
        hook_item = it.second.get();
    });
    if (!hook_item) return JNI_FALSE;
    jobject backup = hook_item->GetBackup();
    if (!backup) return JNI_FALSE;
    JNIMonitor monitor(env, backup);
    if (useModernApi) {
        auto before_method = JNI_GetObjectField(env, callback, before_method_field);
        auto before = env->FromReflectedMethod(before_method.get());
        for (auto i = hook_item->modern_callbacks.begin(); i != hook_item->modern_callbacks.end(); ++i) {
            if (before == i->second.before_method) {
                hook_item->modern_callbacks.erase(i);
                return JNI_TRUE;
            }
        }
    } else {
        for (auto i = hook_item->legacy_callbacks.begin(); i != hook_item->legacy_callbacks.end(); ++i) {
            if (env->IsSameObject(i->second, callback)) {
                env->DeleteGlobalRef(i->second);
                hook_item->legacy_callbacks.erase(i);
                return JNI_TRUE;
            }
        }
    }
    return JNI_FALSE;
}

LSP_DEF_NATIVE_METHOD(jboolean, HookBridge, deoptimizeMethod, jobject hookMethod,
                      jclass hooker, jint priority, jobject callback) {
    return lsplant::Deoptimize(env, hookMethod);
}

LSP_DEF_NATIVE_METHOD(jobject, HookBridge, invokeOriginalMethod, jobject hookMethod,
                      jobject thiz, jobjectArray args, jboolean isConstructor) {
    auto &cache = GetInvokeCache(env);
    auto target = env->FromReflectedMethod(hookMethod);
    if (env->ExceptionCheck()) return nullptr;
    HookItem * hook_item = nullptr;
    hooked_methods.if_contains(target, [&hook_item](const auto &it) {
        hook_item = it.second.get();
    });
    if (hook_item) {
        if (auto backup = hook_item->GetBackup()) {
            return InvokeBackup(env, cache, hookMethod, backup, thiz, args, isConstructor);
        }
    }
    if (isConstructor) {
        if (thiz == nullptr) {
            return NewInstance(env, cache, hookMethod, args);
        }
        return InvokeSpecial(env, hookMethod, nullptr, thiz, args);
    }
    return env->CallObjectMethod(hookMethod, cache.method_invoke, thiz, args);
}

LSP_DEF_NATIVE_METHOD(jobject, HookBridge, findClassInitializer, jclass cls) {
    auto clinit = env->GetStaticMethodID(cls, "<clinit>", "()V");
    return env->ToReflectedMethod(cls, clinit, JNI_TRUE);
}

LSP_DEF_NATIVE_METHOD(jobject, HookBridge, allocateObject, jclass cls) {
    return env->AllocObject(cls);
}

LSP_DEF_NATIVE_METHOD(jobject, HookBridge, invokeSpecialMethod, jobject method, jclass cls,
                      jobject thiz, jobjectArray args) {
    return InvokeSpecial(env, method, cls, thiz, args);
}

LSP_DEF_NATIVE_METHOD(jboolean, HookBridge, instanceOf, jobject object, jclass expected_class) {
    return env->IsInstanceOf(object, expected_class);
}

LSP_DEF_NATIVE_METHOD(jboolean, HookBridge, setTrusted, jobject cookie) {
    return lsplant::MakeDexFileTrusted(env, cookie);
}

LSP_DEF_NATIVE_METHOD(jobjectArray, HookBridge, callbackSnapshot, jclass callback_class, jobject method) {
    auto target = env->FromReflectedMethod(method);
    HookItem *hook_item = nullptr;
    hooked_methods.if_contains(target, [&hook_item](const auto &it) {
        hook_item = it.second.get();
    });
    if (!hook_item) return nullptr;
    jobject backup = hook_item->GetBackup();
    if (!backup) return nullptr;
    JNIMonitor monitor(env, backup);

    auto res = env->NewObjectArray(2, env->FindClass("[Ljava/lang/Object;"), nullptr);
    auto modern = env->NewObjectArray((jsize) hook_item->modern_callbacks.size(), env->FindClass("java/lang/Object"), nullptr);
    auto legacy = env->NewObjectArray((jsize) hook_item->legacy_callbacks.size(), env->FindClass("java/lang/Object"), nullptr);
    for (jsize i = 0; auto callback: hook_item->modern_callbacks) {
        auto before_method = JNI_ToReflectedMethod(env, clazz, callback.second.before_method, JNI_TRUE);
        auto after_method = JNI_ToReflectedMethod(env, clazz, callback.second.after_method, JNI_TRUE);
        auto callback_object = JNI_NewObject(env, callback_class, callback_ctor, before_method, after_method);
        env->SetObjectArrayElement(modern, i++, env->NewLocalRef(callback_object.get()));
    }
    for (jsize i = 0; auto callback: hook_item->legacy_callbacks) {
        env->SetObjectArrayElement(legacy, i++, env->NewLocalRef(callback.second));
    }
    env->SetObjectArrayElement(res, 0, modern);
    env->SetObjectArrayElement(res, 1, legacy);
    return res;
}

static JNINativeMethod gMethods[] = {
    LSP_NATIVE_METHOD(HookBridge, hookMethod, "(ZLjava/lang/reflect/Executable;Ljava/lang/Class;ILjava/lang/Object;)Z"),
    LSP_NATIVE_METHOD(HookBridge, unhookMethod, "(ZLjava/lang/reflect/Executable;Ljava/lang/Object;)Z"),
    LSP_NATIVE_METHOD(HookBridge, deoptimizeMethod, "(Ljava/lang/reflect/Executable;)Z"),
    LSP_NATIVE_METHOD(HookBridge, invokeOriginalMethod,
                      "(Ljava/lang/reflect/Executable;Ljava/lang/Object;[Ljava/lang/Object;Z)Ljava/lang/Object;"),
    LSP_NATIVE_METHOD(
            HookBridge, invokeSpecialMethod,
            "(Ljava/lang/reflect/Executable;Ljava/lang/Class;"
            "Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
    LSP_NATIVE_METHOD(HookBridge, allocateObject, "(Ljava/lang/Class;)Ljava/lang/Object;"),
    LSP_NATIVE_METHOD(HookBridge, instanceOf, "(Ljava/lang/Object;Ljava/lang/Class;)Z"),
    LSP_NATIVE_METHOD(HookBridge, setTrusted, "(Ljava/lang/Object;)Z"),
    LSP_NATIVE_METHOD(HookBridge, callbackSnapshot, "(Ljava/lang/Class;Ljava/lang/reflect/Executable;)[[Ljava/lang/Object;"),
};

void RegisterHookBridge(JNIEnv *env) {
    REGISTER_LSP_NATIVE_METHODS(HookBridge);
}
} // namespace lspd
