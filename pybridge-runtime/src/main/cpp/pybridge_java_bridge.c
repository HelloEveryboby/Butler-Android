/**
 * PyBridge Python↔Java 双向互调桥
 *
 * 导出 C 函数供 Python 端通过 ctypes 调用。
 * 每个函数从 g_jvm 获取 JNIEnv，执行 JNI 操作后返回结果。
 *
 * 导出函数：
 *   pybridge_jni_create_object(class_name, arg_count, arg_types, arg_values)
 *   pybridge_jni_call_method(obj_ref, method_name, arg_count, arg_types, arg_values)
 *   pybridge_jni_get_field(obj_ref, field_name)
 *   pybridge_jni_set_field(obj_ref, field_name, type_sig, value)
 *   pybridge_jni_decref(obj_ref)
 *   pybridge_jni_create_array(type_sig, length, elements)
 *   pybridge_jni_get_array_element(arr_ref, index)
 *   pybridge_jni_set_array_element(arr_ref, index, type_sig, value)
 *   pybridge_jni_get_array_length(arr_ref)
 */
#include "pybridge_jni.h"
#include <string.h>
#include <stdlib.h>

/* ─── 辅助函数 ───────────────────────────────────────────────────── */

/**
 * 根据类型签名字符串查找对应的 jclass
 */
static jclass find_class_by_signature(JNIEnv *env, const char *sig) {
    if (!sig || !strcmp(sig, "V")) return NULL;

    if (!strcmp(sig, "Z")) return (*env)->FindClass(env, "java/lang/Boolean");
    if (!strcmp(sig, "B")) return (*env)->FindClass(env, "java/lang/Byte");
    if (!strcmp(sig, "S")) return (*env)->FindClass(env, "java/lang/Short");
    if (!strcmp(sig, "I")) return (*env)->FindClass(env, "java/lang/Integer");
    if (!strcmp(sig, "J")) return (*env)->FindClass(env, "java/lang/Long");
    if (!strcmp(sig, "F")) return (*env)->FindClass(env, "java/lang/Float");
    if (!strcmp(sig, "D")) return (*env)->FindClass(env, "java/lang/Double");
    if (!strcmp(sig, "C")) return (*env)->FindClass(env, "java/lang/Character");

    if (sig[0] == 'L') {
        /* 对象类型: Lxxx/yyy/Zzz; → xxx.yyy.Zzz */
        char class_name[256];
        size_t len = strlen(sig);
        if (len > 2 && sig[len - 1] == ';') {
            size_t cn_len = len - 2; /* 去掉 L 和 ; */
            memcpy(class_name, sig + 1, cn_len);
            class_name[cn_len] = '\0';
            /* 将 / 替换为 . */
            for (size_t i = 0; i < cn_len; i++) {
                if (class_name[i] == '/') class_name[i] = '.';
            }
            return (*env)->FindClass(env, sig + 1); /* 原始 JNI 签名 */
        }
    }

    if (sig[0] == '[') {
        return (*env)->FindClass(env, sig);
    }

    return NULL;
}

/**
 * 将 Python 值转换为 JNI value 结构
 */
typedef struct {
    char type;   /* 'Z','B','S','I','J','F','D','C','L','[' */
    union {
        jboolean z;
        jbyte    b;
        jshort   s;
        jint     i;
        jlong    j;
        jfloat   f;
        jdouble  d;
        jchar    c;
        jobject  l;
    } value;
} JniValue;

static JniValue python_to_jni_value(JNIEnv *env, PyObject *py_obj, const char *type_sig) {
    JniValue result = {0};

    if (!py_obj || py_obj == Py_None) {
        result.type = 'L';
        result.value.l = NULL;
        return result;
    }

    switch (type_sig ? type_sig[0] : 'L') {
        case 'Z':
            result.type = 'Z';
            result.value.z = (jboolean)(PyObject_IsTrue(py_obj) ? JNI_TRUE : JNI_FALSE);
            break;
        case 'B':
            result.type = 'B';
            result.value.b = (jbyte)PyLong_AsLong(py_obj);
            break;
        case 'S':
            result.type = 'S';
            result.value.s = (jshort)PyLong_AsLong(py_obj);
            break;
        case 'I':
            result.type = 'I';
            result.value.i = (jint)PyLong_AsLong(py_obj);
            break;
        case 'J':
            result.type = 'J';
            result.value.j = (jlong)PyLong_AsLongLong(py_obj);
            break;
        case 'F':
            result.type = 'F';
            result.value.f = (jfloat)PyFloat_AsDouble(py_obj);
            break;
        case 'D':
            result.type = 'D';
            result.value.d = PyFloat_AsDouble(py_obj);
            break;
        case 'C':
            result.type = 'C';
            if (PyUnicode_Check(py_obj)) {
                const char *s = PyUnicode_AsUTF8(py_obj);
                result.value.c = (jchar)(s && s[0] ? s[0] : '\0');
            } else {
                result.value.c = (jchar)0;
            }
            break;
        default:
            result.type = 'L';
            result.value.l = python_to_java(env, py_obj);
            break;
    }

    return result;
}

