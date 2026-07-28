/**
 * PyBridge 核心 JNI 实现
 *
 * 实现 Python.kt 中声明的所有 native 方法：
 *   - 初始化/销毁 Python 运行时
 *   - 模块导入
 *   - 函数调用
 *   - 代码执行
 *   - 属性 get/set
 *   - 类型转换
 */
#include "pybridge_jni.h"
#include <string.h>
#include <stdlib.h>

/* ─── 全局状态 ──────────────────────────────────────────────────── */

JavaVM *g_jvm = NULL;
static int g_python_initialized = 0;

/* Java 对象引用表 */
JavaRef g_java_refs[MAX_JAVA_REFS];
int     g_java_ref_count = 0;

/* ─── JNI 工具函数 ───────────────────────────────────────────────── */

JNIEnv *get_jni_env(void) {
    JNIEnv *env = NULL;
    if (!g_jvm) return NULL;

    jint result = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        result = (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        if (result != JNI_OK) {
            LOGE("Failed to attach to JVM thread");
            return NULL;
        }
    }
    return env;
}

void detach_jni_env(void) {
    if (g_jvm) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

/* ─── Java 对象引用表管理 ────────────────────────────────────────── */

int java_ref_find_free(void) {
    for (int i = 0; i < MAX_JAVA_REFS; i++) {
        if (g_java_refs[i].obj == NULL) return i;
    }
    return -1;
}

int java_ref_store(JNIEnv *env, jobject obj, jclass cls, const char *class_name) {
    int id = java_ref_find_free();
    if (id < 0) {
        LOGE("Java reference table full (max %d)", MAX_JAVA_REFS);
        return -1;
    }

    g_java_refs[id].obj = (*env)->NewGlobalRef(env, obj);
    g_java_refs[id].cls = (jclass)(*env)->NewGlobalRef(env, cls);
    g_java_refs[id].class_name = strdup(class_name ? class_name : "java.lang.Object");
    g_java_ref_count++;
    return id;
}

void java_ref_free(JNIEnv *env, int ref_id) {
    if (ref_id < 0 || ref_id >= MAX_JAVA_REFS) return;
    if (g_java_refs[ref_id].obj == NULL) return;

    (*env)->DeleteGlobalRef(env, g_java_refs[ref_id].obj);
    (*env)->DeleteGlobalRef(env, g_java_refs[ref_id].cls);
    free(g_java_refs[ref_id].class_name);

    g_java_refs[ref_id].obj = NULL;
    g_java_refs[ref_id].cls = NULL;
    g_java_refs[ref_id].class_name = NULL;
    g_java_ref_count--;
}

/* ─── Python 错误 → JNI 异常 ─────────────────────────────────────── */

void throw_python_exception(JNIEnv *env) {
    if (!PyErr_Occurred()) return;

    PyObject *ptype, *pvalue, *ptraceback;
    PyErr_Fetch(&ptype, &pvalue, &ptraceback);
    PyErr_NormalizeException(&ptype, &pvalue, &ptraceback);

    /* 构建错误消息 */
    char err_msg[4096] = {0};

    if (ptype && pvalue) {
        PyObject *pTypeStr = PyObject_Str(ptype);
        PyObject *pValueStr = PyObject_Str(pvalue);

        const char *type_str = pTypeStr ? PyUnicode_AsUTF8(pTypeStr) : "Exception";
        const char *value_str = pValueStr ? PyUnicode_AsUTF8(pValueStr) : "";

        snprintf(err_msg, sizeof(err_msg), "Python %s: %s", type_str, value_str);

        Py_XDECREF(pTypeStr);
        Py_XDECREF(pValueStr);
    }

    /* 获取 traceback */
    char traceback_str[8192] = {0};
    if (ptraceback) {
        PyObject *traceback_module = PyImport_ImportModule("traceback");
        if (traceback_module) {
            PyObject *format_tb = PyObject_GetAttrString(traceback_module, "format_tb");
            if (format_tb && PyCallable_Check(format_tb)) {
                PyObject *tb_list = PyObject_CallFunctionObjArgs(format_tb, ptraceback, NULL);
                if (tb_list && PyList_Check(tb_list)) {
                    PyObject *joined = PyUnicode_Join(
                        PyUnicode_FromString(""),
                        tb_list
                    );
                    if (joined) {
                        const char *tb_str = PyUnicode_AsUTF8(joined);
                        if (tb_str) strncpy(traceback_str, tb_str, sizeof(traceback_str) - 1);
                        Py_DECREF(joined);
                    }
                    Py_DECREF(tb_list);
                }
                Py_DECREF(format_tb);
            }
            Py_DECREF(traceback_module);
        }
    }

    /* 抛出 Java PyException */
    jclass pyExClass = (*env)->FindClass(env, "com/pybridge/exceptions/PyException");
    if (pyExClass) {
        jmethodID ctor = (*env)->GetMethodID(env, pyExClass, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V");
        if (ctor) {
            jstring jMsg = (*env)->NewStringUTF(env, err_msg);
            jstring jType = ptype ?
                (*env)->NewStringUTF(env, PyUnicode_AsUTF8(PyObject_Str(ptype))) : NULL;
            jstring jTraceback = traceback_str[0] ?
                (*env)->NewStringUTF(env, traceback_str) : NULL;

            jobject ex = (*env)->NewObject(env, pyExClass, ctor,
                jMsg, jType, jTraceback, NULL);
            (*env)->Throw(env, (jthrowable)ex);

            if (jMsg) (*env)->DeleteLocalRef(env, jMsg);
            if (jType) (*env)->DeleteLocalRef(env, jType);
            if (jTraceback) (*env)->DeleteLocalRef(env, jTraceback);
            (*env)->DeleteLocalRef(env, ex);
        }
        (*env)->DeleteLocalRef(env, pyExClass);
    }

    Py_XDECREF(ptype);
    Py_XDECREF(pvalue);
    Py_XDECREF(ptraceback);
}

/* ─── 类型转换: Python → Java ────────────────────────────────────── */

static jobject py_none_to_java(JNIEnv *env, PyObject *py_obj) {
    (void)py_obj;
    return NULL;
}

static jobject py_bool_to_java(JNIEnv *env, PyObject *py_obj) {
    int val = (py_obj == Py_True) ? 1 : 0;
    jclass cls = (*env)->FindClass(env, "java/lang/Boolean");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Z)V");
    jobject result = (*env)->NewObject(env, cls, ctor, (jboolean)val);
    (*env)->DeleteLocalRef(env, cls);
    return result;
}

static jobject py_int_to_java(JNIEnv *env, PyObject *py_obj) {
    long val = PyLong_AsLong(py_obj);
    jclass cls = (*env)->FindClass(env, "java/lang/Long");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(J)V");
    jobject result = (*env)->NewObject(env, cls, ctor, (jlong)val);
    (*env)->DeleteLocalRef(env, cls);
    return result;
}

static jobject py_float_to_java(JNIEnv *env, PyObject *py_obj) {
    double val = PyFloat_AsDouble(py_obj);
    jclass cls = (*env)->FindClass(env, "java/lang/Double");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(D)V");
    jobject result = (*env)->NewObject(env, cls, ctor, (jdouble)val);
    (*env)->DeleteLocalRef(env, cls);
    return result;
}

static jobject py_str_to_java(JNIEnv *env, PyObject *py_obj) {
    const char *utf8 = PyUnicode_AsUTF8(py_obj);
    return (*env)->NewStringUTF(env, utf8 ? utf8 : "");
}

static jobject py_bytes_to_java(JNIEnv *env, PyObject *py_obj) {
    char *buffer = NULL;
    Py_ssize_t length = 0;
    PyBytes_AsStringAndSize(py_obj, &buffer, &length);

    jbyteArray arr = (*env)->NewByteArray(env, (jsize)length);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, (jsize)length, (jbyte *)buffer);
    }
    return arr;
}

