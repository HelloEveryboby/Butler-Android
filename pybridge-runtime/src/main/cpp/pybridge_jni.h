/**
 * PyBridge JNI — 公共头文件
 *
 * Python 运行时嵌入 + Python↔Java 双向互调
 */
#ifndef PYBRIDGE_JNI_H
#define PYBRIDGE_JNI_H

#include <jni.h>
#include <Python.h>
#include <android/log.h>

#define TAG "PyBridge-JNI"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ─── 全局状态 ──────────────────────────────────────────────── */

extern JavaVM *g_jvm;          /* JVM 句柄，用于从任意线程获取 JNIEnv */

/* ─── Java 对象引用表 ───────────────────────────────────────── */

#define MAX_JAVA_REFS 2048

typedef struct {
    jobject obj;               /* JNI global reference */
    jclass  cls;               /* 对象的类 */
    char   *class_name;        /* 类名 */
} JavaRef;

extern JavaRef g_java_refs[MAX_JAVA_REFS];
extern int    g_java_ref_count;

int  java_ref_store(JNIEnv *env, jobject obj, jclass cls, const char *class_name);
void java_ref_free(JNIEnv *env, int ref_id);
int  java_ref_find_free(void);

/* ─── JNI 工具 ──────────────────────────────────────────────── */

JNIEnv *get_jni_env(void);
void    detach_jni_env(void);

/* ─── Python ↔ Java 类型转换 ────────────────────────────────── */

jobject python_to_java(JNIEnv *env, PyObject *py_obj);
PyObject *java_to_python(JNIEnv *env, jobject java_obj);

/* ─── Python 错误 → JNI 异常 ────────────────────────────────── */

void throw_python_exception(JNIEnv *env);

#endif /* PYBRIDGE_JNI_H */