/**
 * 将 JNI 返回值转换为 Python 对象
 */
static PyObject *jni_value_to_python(JNIEnv *env, char type, jvalue val) {
    switch (type) {
        case 'Z': return PyBool_FromLong(val.z ? 1 : 0);
        case 'B': return PyLong_FromLong(val.b);
        case 'S': return PyLong_FromLong(val.s);
        case 'I': return PyLong_FromLong(val.i);
        case 'J': return PyLong_FromLongLong(val.j);
        case 'F': return PyFloat_FromDouble((double)val.f);
        case 'D': return PyFloat_FromDouble(val.d);
        case 'C': {
            char buf[2] = {(char)val.c, '\0'};
            return PyUnicode_FromString(buf);
        }
        case 'L':
            return java_to_python(env, val.l);
        case 'V':
        default:
            Py_RETURN_NONE;
    }
}

/* ─── 构造方法签名 ───────────────────────────────────────────────── */

static char *build_method_sig(const char *ret_type_sig, const char **arg_types, int arg_count) {
    /* 构建 JNI 方法签名: (arg_types)ret_type */
    size_t sig_len = 3; /* ( ) \0 */
    for (int i = 0; i < arg_count; i++) {
        if (arg_types[i]) sig_len += strlen(arg_types[i]);
    }
    if (ret_type_sig) sig_len += strlen(ret_type_sig);

    char *sig = (char *)malloc(sig_len);
    if (!sig) return NULL;

    sig[0] = '(';
    sig[1] = '\0';

    for (int i = 0; i < arg_count; i++) {
        if (arg_types[i]) strcat(sig, arg_types[i]);
    }

    strcat(sig, ")");
    if (ret_type_sig) strcat(sig, ret_type_sig);

    return sig;
}

/* ═══════════════════════════════════════════════════════════════════
 * 导出函数: Python → ctypes 调用
 * ═══════════════════════════════════════════════════════════════════ */

/* ─── pybridge_jni_create_object ─────────────────────────────────── */

__attribute__((visibility("default")))
int pybridge_jni_create_object(
    const char *class_name,
    int arg_count,
    const char **arg_type_sigs,
    PyObject **arg_values)
{
    JNIEnv *env = get_jni_env();
    if (!env) {
        LOGE("pybridge_jni_create_object: Cannot get JNIEnv");
        return -1;
    }

    /* 将类名中的 . 替换为 / */
    char jni_name[256];
    snprintf(jni_name, sizeof(jni_name), "%s", class_name);
    for (char *p = jni_name; *p; p++) {
        if (*p == '.') *p = '/';
    }

    jclass cls = (*env)->FindClass(env, jni_name);
    if (!cls) {
        LOGE("pybridge_jni_create_object: Class not found: %s", class_name);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        return -1;
    }

    /* 构建构造方法签名 */
    char *ctor_sig = build_method_sig("V", arg_type_sigs, arg_count);
    if (!ctor_sig) {
        (*env)->DeleteLocalRef(env, cls);
        return -1;
    }

    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", ctor_sig);
    free(ctor_sig);

    if (!ctor) {
        LOGE("pybridge_jni_create_object: Constructor not found for %s", class_name);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, cls);
        return -1;
    }

    /* 转换参数 */
    jvalue *jargs = (jvalue *)malloc(sizeof(jvalue) * arg_count);
    for (int i = 0; i < arg_count; i++) {
        JniValue jv = python_to_jni_value(env, arg_values[i], arg_type_sigs[i]);
        jargs[i] = jv.value;
    }

    /* 创建对象 */
    jobject obj = (*env)->NewObjectA(env, cls, ctor, jargs);

    /* 清理中间创建的 local refs */
    for (int i = 0; i < arg_count; i++) {
        if (arg_type_sigs[i] && arg_type_sigs[i][0] != 'Z' && arg_type_sigs[i][0] != 'B'
            && arg_type_sigs[i][0] != 'S' && arg_type_sigs[i][0] != 'I'
            && arg_type_sigs[i][0] != 'J' && arg_type_sigs[i][0] != 'F'
            && arg_type_sigs[i][0] != 'D' && arg_type_sigs[i][0] != 'C') {
            if (jargs[i].l) (*env)->DeleteLocalRef(env, jargs[i].l);
        }
    }
    free(jargs);

    if (!obj) {
        LOGE("pybridge_jni_create_object: Failed to create %s", class_name);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, cls);
        return -1;
    }

    /* 存储到引用表 */
    int ref_id = java_ref_store(env, obj, cls, class_name);
    (*env)->DeleteLocalRef(env, obj);
    (*env)->DeleteLocalRef(env, cls);

    return ref_id;
}