static jobject py_list_to_java(JNIEnv *env, PyObject *py_obj) {
    Py_ssize_t len = PyList_Size(py_obj);
    jclass objCls = (*env)->FindClass(env, "java/lang/Object");
    jobjectArray arr = (*env)->NewObjectArray(env, (jsize)len, objCls, NULL);
    (*env)->DeleteLocalRef(env, objCls);

    for (Py_ssize_t i = 0; i < len; i++) {
        PyObject *item = PyList_GetItem(py_obj, i); /* borrowed */
        jobject jitem = python_to_java(env, item);
        (*env)->SetObjectArrayElement(env, arr, (jsize)i, jitem);
        if (jitem) (*env)->DeleteLocalRef(env, jitem);
    }
    return arr;
}

static jobject py_tuple_to_java(JNIEnv *env, PyObject *py_obj) {
    Py_ssize_t len = PyTuple_Size(py_obj);
    jclass objCls = (*env)->FindClass(env, "java/lang/Object");
    jobjectArray arr = (*env)->NewObjectArray(env, (jsize)len, objCls, NULL);
    (*env)->DeleteLocalRef(env, objCls);

    for (Py_ssize_t i = 0; i < len; i++) {
        PyObject *item = PyTuple_GetItem(py_obj, i); /* borrowed */
        jobject jitem = python_to_java(env, item);
        (*env)->SetObjectArrayElement(env, arr, (jsize)i, jitem);
        if (jitem) (*env)->DeleteLocalRef(env, jitem);
    }
    return arr;
}

