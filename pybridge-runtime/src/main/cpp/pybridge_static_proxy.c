/**
 * PyBridge Static Proxy — JNI 实现
 *
 * 实现 Java 动态代理 → Python 方法调用的桥接。
 *
 * 工作流程：
 *   1. Python 端调用 pybridge_jni_create_proxy() 创建 Java 动态代理
 *   2. Java 端通过 java.lang.reflect.Proxy 创建代理实例
 *   3. 当 Java 调用代理方法时，InvocationHandler 回调到 JNI
 *   4. JNI 通过 pybridge_jni_proxy_invoke() 调用 Python 方法
 *   5. 返回值自动转换 Java ↔ Python
 *
 * 导出函数：
 *   pybridge_jni_create_proxy(proxy_id, iface_count, iface_names, method_count, method_names)
 *   pybridge_jni_release_proxy(proxy_id)
 *   pybridge_jni_proxy_invoke(proxy_id, method_name, arg_count, args)
 *   pybridge_jni_set_proxy_callback(callback)
 */

#include "pybridge_jni.h"
#include <string.h>
#include <stdlib.h>

/* ─── 全局回调 ───────────────────────────────────────────────────── */

/* Python 端的回调函数指针（通过 ctypes 注册） */
static PyObject *(*g_proxy_invoke_callback)(int, const char *, int, PyObject *) = NULL;

/* ─── 代理映射表 ─────────────────────────────────────────────────── */

#define MAX_PROXIES 512

typedef struct {
    int     proxy_id;          /* Python 端代理 ID */
    jobject java_proxy;        /* Java 代理对象 (global ref) */
    int     active;            /* 是否活跃 */
} ProxyMapping;

static ProxyMapping g_proxy_mappings[MAX_PROXIES];
static int g_proxy_count = 0;

static int proxy_mapping_find_free(void) {
    for (int i = 0; i < MAX_PROXIES; i++) {
        if (!g_proxy_mappings[i].active) return i;
    }
    return -1;
}

static int proxy_mapping_find_by_id(int proxy_id) {
    for (int i = 0; i < MAX_PROXIES; i++) {
        if (g_proxy_mappings[i].active && g_proxy_mappings[i].proxy_id == proxy_id) {
            return i;
        }
    }
    return -1;
}

/* ─── InvocationHandler JNI 实现 ────────────────────────────────── */

/**
 * Java InvocationHandler.invoke() 的 native 实现
 *
 * 当 Java 动态代理的方法被调用时，此方法被触发。
 * 它会回调到 Python 端执行实际方法。
 */