/* ─── pybridge_jni_call_method ───────────────────────────────────── */

__attribute__((visibility("default")))
PyObject *pybridge_jni_call_method(
    int obj_ref,
    const char *method_name,
    const char *ret_type_sig,
    int arg_count,
    const char **arg_type_sigs,
    PyObject **arg_values)
{
    JNIEnv *env = get_jni_env();
    if (!env) {
        LOGE("pybridge_jni_call_method: Cannot get JNIEnv");
        Py_RETURN_NONE;
    }

    if (obj_ref < 0 || obj_ref >= MAX_JAVA_REFS || !g_java_refs[obj_ref].obj) {
        LOGE("pybridge_jni_call_method: Invalid ref %d", obj_ref);
        Py_RETURN_NONE;
    }

    jobject obj = g_java_refs[obj_ref].obj;
    jclass cls = g_java_refs[obj_ref].cls;

    /* 构建方法签名 */
    char *method_sig = build_method_sig(ret_type_sig, arg_type_sigs, arg_count);
    if (!method_sig) {
        Py_RETURN_NONE;
    }

    jmethodID method = (*env)->GetMethodID(env, cls, method_name, method_sig);
    free(method_sig);

    if (!method) {
        LOGE("pybridge_jni_call_method: Method not found: %s.%s",
             g_java_refs[obj_ref].class_name, method_name);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        Py_RETURN_NONE;
    }

    /* 转换参数 */
    jvalue *jargs = (jvalue *)malloc(sizeof(jvalue) * arg_count);
    for (int i = 0; i < arg_count; i++) {
        JniValue jv = python_to_jni_value(env, arg_values[i], arg_type_sigs[i]);
        jargs[i] = jv.value;
    }

    /* 调用方法 */
    char ret_type = ret_type_sig ? ret_type_sig[0] : 'V';
    jvalue result_value;
    memset(&result_value, 0, sizeof(result_value));

    switch (ret_type) {
        case 'Z':
            result_value.z = (*env)->CallBooleanMethodA(env, obj, method, jargs);
            break;
        case 'B':
            result_value.b = (*env)->CallByteMethodA(env, obj, method, jargs);
            break;
        case 'S':
            result_value.s = (*env)->CallShortMethodA(env, obj, method, jargs);
            break;
        case 'I':
            result_value.i = (*env)->CallIntMethodA(env, obj, method, jargs);
            break;
        case 'J':
            result_value.j = (*env)->CallLongMethodA(env, obj, method, jargs);
            break;
        case 'F':
            result_value.f = (*env)->CallFloatMethodA(env, obj, method, jargs);
            break;
        case 'D':
            result_value.d = (*env)->CallDoubleMethodA(env, obj, method, jargs);
            break;
        case 'C':
            result_value.c = (*env)->CallCharMethodA(env, obj, method, jargs);
            break;
        case 'L':
        case '[':
            result_value.l = (*env)->CallObjectMethodA(env, obj, method, jargs);
            break;
        case 'V':
        default:
            (*env)->CallVoidMethodA(env, obj, method, jargs);
            break;
    }

    /* 清理中间创建的 local refs */
    for (int i = 0; i < arg_count; i++) {
        if (arg_type_sigs[i] && arg_type_sigs[i][0] != 'Z' && arg_type_sigs[i][0] != 'B'
            && arg_type_sigs[i][0] != 'S' && arg_type_sigs[i][0] != 'I'
            && arg_type_sigs[i][0] != 'J' && arg_type_sigs[i][0] != 'F'
            && arg_type_sigs[i][0] != 'D' && arg_type_sigs[i][0] != 'C') {
            if (jargs[i].l) (*env)->DeleteLocalRef(env, jargs[i].l);
        }
    }
    free(jargs);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        if (ret_type == 'L' || ret_type == '[') {
            if (result_value.l) (*env)->DeleteLocalRef(env, result_value.l);
        }
        Py_RETURN_NONE;
    }

    PyObject *py_result = jni_value_to_python(env, ret_type, result_value);

    /* 清理返回的 local ref */
    if ((ret_type == 'L' || ret_type == '[') && result_value.l) {
        (*env)->DeleteLocalRef(env, result_value.l);
    }

    return py_result;
}