static jobject py_dict_to_java(JNIEnv *env, PyObject *py_obj) {
    jclass hashMapCls = (*env)->FindClass(env, "java/util/HashMap");
    jmethodID ctor = (*env)->GetMethodID(env, hashMapCls, "<init>", "()V");
    jmethodID put = (*env)->GetMethodID(env, hashMapCls, "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jobject map = (*env)->NewObject(env, hashMapCls, ctor);
    (*env)->DeleteLocalRef(env, hashMapCls);

    PyObject *key, *value;
    Py_ssize_t pos = 0;
    while (PyDict_Next(py_obj, &pos, &key, &value)) {
        jobject jkey = python_to_java(env, key);
        jobject jvalue = python_to_java(env, value);
        (*env)->CallObjectMethod(env, map, put, jkey, jvalue);
        if (jkey) (*env)->DeleteLocalRef(env, jkey);
        if (jvalue) (*env)->DeleteLocalRef(env, jvalue);
    }
    return map;
}

static jobject py_set_to_java(JNIEnv *env, PyObject *py_obj) {
    jclass hashSetCls = (*env)->FindClass(env, "java/util/HashSet");
    jmethodID ctor = (*env)->GetMethodID(env, hashSetCls, "<init>", "()V");
    jmethodID add = (*env)->GetMethodID(env, hashSetCls, "add",
        "(Ljava/lang/Object;)Z");

    jobject set = (*env)->NewObject(env, hashSetCls, ctor);
    (*env)->DeleteLocalRef(env, hashSetCls);

    PyObject *iterator = PyObject_GetIter(py_obj);
    if (iterator) {
        PyObject *item;
        while ((item = PyIter_Next(iterator))) {
            jobject jitem = python_to_java(env, item);
            (*env)->CallBooleanMethod(env, set, add, jitem);
            if (jitem) (*env)->DeleteLocalRef(env, jitem);
            Py_DECREF(item);
        }
        Py_DECREF(iterator);
    }
    return set;
}

jobject python_to_java(JNIEnv *env, PyObject *py_obj) {
    if (!py_obj || py_obj == Py_None) {
        return NULL;
    }

    if (PyBool_Check(py_obj)) {
        return py_bool_to_java(env, py_obj);
    }
    if (PyLong_Check(py_obj)) {
        return py_int_to_java(env, py_obj);
    }
    if (PyFloat_Check(py_obj)) {
        return py_float_to_java(env, py_obj);
    }
    if (PyUnicode_Check(py_obj)) {
        return py_str_to_java(env, py_obj);
    }
    if (PyBytes_Check(py_obj)) {
        return py_bytes_to_java(env, py_obj);
    }
    if (PyList_Check(py_obj)) {
        return py_list_to_java(env, py_obj);
    }
    if (PyTuple_Check(py_obj)) {
        return py_tuple_to_java(env, py_obj);
    }
    if (PyDict_Check(py_obj)) {
        return py_dict_to_java(env, py_obj);
    }
    if (PySet_Check(py_obj)) {
        return py_set_to_java(env, py_obj);
    }

    /* 未知类型：返回 toString() */
    PyObject *str_obj = PyObject_Str(py_obj);
    if (str_obj) {
        const char *utf8 = PyUnicode_AsUTF8(str_obj);
        jobject result = (*env)->NewStringUTF(env, utf8 ? utf8 : "?");
        Py_DECREF(str_obj);
        return result;
    }
    return (*env)->NewStringUTF(env, "?");
}

/* ─── 类型转换: Java → Python ────────────────────────────────────── */

PyObject *java_to_python(JNIEnv *env, jobject java_obj) {
    if (!java_obj) {
        Py_RETURN_NONE;
    }

    jclass objClass = (*env)->GetObjectClass(env, java_obj);

    /* 检查 String */
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if ((*env)->IsInstanceOf(env, java_obj, stringClass)) {
        const char *utf8 = (*env)->GetStringUTFChars(env, (jstring)java_obj, NULL);
        PyObject *result = PyUnicode_FromString(utf8 ? utf8 : "");
        if (utf8) (*env)->ReleaseStringUTFChars(env, (jstring)java_obj, utf8);
        (*env)->DeleteLocalRef(env, stringClass);
        (*env)->DeleteLocalRef(env, objClass);
        return result;
    }
    (*env)->DeleteLocalRef(env, stringClass);

    /* 检查 Boolean */
    jclass booleanClass = (*env)->FindClass(env, "java/lang/Boolean");
    if ((*env)->IsInstanceOf(env, java_obj, booleanClass)) {
        jmethodID booleanValue = (*env)->GetMethodID(env, booleanClass, "booleanValue", "()Z");
        jboolean val = (*env)->CallBooleanMethod(env, java_obj, booleanValue);
        (*env)->DeleteLocalRef(env, booleanClass);
        (*env)->DeleteLocalRef(env, objClass);
        return PyBool_FromLong(val ? 1 : 0);
    }
    (*env)->DeleteLocalRef(env, booleanClass);

    /* 检查 Integer */
    jclass integerClass = (*env)->FindClass(env, "java/lang/Integer");
    if ((*env)->IsInstanceOf(env, java_obj, integerClass)) {
        jmethodID intValue = (*env)->GetMethodID(env, integerClass, "intValue", "()I");
        jint val = (*env)->CallIntMethod(env, java_obj, intValue);
        (*env)->DeleteLocalRef(env, integerClass);
        (*env)->DeleteLocalRef(env, objClass);
        return PyLong_FromLong(val);
    }
    (*env)->DeleteLocalRef(env, integerClass);

    /* 检查 Long */
    jclass longClass = (*env)->FindClass(env, "java/lang/Long");
    if ((*env)->IsInstanceOf(env, java_obj, longClass)) {
        jmethodID longValue = (*env)->GetMethodID(env, longClass, "longValue", "()J");
        jlong val = (*env)->CallLongMethod(env, java_obj, longValue);
        (*env)->DeleteLocalRef(env, longClass);
        (*env)->DeleteLocalRef(env, objClass);
        return PyLong_FromLongLong(val);
    }
    (*env)->DeleteLocalRef(env, longClass);

    /* 检查 Float/Double */
    jclass floatClass = (*env)->FindClass(env, "java/lang/Float");
    if ((*env)->IsInstanceOf(env, java_obj, floatClass)) {
        jmethodID floatValue = (*env)->GetMethodID(env, floatClass, "floatValue", "()F");
        jfloat val = (*env)->CallFloatMethod(env, java_obj, floatValue);
        (*env)->DeleteLocalRef(env, floatClass);
        (*env)->DeleteLocalRef(env, objClass);
        return PyFloat_FromDouble((double)val);
    }
    (*env)->DeleteLocalRef(env, floatClass);

    jclass doubleClass = (*env)->FindClass(env, "java/lang/Double");
    if ((*env)->IsInstanceOf(env, java_obj, doubleClass)) {
        jmethodID doubleValue = (*env)->GetMethodID(env, doubleClass, "doubleValue", "()D");
        jdouble val = (*env)->CallDoubleMethod(env, java_obj, doubleValue);
        (*env)->DeleteLocalRef(env, doubleClass);
        (*env)->DeleteLocalRef(env, objClass);
        return PyFloat_FromDouble(val);
    }
    (*env)->DeleteLocalRef(env, doubleClass);

    /* 检查 byte[] */
    jclass byteArrayClass = (*env)->FindClass(env, "[B");
    if ((*env)->IsInstanceOf(env, java_obj, byteArrayClass)) {
        jbyteArray arr = (jbyteArray)java_obj;
        jsize len = (*env)->GetArrayLength(env, arr);
        jbyte *bytes = (*env)->GetByteArrayElements(env, arr, NULL);
        PyObject *result = PyBytes_FromStringAndSize((char *)bytes, len);
        (*env)->ReleaseByteArrayElements(env, arr, bytes, JNI_ABORT);
        (*env)->DeleteLocalRef(env, byteArrayClass);
        (*env)->DeleteLocalRef(env, objClass);
        return result;
    }
    (*env)->DeleteLocalRef(env, byteArrayClass);

    /* 检查 Object[] → Python tuple */
    jclass objArrayClass = (*env)->FindClass(env, "[Ljava/lang/Object;");
    if ((*env)->IsInstanceOf(env, java_obj, objArrayClass)) {
        jobjectArray arr = (jobjectArray)java_obj;
        jsize len = (*env)->GetArrayLength(env, arr);
        PyObject *tuple = PyTuple_New(len);
        for (jsize i = 0; i < len; i++) {
            jobject item = (*env)->GetObjectArrayElement(env, arr, i);
            PyObject *pyItem = java_to_python(env, item);
            PyTuple_SetItem(tuple, i, pyItem ? pyItem : Py_None);
            if (item) (*env)->DeleteLocalRef(env, item);
        }
        (*env)->DeleteLocalRef(env, objArrayClass);
        (*env)->DeleteLocalRef(env, objClass);
        return tuple;
    }
    (*env)->DeleteLocalRef(env, objArrayClass);

    /* 检查 List → Python list */
    jclass listClass = (*env)->FindClass(env, "java/util/List");
    if ((*env)->IsInstanceOf(env, java_obj, listClass)) {
        jmethodID size = (*env)->GetMethodID(env, listClass, "size", "()I");
        jmethodID get = (*env)->GetMethodID(env, listClass, "get",
            "(I)Ljava/lang/Object;");
        jint len = (*env)->CallIntMethod(env, java_obj, size);
        PyObject *pyList = PyList_New(len);
        for (jint i = 0; i < len; i++) {
            jobject item = (*env)->CallObjectMethod(env, java_obj, get, i);
            PyObject *pyItem = java_to_python(env, item);
            PyList_SetItem(pyList, i, pyItem ? pyItem : Py_None);
            if (item) (*env)->DeleteLocalRef(env, item);
        }
        (*env)->DeleteLocalRef(env, listClass);
        (*env)->DeleteLocalRef(env, objClass);
        return pyList;
    }
    (*env)->DeleteLocalRef(env, listClass);

    /* 检查 Map → Python dict */
    jclass mapClass = (*env)->FindClass(env, "java/util/Map");
    if ((*env)->IsInstanceOf(env, java_obj, mapClass)) {
        jmethodID entrySet = (*env)->GetMethodID(env, mapClass, "entrySet",
            "()Ljava/util/Set;");
        jobject entries = (*env)->CallObjectMethod(env, java_obj, entrySet);

        jclass setClass = (*env)->FindClass(env, "java/util/Set");
        jmethodID iterator = (*env)->GetMethodID(env, setClass, "iterator",
            "()Ljava/util/Iterator;");
        jobject iter = (*env)->CallObjectMethod(env, entries, iterator);

        jclass iterClass = (*env)->FindClass(env, "java/util/Iterator");
        jmethodID hasNext = (*env)->GetMethodID(env, iterClass, "hasNext", "()Z");
        jmethodID next = (*env)->GetMethodID(env, iterClass, "next",
            "()Ljava/lang/Object;");

        jclass entryClass = (*env)->FindClass(env, "java/util/Map$Entry");
        jmethodID getKey = (*env)->GetMethodID(env, entryClass, "getKey",
            "()Ljava/lang/Object;");
        jmethodID getValue = (*env)->GetMethodID(env, entryClass, "getValue",
            "()Ljava/lang/Object;");

        PyObject *pyDict = PyDict_New();

        while ((*env)->CallBooleanMethod(env, iter, hasNext)) {
            jobject entry = (*env)->CallObjectMethod(env, iter, next);
            jobject jkey = (*env)->CallObjectMethod(env, entry, getKey);
            jobject jvalue = (*env)->CallObjectMethod(env, entry, getValue);

            PyObject *pyKey = java_to_python(env, jkey);
            PyObject *pyValue = java_to_python(env, jvalue);
            if (pyKey && pyValue) {
                PyDict_SetItem(pyDict, pyKey, pyValue);
            }
            Py_XDECREF(pyKey);
            Py_XDECREF(pyValue);

            if (entry) (*env)->DeleteLocalRef(env, entry);
            if (jkey) (*env)->DeleteLocalRef(env, jkey);
            if (jvalue) (*env)->DeleteLocalRef(env, jvalue);
        }

        (*env)->DeleteLocalRef(env, mapClass);
        (*env)->DeleteLocalRef(env, setClass);
        (*env)->DeleteLocalRef(env, iterClass);
        (*env)->DeleteLocalRef(env, entryClass);
        (*env)->DeleteLocalRef(env, entries);
        (*env)->DeleteLocalRef(env, iter);
        (*env)->DeleteLocalRef(env, objClass);
        return pyDict;
    }
    (*env)->DeleteLocalRef(env, mapClass);

    /* 默认：存储为 Java 对象引用，返回 PyCapsule 包装的 ID */
    int ref_id = java_ref_store(env, java_obj, objClass, "java.lang.Object");
    (*env)->DeleteLocalRef(env, objClass);

    if (ref_id < 0) {
        return PyLong_FromLong(0);
    }
    return PyLong_FromLong(ref_id);
}

/* ═══════════════════════════════════════════════════════════════════
 * JNI 方法实现 — Python.kt 的 native 方法
 * ═══════════════════════════════════════════════════════════════════ */

/* ─── JNI_OnLoad ─────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;

    /* 初始化引用表 */
    memset(g_java_refs, 0, sizeof(g_java_refs));
    g_java_ref_count = 0;

    LOGI("PyBridge JNI loaded");
    return JNI_VERSION_1_6;
}