static jobject proxy_invoke_handler(
    JNIEnv *env,
    jobject proxy,
    jmethodID method,
    jobjectArray jargs)
{
    (void)proxy;

    /* 获取方法名 */
    jclass methodClass = (*env)->GetObjectClass(env, method);
    jmethodID getName = (*env)->GetMethodID(env, methodClass, "getName", "()Ljava/lang/String;");
    jstring jmethodName = (jstring)(*env)->CallObjectMethod(env, method, getName);

    /* 获取方法参数类型 */
    jmethodID getParameterTypes = (*env)->GetMethodID(env, methodClass, "getParameterTypes",
        "()[Ljava/lang/Class;");
    jobjectArray paramTypes = (jobjectArray)(*env)->CallObjectMethod(env, method, getParameterTypes);

    const char *methodName = (*env)->GetStringUTFChars(env, jmethodName, NULL);
    if (!methodName) {
        (*env)->DeleteLocalRef(env, methodClass);
        return NULL;
    }

    /* 跳过 Object 方法（toString, hashCode, equals） */
    if (!strcmp(methodName, "toString") || !strcmp(methodName, "hashCode") ||
        !strcmp(methodName, "equals") || !strcmp(methodName, "getClass")) {
        (*env)->ReleaseStringUTFChars(env, jmethodName, methodName);
        (*env)->DeleteLocalRef(env, methodClass);
        (*env)->DeleteLocalRef(env, jmethodName);
        if (paramTypes) (*env)->DeleteLocalRef(env, paramTypes);

        if (!strcmp(methodName, "toString")) {
            return (*env)->NewStringUTF(env, "PyBridgeProxy");
        }
        if (!strcmp(methodName, "hashCode")) {
            jclass intClass = (*env)->FindClass(env, "java/lang/Integer");
            jmethodID valueOf = (*env)->GetStaticMethodID(env, intClass, "valueOf",
                "(I)Ljava/lang/Integer;");
            jobject result = (*env)->CallStaticObjectMethod(env, intClass, valueOf,
                (jint)(intptr_t)proxy);
            (*env)->DeleteLocalRef(env, intClass);
            return result;
        }
        return NULL;
    }

    /* 构建参数列表（Python list） */
    jsize argCount = jargs ? (*env)->GetArrayLength(env, jargs) : 0;
    PyObject *pyArgs = PyList_New(argCount);

    for (jsize i = 0; i < argCount; i++) {
        jobject jarg = (*env)->GetObjectArrayElement(env, jargs, i);
        PyObject *pyArg = java_to_python(env, jarg);
        PyList_SetItem(pyArgs, i, pyArg ? pyArg : Py_None);
        if (jarg) (*env)->DeleteLocalRef(env, jarg);
    }

    /* 查找 proxy_id */
    /* 我们需要从 proxy 对象中获取 proxy_id。
     * 由于 java.lang.reflect.Proxy 不直接存储这个信息，
     * 我们通过遍历 g_proxy_mappings 来查找。
     * 更高效的方式是将 proxy_id 编码到 proxy 对象中。 */

    /* 简化方案：遍历查找匹配的 proxy 对象 */
    int proxy_id = -1;
    for (int i = 0; i < MAX_PROXIES; i++) {
        if (g_proxy_mappings[i].active &&
            (*env)->IsSameObject(env, proxy, g_proxy_mappings[i].java_proxy)) {
            proxy_id = g_proxy_mappings[i].proxy_id;
            break;
        }
    }

    if (proxy_id < 0) {
        LOGW("proxy_invoke_handler: Cannot find proxy_id for object");
        Py_DECREF(pyArgs);
        (*env)->ReleaseStringUTFChars(env, jmethodName, methodName);
        (*env)->DeleteLocalRef(env, methodClass);
        (*env)->DeleteLocalRef(env, jmethodName);
        if (paramTypes) (*env)->DeleteLocalRef(env, paramTypes);
        return NULL;
    }

    /* 调用 Python 回调 */
    jobject result = NULL;
    if (g_proxy_invoke_callback) {
        /* 获取 GIL */
        PyGILState_STATE gstate = PyGILState_Ensure();

        PyObject *pyResult = g_proxy_invoke_callback(proxy_id, methodName, argCount, pyArgs);

        if (pyResult) {
            result = python_to_java(env, pyResult);
            Py_DECREF(pyResult);
        } else {
            /* Python 回调抛出异常 */
            throw_python_exception(env);
        }

        PyGILState_Release(gstate);
    }

    Py_DECREF(pyArgs);
    (*env)->ReleaseStringUTFChars(env, jmethodName, methodName);
    (*env)->DeleteLocalRef(env, methodClass);
    (*env)->DeleteLocalRef(env, jmethodName);
    if (paramTypes) (*env)->DeleteLocalRef(env, paramTypes);

    return result;
}

/* ═══════════════════════════════════════════════════════════════════
 * 导出函数: Python → ctypes 调用
 * ═══════════════════════════════════════════════════════════════════ */

/**
 * 注册 Python 端回调函数
 *
 * 由 Python 端在模块加载时调用，注册 proxy_invoke 回调。
 */
__attribute__((visibility("default")))
void pybridge_jni_set_proxy_callback(void *callback) {
    g_proxy_invoke_callback = (PyObject *(*)(int, const char *, int, PyObject *))callback;
    LOGI("Proxy callback registered");
}

/**
 * 创建 Java 动态代理
 *
 * 为 Python 端创建的代理对象创建对应的 Java 动态代理。
 * 返回 Java 代理对象的引用 ID。
 */