/* ─── pybridge_jni_get_field ─────────────────────────────────────── */

__attribute__((visibility("default")))
PyObject *pybridge_jni_get_field(int obj_ref, const char *field_name, const char *type_sig)
{
    JNIEnv *env = get_jni_env();
    if (!env) {
        LOGE("pybridge_jni_get_field: Cannot get JNIEnv");
        Py_RETURN_NONE;
    }

    if (obj_ref < 0 || obj_ref >= MAX_JAVA_REFS || !g_java_refs[obj_ref].obj) {
        Py_RETURN_NONE;
    }

    jobject obj = g_java_refs[obj_ref].obj;
    jclass cls = g_java_refs[obj_ref].cls;

    jfieldID field = (*env)->GetFieldID(env, cls, field_name, type_sig);
    if (!field) {
        LOGE("pybridge_jni_get_field: Field not found: %s.%s",
             g_java_refs[obj_ref].class_name, field_name);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        Py_RETURN_NONE;
    }

    char type = type_sig ? type_sig[0] : 'L';
    jvalue result_value;
    memset(&result_value, 0, sizeof(result_value));

    switch (type) {
        case 'Z': result_value.z = (*env)->GetBooleanField(env, obj, field); break;
        case 'B': result_value.b = (*env)->GetByteField(env, obj, field); break;
        case 'S': result_value.s = (*env)->GetShortField(env, obj, field); break;
        case 'I': result_value.i = (*env)->GetIntField(env, obj, field); break;
        case 'J': result_value.j = (*env)->GetLongField(env, obj, field); break;
        case 'F': result_value.f = (*env)->GetFloatField(env, obj, field); break;
        case 'D': result_value.d = (*env)->GetDoubleField(env, obj, field); break;
        case 'C': result_value.c = (*env)->GetCharField(env, obj, field); break;
        default: result_value.l = (*env)->GetObjectField(env, obj, field); break;
    }

    PyObject *py_result = jni_value_to_python(env, type, result_value);

    if ((type == 'L' || type == '[') && result_value.l) {
        (*env)->DeleteLocalRef(env, result_value.l);
    }

    return py_result;
}

/* ─── pybridge_jni_set_field ─────────────────────────────────────── */

__attribute__((visibility("default")))
void pybridge_jni_set_field(int obj_ref, const char *field_name, const char *type_sig, PyObject *value)
{
    JNIEnv *env = get_jni_env();
    if (!env) {
        LOGE("pybridge_jni_set_field: Cannot get JNIEnv");
        return;
    }

    if (obj_ref < 0 || obj_ref >= MAX_JAVA_REFS || !g_java_refs[obj_ref].obj) {
        return;
    }

    jobject obj = g_java_refs[obj_ref].obj;
    jclass cls = g_java_refs[obj_ref].cls;

    jfieldID field = (*env)->GetFieldID(env, cls, field_name, type_sig);
    if (!field) {
        LOGE("pybridge_jni_set_field: Field not found: %s.%s",
             g_java_refs[obj_ref].class_name, field_name);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        return;
    }

    JniValue jv = python_to_jni_value(env, value, type_sig);
    char type = type_sig ? type_sig[0] : 'L';

    switch (type) {
        case 'Z': (*env)->SetBooleanField(env, obj, field, jv.value.z); break;
        case 'B': (*env)->SetByteField(env, obj, field, jv.value.b); break;
        case 'S': (*env)->SetShortField(env, obj, field, jv.value.s); break;
        case 'I': (*env)->SetIntField(env, obj, field, jv.value.i); break;
        case 'J': (*env)->SetLongField(env, obj, field, jv.value.j); break;
        case 'F': (*env)->SetFloatField(env, obj, field, jv.value.f); break;
        case 'D': (*env)->SetDoubleField(env, obj, field, jv.value.d); break;
        case 'C': (*env)->SetCharField(env, obj, field, jv.value.c); break;
        default:
            (*env)->SetObjectField(env, obj, field, jv.value.l);
            if (jv.value.l) (*env)->DeleteLocalRef(env, jv.value.l);
            break;
    }
}