/* ─── nativeInit ─────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
Java_com_pybridge_core_Python_nativeInit(
    JNIEnv *env, jclass cls, jstring pythonHome, jobjectArray modulePaths)
{
    (void)env;
    (void)cls;

    if (g_python_initialized) {
        LOGW("Python already initialized");
        return 0;
    }

    const char *home = pythonHome ?
        (*env)->GetStringUTFChars(env, pythonHome, NULL) : NULL;

    LOGI("Initializing Python runtime, home=%s", home ? home : "(auto)");

    /* 设置 Python Home */
    if (home) {
        #if PY_MAJOR_VERSION >= 3
        wchar_t *whome = Py_DecodeLocale(home, NULL);
        if (whome) {
            Py_SetPythonHome(whome);
            PyMem_RawFree(whome);
        }
        #else
        Py_SetPythonHome((char *)home);
        #endif
        (*env)->ReleaseStringUTFChars(env, pythonHome, home);
    }

    /* 初始化 Python */
    Py_Initialize();

    if (!Py_IsInitialized()) {
        LOGE("Failed to initialize Python");
        return -1;
    }

    /* 设置 sys.path */
    if (modulePaths) {
        jsize pathCount = (*env)->GetArrayLength(env, modulePaths);
        PyObject *sysPath = PySys_GetObject("path"); /* borrowed */

        for (jsize i = 0; i < pathCount; i++) {
            jstring jpath = (jstring)(*env)->GetObjectArrayElement(env, modulePaths, i);
            const char *cpath = (*env)->GetStringUTFChars(env, jpath, NULL);
            if (cpath) {
                PyObject *pyPath = PyUnicode_FromString(cpath);
                PyList_Append(sysPath, pyPath);
                Py_DECREF(pyPath);
                LOGI("  sys.path += %s", cpath);
                (*env)->ReleaseStringUTFChars(env, jpath, cpath);
            }
            (*env)->DeleteLocalRef(env, jpath);
        }
    }

    /* 初始化 pybridge_java 模块支持 */
    PyRun_SimpleString("import sys; sys.modules['_pybridge_internal'] = type(sys)('_pybridge_internal')");

    g_python_initialized = 1;
    LOGI("Python initialized successfully, version: %s", Py_GetVersion());
    return 0;
}