__attribute__((visibility("default")))
int pybridge_jni_create_proxy(
    int proxy_id,
    int interface_count,
    const char **interface_names,
    int method_count,
    const char **method_names)
{
    JNIEnv *env = get_jni_env();
    if (!env) {
        LOGE("pybridge_jni_create_proxy: Cannot get JNIEnv");
        return -1;
    }

    /* 加载接口类 */
    jclass *iface_classes = (jclass *)malloc(sizeof(jclass) * interface_count);
    for (int i = 0; i < interface_count; i++) {
        /* 将 . 替换为 / */
        char jni_name[256];
        snprintf(jni_name, sizeof(jni_name), "%s", interface_names[i]);
        for (char *p = jni_name; *p; p++) {
            if (*p == '.') *p = '/';
        }

        iface_classes[i] = (*env)->FindClass(env, jni_name);
        if (!iface_classes[i]) {
            LOGE("pybridge_jni_create_proxy: Interface not found: %s", interface_names[i]);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
            }
            for (int j = 0; j < i; j++) {
                (*env)->DeleteLocalRef(env, iface_classes[j]);
            }
            free(iface_classes);
            return -1;
        }
    }

    /* 使用 java.lang.reflect.Proxy 创建动态代理 */
    jclass proxyClass = (*env)->FindClass(env, "java/lang/reflect/Proxy");
    if (!proxyClass) {
        LOGE("pybridge_jni_create_proxy: java.lang.reflect.Proxy not found");
        for (int i = 0; i < interface_count; i++) {
            (*env)->DeleteLocalRef(env, iface_classes[i]);
        }
        free(iface_classes);
        return -1;
    }

    /* 构建接口数组 */
    jclass objClass = (*env)->FindClass(env, "java/lang/Class");
    jobjectArray ifaceArray = (*env)->NewObjectArray(env, (jsize)interface_count, objClass, NULL);
    for (int i = 0; i < interface_count; i++) {
        (*env)->SetObjectArrayElement(env, ifaceArray, (jsize)i, iface_classes[i]);
    }
    (*env)->DeleteLocalRef(env, objClass);

    /* 创建自定义 InvocationHandler */
    /* 我们需要一个 Java 类来作为 InvocationHandler。
     * 由于我们无法在 C 中动态生成 Java 类，这里使用内置的 PyBridgeProxyHandler。
     * 检查是否已加载，如果没有则回退到直接方案。 */

    jclass handlerClass = (*env)->FindClass(env, "com/pybridge/proxy/PyBridgeProxyHandler");
    if (!handlerClass) {
        (*env)->ExceptionClear(env);

        /* 回退方案：使用内置的 Java Proxy 但无法回调到 Python
         * 这种情况下，我们使用一个简单的桩实现 */
        LOGW("pybridge_jni_create_proxy: PyBridgeProxyHandler not found, using stub");
        LOGE("pybridge_jni_create_proxy: Create com/pybridge/proxy/PyBridgeProxyHandler.java first");

        for (int i = 0; i < interface_count; i++) {
            (*env)->DeleteLocalRef(env, iface_classes[i]);
        }
        (*env)->DeleteLocalRef(env, ifaceArray);
        (*env)->DeleteLocalRef(env, proxyClass);
        free(iface_classes);
        return -1;
    }

    /* 创建 Handler 实例 */
    jmethodID handlerCtor = (*env)->GetMethodID(env, handlerClass, "<init>", "(I)V");
    if (!handlerCtor) {
        LOGE("pybridge_jni_create_proxy: Handler constructor not found");
        (*env)->DeleteLocalRef(env, handlerClass);
        for (int i = 0; i < interface_count; i++) {
            (*env)->DeleteLocalRef(env, iface_classes[i]);
        }
        (*env)->DeleteLocalRef(env, ifaceArray);
        (*env)->DeleteLocalRef(env, proxyClass);
        free(iface_classes);
        return -1;
    }

    jobject handler = (*env)->NewObject(env, handlerClass, handlerCtor, (jint)proxy_id);

    /* 调用 Proxy.newProxyInstance() */
    jmethodID newProxyInstance = (*env)->GetStaticMethodID(env, proxyClass, "newProxyInstance",
        "(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/reflect/InvocationHandler;)Ljava/lang/Object;");

    if (!newProxyInstance) {
        LOGE("pybridge_jni_create_proxy: Proxy.newProxyInstance not found");
        (*env)->DeleteLocalRef(env, handler);
        (*env)->DeleteLocalRef(env, handlerClass);
        (*env)->DeleteLocalRef(env, proxyClass);
        for (int i = 0; i < interface_count; i++) {
            (*env)->DeleteLocalRef(env, iface_classes[i]);
        }
        (*env)->DeleteLocalRef(env, ifaceArray);
        free(iface_classes);
        return -1;
    }

    /* 获取 ClassLoader（使用第一个接口的 ClassLoader） */
    jclass classLoaderClass = (*env)->FindClass(env, "java/lang/ClassLoader");
    jmethodID getClassLoader = (*env)->GetMethodID(env, objClass, "getClassLoader",
        "()Ljava/lang/ClassLoader;");
    /* 重新获取 objClass（之前被释放了） */
    jclass objClass2 = (*env)->FindClass(env, "java/lang/Class");
    jobject classLoader = (*env)->CallObjectMethod(env, iface_classes[0], getClassLoader);

    jobject javaProxy = (*env)->CallStaticObjectMethod(env, proxyClass, newProxyInstance,
        classLoader, ifaceArray, handler);

    if (!javaProxy) {
        LOGE("pybridge_jni_create_proxy: Failed to create proxy");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, handler);
        (*env)->DeleteLocalRef(env, handlerClass);
        (*env)->DeleteLocalRef(env, proxyClass);
        (*env)->DeleteLocalRef(env, classLoader);
        (*env)->DeleteLocalRef(env, objClass2);
        for (int i = 0; i < interface_count; i++) {
            (*env)->DeleteLocalRef(env, iface_classes[i]);
        }
        (*env)->DeleteLocalRef(env, ifaceArray);
        free(iface_classes);
        return -1;
    }

    /* 创建 global ref */
    jobject globalProxy = (*env)->NewGlobalRef(env, javaProxy);

    /* 存储到映射表 */
    int mapping_idx = proxy_mapping_find_free();
    if (mapping_idx < 0) {
        LOGE("pybridge_jni_create_proxy: Proxy mapping table full");
        (*env)->DeleteGlobalRef(env, globalProxy);
        (*env)->DeleteLocalRef(env, javaProxy);
        (*env)->DeleteLocalRef(env, handler);
        (*env)->DeleteLocalRef(env, handlerClass);
        (*env)->DeleteLocalRef(env, proxyClass);
        (*env)->DeleteLocalRef(env, classLoader);
        (*env)->DeleteLocalRef(env, objClass2);
        for (int i = 0; i < interface_count; i++) {
            (*env)->DeleteLocalRef(env, iface_classes[i]);
        }
        (*env)->DeleteLocalRef(env, ifaceArray);
        free(iface_classes);
        return -1;
    }

    g_proxy_mappings[mapping_idx].proxy_id = proxy_id;
    g_proxy_mappings[mapping_idx].java_proxy = globalProxy;
    g_proxy_mappings[mapping_idx].active = 1;
    g_proxy_count++;

    /* 存储到 Java 引用表 */
    int ref_id = java_ref_store(env, javaProxy, proxyClass, "java.lang.reflect.Proxy");

    LOGI("pybridge_jni_create_proxy: Created proxy id=%d, ref=%d, interfaces=%d",
         proxy_id, ref_id, interface_count);

    /* 清理 */
    (*env)->DeleteLocalRef(env, javaProxy);
    (*env)->DeleteLocalRef(env, handler);
    (*env)->DeleteLocalRef(env, handlerClass);
    (*env)->DeleteLocalRef(env, proxyClass);
    (*env)->DeleteLocalRef(env, classLoader);
    (*env)->DeleteLocalRef(env, objClass2);
    for (int i = 0; i < interface_count; i++) {
        (*env)->DeleteLocalRef(env, iface_classes[i]);
    }
    (*env)->DeleteLocalRef(env, ifaceArray);
    free(iface_classes);

    return ref_id;
}