/* ─── pybridge_jni_decref ────────────────────────────────────────── */

__attribute__((visibility("default")))
void pybridge_jni_decref(int obj_ref)
{
    JNIEnv *env = get_jni_env();
    if (!env) return;

    java_ref_free(env, obj_ref);
}

/* ─── pybridge_jni_create_array ──────────────────────────────────── */

__attribute__((visibility("default")))
int pybridge_jni_create_array(const char *type_sig, int length, PyObject *elements)
{
    JNIEnv *env = get_jni_env();
    if (!env) {
        LOGE("pybridge_jni_create_array: Cannot get JNIEnv");
        return -1;
    }

    if (!type_sig) return -1;

    char type = type_sig[0];
    jobject arr = NULL;
    jclass arr_cls = NULL;

    switch (type) {
        case 'Z': {
            jbooleanArray jarr = (*env)->NewBooleanArray(env, (jsize)length);
            arr = jarr;
            arr_cls = (*env)->FindClass(env, "[Z");
            if (elements && PyList_Check(elements)) {
                jboolean *buf = (jboolean *)malloc(sizeof(jboolean) * length);
                for (int i = 0; i < length; i++) {
                    PyObject *item = PyList_GetItem(elements, i);
                    buf[i] = (jboolean)(PyObject_IsTrue(item) ? JNI_TRUE : JNI_FALSE);
                }
                (*env)->SetBooleanArrayRegion(env, jarr, 0, (jsize)length, buf);
                free(buf);
            }
            break;
        }
        case 'B': {
            jbyteArray jarr = (*env)->NewByteArray(env, (jsize)length);
            arr = jarr;
            arr_cls = (*env)->FindClass(env, "[B");
            if (elements && PyList_Check(elements)) {
                jbyte *buf = (jbyte *)malloc(sizeof(jbyte) * length);
                for (int i = 0; i < length; i++) {
                    buf[i] = (jbyte)PyLong_AsLong(PyList_GetItem(elements, i));
                }
                (*env)->SetByteArrayRegion(env, jarr, 0, (jsize)length, buf);
                free(buf);
            }
            break;
        }
        case 'S': {
            jshortArray jarr = (*env)->NewShortArray(env, (jsize)length);
            arr = jarr;
            arr_cls = (*env)->FindClass(env, "[S");
            if (elements && PyList_Check(elements)) {
                jshort *buf = (jshort *)malloc(sizeof(jshort) * length);
                for (int i = 0; i < length; i++) {
                    buf[i] = (jshort)PyLong_AsLong(PyList_GetItem(elements, i));
                }
                (*env)->SetShortArrayRegion(env, jarr, 0, (jsize)length, buf);
                free(buf);
            }
            break;
        }
        case 'I': {
            jintArray jarr = (*env)->NewIntArray(env, (jsize)length);
            arr = jarr;
            arr_cls = (*env)->FindClass(env, "[I");
            if (elements && PyList_Check(elements)) {
                jint *buf = (jint *)malloc(sizeof(jint) * length);
                for (int i = 0; i < length; i++) {
                    buf[i] = (jint)PyLong_AsLong(PyList_GetItem(elements, i));
                }
                (*env)->SetIntArrayRegion(env, jarr, 0, (jsize)length, buf);
                free(buf);
            }
            break;
        }
        case 'J': {
            jlongArray jarr = (*env)->NewLongArray(env, (jsize)length);
            arr = jarr;
            arr_cls = (*env)->FindClass(env, "[J");
            if (elements && PyList_Check(elements)) {
                jlong *buf = (jlong *)malloc(sizeof(jlong) * length);
                for (int i = 0; i < length; i++) {
                    buf[i] = (jlong)PyLong_AsLongLong(PyList_GetItem(elements, i));
                }
                (*env)->SetLongArrayRegion(env, jarr, 0, (jsize)length, buf);
                free(buf);
            }
            break;
        }
        case 'F': {
            jfloatArray jarr = (*env)->NewFloatArray(env, (jsize)length);
            arr = jarr;
            arr_cls = (*env)->FindClass(env, "[F");
            if (elements && PyList_Check(elements)) {
                jfloat *buf = (jfloat *)malloc(sizeof(jfloat) * length);
                for (int i = 0; i < length; i++) {
                    buf[i] = (jfloat)PyFloat_AsDouble(PyList_GetItem(elements, i));
                }
                (*env)->SetFloatArrayRegion(env, jarr, 0, (jsize)length, buf);
                free(buf);
            }
            break;
        }
        case 'D': {
            jdoubleArray jarr = (*env)->NewDoubleArray(env, (jsize)length);
            arr = jarr;
            arr_cls = (*env)->FindClass(env, "[D");
            if (elements && PyList_Check(elements)) {
                jdouble *buf = (jdouble *)malloc(sizeof(jdouble) * length);
                for (int i = 0; i < length; i++) {
                    buf[i] = PyFloat_AsDouble(PyList_GetItem(elements, i));
                }
                (*env)->SetDoubleArrayRegion(env, jarr, 0, (jsize)length, buf);
                free(buf);
            }
            break;
        }
        case 'L': {
            /* 对象数组 */
            jclass elem_class = find_class_by_signature(env, type_sig);
            if (!elem_class) {
                elem_class = (*env)->FindClass(env, "java/lang/Object");
            }
            jobjectArray jarr = (*env)->NewObjectArray(env, (jsize)length, elem_class, NULL);
            arr = jarr;
            arr_cls = (*env)->FindClass(env, type_sig);
            if (!arr_cls) arr_cls = (*env)->FindClass(env, "[Ljava/lang/Object;");

            if (elements && PyList_Check(elements)) {
                for (int i = 0; i < length; i++) {
                    PyObject *item = PyList_GetItem(elements, i);
                    jobject jitem = python_to_java(env, item);
                    (*env)->SetObjectArrayElement(env, jarr, (jsize)i, jitem);
                    if (jitem) (*env)->DeleteLocalRef(env, jitem);
                }
            }
            if (elem_class) (*env)->DeleteLocalRef(env, elem_class);
            break;
        }
        default: {
            /* 默认作为 Object 数组 */
            jclass objClass = (*env)->FindClass(env, "java/lang/Object");
            jobjectArray jarr = (*env)->NewObjectArray(env, (jsize)length, objClass, NULL);
            arr = jarr;
            arr_cls = (*env)->FindClass(env, "[Ljava/lang/Object;");
            (*env)->DeleteLocalRef(env, objClass);
            break;
        }
    }

    if (!arr) {
        LOGE("pybridge_jni_create_array: Failed to create array");
        return -1;
    }

    int ref_id = java_ref_store(env, arr, arr_cls, "java.lang.Object[]");
    (*env)->DeleteLocalRef(env, arr);
    if (arr_cls) (*env)->DeleteLocalRef(env, arr_cls);

    return ref_id;
}