/* ─── nativeFinalize ─────────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_com_pybridge_core_Python_nativeFinalize(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;

    if (!g_python_initialized) return;

    LOGI("Finalizing Python runtime");

    /* 释放所有 Java 引用 */
    JNIEnv *jenv = get_jni_env();
    if (jenv) {
        for (int i = 0; i < MAX_JAVA_REFS; i++) {
            if (g_java_refs[i].obj) {
                java_ref_free(jenv, i);
            }
        }
    }

    Py_Finalize();
    g_python_initialized = 0;
    LOGI("Python finalized");
}

/* ─── nativeImportModule ─────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_com_pybridge_core_Python_nativeImportModule(
    JNIEnv *env, jclass cls, jstring name)
{
    (void)env;
    (void)cls;

    const char *modName = (*env)->GetStringUTFChars(env, name, NULL);
    if (!modName) return 0;

    PyObject *module = PyImport_ImportModule(modName);
    (*env)->ReleaseStringUTFChars(env, name, modName);

    if (!module) {
        throw_python_exception(env);
        return 0;
    }

    return (jlong)(intptr_t)module;
}

/* ─── nativeCallFunction ─────────────────────────────────────────── */

JNIEXPORT jobject JNICALL
Java_com_pybridge_core_Python_nativeCallFunction(
    JNIEnv *env, jclass cls, jlong modulePtr, jstring funcName, jobjectArray args)
{
    (void)cls;

    if (modulePtr == 0) {
        LOGE("nativeCallFunction: null module pointer");
        return NULL;
    }

    PyObject *module = (PyObject *)(intptr_t)modulePtr;

    const char *fname = (*env)->GetStringUTFChars(env, funcName, NULL);
    if (!fname) return NULL;

    /* 构建参数元组 */
    PyObject *pyArgs = NULL;
    if (args) {
        jsize argCount = (*env)->GetArrayLength(env, args);
        pyArgs = PyTuple_New(argCount);

        for (jsize i = 0; i < argCount; i++) {
            jobject jarg = (*env)->GetObjectArrayElement(env, args, i);
            PyObject *pyArg = java_to_python(env, jarg);
            PyTuple_SetItem(pyArgs, i, pyArg ? pyArg : Py_None);
            if (jarg) (*env)->DeleteLocalRef(env, jarg);
        }
    } else {
        pyArgs = PyTuple_New(0);
    }

    /* 调用函数 */
    PyObject *result = PyObject_CallMethod(module, (char *)fname, "O", pyArgs);
    (*env)->ReleaseStringUTFChars(env, funcName, fname);
    Py_DECREF(pyArgs);

    if (!result) {
        throw_python_exception(env);
        return NULL;
    }

    /* 转换结果 */
    jobject javaResult = python_to_java(env, result);
    Py_DECREF(result);
    return javaResult;
}