/**
 * 释放 Java 代理
 */
__attribute__((visibility("default")))
void pybridge_jni_release_proxy(int proxy_id) {
    JNIEnv *env = get_jni_env();
    if (!env) return;

    int idx = proxy_mapping_find_by_id(proxy_id);
    if (idx < 0) return;

    if (g_proxy_mappings[idx].java_proxy) {
        (*env)->DeleteGlobalRef(env, g_proxy_mappings[idx].java_proxy);
    }

    g_proxy_mappings[idx].active = 0;
    g_proxy_mappings[idx].java_proxy = NULL;
    g_proxy_mappings[idx].proxy_id = 0;
    g_proxy_count--;

    LOGI("pybridge_jni_release_proxy: Released proxy id=%d", proxy_id);
}

/**
 * 代理方法调用（由 PyBridgeProxyHandler 调用）
 *
 * 当 Java 端调用代理方法时，此函数被 PyBridgeProxyHandler.invoke() 调用。
 * 它转发调用到 Python 端的回调函数。
 */
__attribute__((visibility("default")))
PyObject *pybridge_jni_proxy_invoke(
    int proxy_id,
    const char *method_name,
    int arg_count,
    PyObject *args)
{
    if (!g_proxy_invoke_callback) {
        LOGE("pybridge_jni_proxy_invoke: No callback registered");
        Py_RETURN_NONE;
    }

    return g_proxy_invoke_callback(proxy_id, method_name, arg_count, args);
}

/* ═══════════════════════════════════════════════════════════════════
 * JNI 方法 — PyBridgeProxyHandler 的 native 方法
 * ═══════════════════════════════════════════════════════════════════ */

/**
 * PyBridgeProxyHandler.nativeInvoke() 的 JNI 实现
 *
 * 由 Java 端的 InvocationHandler 调用，将方法调用转发到 Python。
 */
JNIEXPORT jobject JNICALL
Java_com_pybridge_proxy_PyBridgeProxyHandler_nativeInvoke(
    JNIEnv *env, jobject handler, jint proxyId,
    jobject jmethod, jobjectArray jargs)
{
    return proxy_invoke_handler(env, handler, NULL, jargs);
}