/* ─── pybridge_jni_get_array_element ─────────────────────────────── */

__attribute__((visibility("default")))
PyObject *pybridge_jni_get_array_element(int arr_ref, int index, const char *type_sig)
{
    JNIEnv *env = get_jni_env();
    if (!env) { Py_RETURN_NONE; }

    if (arr_ref < 0 || arr_ref >= MAX_JAVA_REFS || !g_java_refs[arr_ref].obj) {
        Py_RETURN_NONE;
    }

    jobject arr = g_java_refs[arr_ref].obj;
    if (!type_sig) { Py_RETURN_NONE; }

    char type = type_sig[0];
    jvalue val;
    memset(&val, 0, sizeof(val));

    switch (type) {
        case 'Z': {
            jboolean b;
            (*env)->GetBooleanArrayRegion(env, (jbooleanArray)arr, (jsize)index, 1, &b);
            val.z = b; break;
        }
        case 'B': {
            jbyte b;
            (*env)->GetByteArrayRegion(env, (jbyteArray)arr, (jsize)index, 1, &b);
            val.b = b; break;
        }
        case 'S': {
            jshort s;
            (*env)->GetShortArrayRegion(env, (jshortArray)arr, (jsize)index, 1, &s);
            val.s = s; break;
        }
        case 'I': {
            jint i;
            (*env)->GetIntArrayRegion(env, (jintArray)arr, (jsize)index, 1, &i);
            val.i = i; break;
        }
        case 'J': {
            jlong j;
            (*env)->GetLongArrayRegion(env, (jlongArray)arr, (jsize)index, 1, &j);
            val.j = j; break;
        }
        case 'F': {
            jfloat f;
            (*env)->GetFloatArrayRegion(env, (jfloatArray)arr, (jsize)index, 1, &f);
            val.f = f; break;
        }
        case 'D': {
            jdouble d;
            (*env)->GetDoubleArrayRegion(env, (jdoubleArray)arr, (jsize)index, 1, &d);
            val.d = d; break;
        }
        case 'L':
        case '[':
        default:
            val.l = (*env)->GetObjectArrayElement(env, (jobjectArray)arr, (jsize)index);
            break;
    }

    PyObject *result = jni_value_to_python(env, type, val);

    if ((type == 'L' || type == '[') && val.l) {
        (*env)->DeleteLocalRef(env, val.l);
    }

    return result;
}