/* ─── nativeExec ─────────────────────────────────────────────────── */

JNIEXPORT jobject JNICALL
Java_com_pybridge_core_Python_nativeExec(
    JNIEnv *env, jclass cls, jstring code)
{
    (void)cls;

    const char *ccode = (*env)->GetStringUTFChars(env, code, NULL);
    if (!ccode) return NULL;

    PyObject *mainDict = PyModule_GetDict(PyImport_AddModule("__main__"));
    PyObject *result = PyRun_String(ccode, Py_single_input, mainDict, mainDict);
    (*env)->ReleaseStringUTFChars(env, code, ccode);

    if (!result) {
        throw_python_exception(env);
        return NULL;
    }

    jobject javaResult = python_to_java(env, result);
    Py_DECREF(result);
    return javaResult;
}

/* ─── nativeGetAttr ──────────────────────────────────────────────── */

JNIEXPORT jobject JNICALL
Java_com_pybridge_core_Python_nativeGetAttr(
    JNIEnv *env, jclass cls, jlong objPtr, jstring attrName)
{
    (void)cls;

    if (objPtr == 0) return NULL;

    PyObject *obj = (PyObject *)(intptr_t)objPtr;
    const char *aname = (*env)->GetStringUTFChars(env, attrName, NULL);
    if (!aname) return NULL;

    PyObject *attr = PyObject_GetAttrString(obj, aname);
    (*env)->ReleaseStringUTFChars(env, attrName, aname);

    if (!attr) {
        throw_python_exception(env);
        return NULL;
    }

    jobject javaResult = python_to_java(env, attr);
    Py_DECREF(attr);
    return javaResult;
}