/* ─── pybridge_jni_set_array_element ─────────────────────────────── */

__attribute__((visibility("default")))
void pybridge_jni_set_array_element(int arr_ref, int index, const char *type_sig, PyObject *value)
{
    JNIEnv *env = get_jni_env();
    if (!env) return;

    if (arr_ref < 0 || arr_ref >= MAX_JAVA_REFS || !g_java_refs[arr_ref].obj) {
        return;
    }

    jobject arr = g_java_refs[arr_ref].obj;
    if (!type_sig) return;

    JniValue jv = python_to_jni_value(env, value, type_sig);
    char type = type_sig[0];

    switch (type) {
        case 'Z':
            (*env)->SetBooleanArrayRegion(env, (jbooleanArray)arr, (jsize)index, 1, &jv.value.z);
            break;
        case 'B':
            (*env)->SetByteArrayRegion(env, (jbyteArray)arr, (jsize)index, 1, &jv.value.b);
            break;
        case 'S':
            (*env)->SetShortArrayRegion(env, (jshortArray)arr, (jsize)index, 1, &jv.value.s);
            break;
        case 'I':
            (*env)->SetIntArrayRegion(env, (jintArray)arr, (jsize)index, 1, &jv.value.i);
            break;
        case 'J':
            (*env)->SetLongArrayRegion(env, (jlongArray)arr, (jsize)index, 1, &jv.value.j);
            break;
        case 'F':
            (*env)->SetFloatArrayRegion(env, (jfloatArray)arr, (jsize)index, 1, &jv.value.f);
            break;
        case 'D':
            (*env)->SetDoubleArrayRegion(env, (jdoubleArray)arr, (jsize)index, 1, &jv.value.d);
            break;
        default:
            (*env)->SetObjectArrayElement(env, (jobjectArray)arr, (jsize)index, jv.value.l);
            if (jv.value.l) (*env)->DeleteLocalRef(env, jv.value.l);
            break;
    }
}

/* ─── pybridge_jni_get_array_length ──────────────────────────────── */

__attribute__((visibility("default")))
int pybridge_jni_get_array_length(int arr_ref)
{
    JNIEnv *env = get_jni_env();
    if (!env) return 0;

    if (arr_ref < 0 || arr_ref >= MAX_JAVA_REFS || !g_java_refs[arr_ref].obj) {
        return 0;
    }

    jobject arr = g_java_refs[arr_ref].obj;

    /* 判断是基本类型数组还是对象数组 */
    jclass cls = g_java_refs[arr_ref].cls;
    if (!cls) return 0;

    return (int)(*env)->GetArrayLength(env, (jarray)arr);
}

/* ─── pybridge_jni_find_class ────────────────────────────────────── */

__attribute__((visibility("default")))
int pybridge_jni_find_class(const char *class_name)
{
    JNIEnv *env = get_jni_env();
    if (!env) return -1;

    char jni_name[256];
    snprintf(jni_name, sizeof(jni_name), "%s", class_name);
    for (char *p = jni_name; *p; p++) {
        if (*p == '.') *p = '/';
    }

    jclass cls = (*env)->FindClass(env, jni_name);
    if (!cls) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        return -1;
    }

    int ref_id = java_ref_store(env, NULL, cls, class_name);
    (*env)->DeleteLocalRef(env, cls);
    return ref_id;
}

/* ─── pybridge_jni_get_static_method ─────────────────────────────── */