/* ─── nativeSetAttr ──────────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_com_pybridge_core_Python_nativeSetAttr(
    JNIEnv *env, jclass cls, jlong objPtr, jstring attrName, jobject value)
{
    (void)cls;

    if (objPtr == 0) return;

    PyObject *obj = (PyObject *)(intptr_t)objPtr;
    const char *aname = (*env)->GetStringUTFChars(env, attrName, NULL);
    if (!aname) return;

    PyObject *pyValue = java_to_python(env, value);
    int ret = PyObject_SetAttrString(obj, aname, pyValue ? pyValue : Py_None);
    Py_XDECREF(pyValue);
    (*env)->ReleaseStringUTFChars(env, attrName, aname);

    if (ret < 0) {
        throw_python_exception(env);
    }
}

/* ─── nativeGetVersion ───────────────────────────────────────────── */

JNIEXPORT jstring JNICALL
Java_com_pybridge_core_Python_nativeGetVersion(
    JNIEnv *env, jclass cls)
{
    (void)cls;
    const char *version = Py_GetVersion();
    return (*env)->NewStringUTF(env, version);
}

/* ─── nativeDecRef ───────────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_com_pybridge_core_Python_nativeDecRef(
    JNIEnv *env, jclass cls, jlong ptr)
{
    (void)env;
    (void)cls;

    if (ptr != 0) {
        PyObject *obj = (PyObject *)(intptr_t)ptr;
        Py_DECREF(obj);
    }
}

/* ─── nativeJavaToPython (TypeConverter) ─────────────────────────── */

JNIEXPORT jlong JNICALL
Java_com_pybridge_types_TypeConverter_nativeJavaToPython(
    JNIEnv *env, jclass cls, jobject value)
{
    (void)cls;

    if (!value) return 0;

    PyObject *pyObj = java_to_python(env, value);
    return (jlong)(intptr_t)pyObj;
}