__attribute__((visibility("default")))
PyObject *pybridge_jni_call_static_method(
    int class_ref,
    const char *method_name,
    const char *ret_type_sig,
    int arg_count,
    const char **arg_type_sigs,
    PyObject **arg_values)
{
    JNIEnv *env = get_jni_env();
    if (!env) { Py_RETURN_NONE; }

    if (class_ref < 0 || class_ref >= MAX_JAVA_REFS || !g_java_refs[class_ref].cls) {
        Py_RETURN_NONE;
    }

    jclass cls = g_java_refs[class_ref].cls;

    char *method_sig = build_method_sig(ret_type_sig, arg_type_sigs, arg_count);
    if (!method_sig) { Py_RETURN_NONE; }

    jmethodID method = (*env)->GetStaticMethodID(env, cls, method_name, method_sig);
    free(method_sig);

    if (!method) {
        LOGE("pybridge_jni_call_static_method: Static method not found: %s",
             method_name);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        Py_RETURN_NONE;
    }

    jvalue *jargs = (jvalue *)malloc(sizeof(jvalue) * arg_count);
    for (int i = 0; i < arg_count; i++) {
        JniValue jv = python_to_jni_value(env, arg_values[i], arg_type_sigs[i]);
        jargs[i] = jv.value;
    }

    char ret_type = ret_type_sig ? ret_type_sig[0] : 'V';
    jvalue result_value;
    memset(&result_value, 0, sizeof(result_value));

    switch (ret_type) {
        case 'Z': result_value.z = (*env)->CallStaticBooleanMethodA(env, cls, method, jargs); break;
        case 'B': result_value.b = (*env)->CallStaticByteMethodA(env, cls, method, jargs); break;
        case 'S': result_value.s = (*env)->CallStaticShortMethodA(env, cls, method, jargs); break;
        case 'I': result_value.i = (*env)->CallStaticIntMethodA(env, cls, method, jargs); break;
        case 'J': result_value.j = (*env)->CallStaticLongMethodA(env, cls, method, jargs); break;
        case 'F': result_value.f = (*env)->CallStaticFloatMethodA(env, cls, method, jargs); break;
        case 'D': result_value.d = (*env)->CallStaticDoubleMethodA(env, cls, method, jargs); break;
        case 'C': result_value.c = (*env)->CallStaticCharMethodA(env, cls, method, jargs); break;
        case 'L': case '[':
            result_value.l = (*env)->CallStaticObjectMethodA(env, cls, method, jargs); break;
        default:
            (*env)->CallStaticVoidMethodA(env, cls, method, jargs); break;
    }

    for (int i = 0; i < arg_count; i++) {
        if (arg_type_sigs[i] && arg_type_sigs[i][0] != 'Z' && arg_type_sigs[i][0] != 'B'
            && arg_type_sigs[i][0] != 'S' && arg_type_sigs[i][0] != 'I'
            && arg_type_sigs[i][0] != 'J' && arg_type_sigs[i][0] != 'F'
            && arg_type_sigs[i][0] != 'D' && arg_type_sigs[i][0] != 'C') {
            if (jargs[i].l) (*env)->DeleteLocalRef(env, jargs[i].l);
        }
    }
    free(jargs);

    PyObject *py_result = jni_value_to_python(env, ret_type, result_value);

    if ((ret_type == 'L' || ret_type == '[') && result_value.l) {
        (*env)->DeleteLocalRef(env, result_value.l);
    }

    return py_result;
}

/* ─── pybridge_jni_get_static_field ──────────────────────────────── */

__attribute__((visibility("default")))
PyObject *pybridge_jni_get_static_field(int class_ref, const char *field_name, const char *type_sig)
{
    JNIEnv *env = get_jni_env();
    if (!env) { Py_RETURN_NONE; }

    if (class_ref < 0 || class_ref >= MAX_JAVA_REFS || !g_java_refs[class_ref].cls) {
        Py_RETURN_NONE;
    }

    jclass cls = g_java_refs[class_ref].cls;
    jfieldID field = (*env)->GetStaticFieldID(env, cls, field_name, type_sig);
    if (!field) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        Py_RETURN_NONE;
    }

    char type = type_sig ? type_sig[0] : 'L';
    jvalue val;
    memset(&val, 0, sizeof(val));

    switch (type) {
        case 'Z': val.z = (*env)->GetStaticBooleanField(env, cls, field); break;
        case 'B': val.b = (*env)->GetStaticByteField(env, cls, field); break;
        case 'S': val.s = (*env)->GetStaticShortField(env, cls, field); break;
        case 'I': val.i = (*env)->GetStaticIntField(env, cls, field); break;
        case 'J': val.j = (*env)->GetStaticLongField(env, cls, field); break;
        case 'F': val.f = (*env)->GetStaticFloatField(env, cls, field); break;
        case 'D': val.d = (*env)->GetStaticDoubleField(env, cls, field); break;
        case 'C': val.c = (*env)->GetStaticCharField(env, cls, field); break;
        default: val.l = (*env)->GetStaticObjectField(env, cls, field); break;
    }

    PyObject *result = jni_value_to_python(env, type, val);
    if ((type == 'L' || type == '[') && val.l) {
        (*env)->DeleteLocalRef(env, val.l);
    }
    return result;
}