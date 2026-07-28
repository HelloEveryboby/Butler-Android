/**
 * PyBridge JNI 桥接层
 * ====================
 *
 * 本文件实现了 Java/Kotlin 层与嵌入式 CPython 运行时之间的桥接。
 * 它负责：
 *   1. 从 Android Context 获取文件路径并设置 Python 环境变量；
 *   2. 初始化 Python 解释器并加载 pybridge 模块；
 *   3. 将 Java 调用转发到 Python 函数（run_skill / list_installed_skills 等）；
 *   4. 在 Python 与 Java 之间进行 JSON 序列化/反序列化；
 *   5. 统一的错误处理与线程安全（GIL）管理。
 *
 * 对应的 Java 类为 com.butler.pybridge.PyBridge，其 native 方法声明如下：
 *
 *   public class PyBridge {
 *       public native boolean nativeInit(Context context);
 *       public native String runSkill(String skillId, String argsJson);
 *       public native String listSkills();
 *       public native String installSkill(String bskPath);
 *       public native String uninstallSkill(String skillId);
 *   }
 *
 * 编译产物为 libpybridge.so，由 Android Gradle 的 externalNativeBuild 加载。
 */

#include <jni.h>
#include <Python.h>

#include <string>
#include <cstdlib>
#include <android/log.h>

/* ------------------------------------------------------------------ */
/* 日志宏定义                                                          */
/* ------------------------------------------------------------------ */

/** logcat 日志标签 */
#define LOG_TAG "PyBridgeJNI"

#define LOGI(...) \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) \
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) \
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* 全局状态                                                            */
/* ------------------------------------------------------------------ */

/** Python 解释器是否已初始化 */
static bool g_python_initialized = false;

/** pybridge 模块是否已成功导入 */
static bool g_pybridge_loaded = false;

/** 缓存的 pybridge 模块对象（borrowed reference，导入后长期有效） */
static PyObject* g_pybridge_module = nullptr;

/* ------------------------------------------------------------------ */
/* JNI 字符串工具函数                                                  */
/* ------------------------------------------------------------------ */

/**
 * 将 jstring 转换为 std::string（UTF-8）。
 *
 * @param env   JNIEnv 指针
 * @param jstr  Java 字符串对象，可为 nullptr
 * @return 对应的 std::string；若 jstr 为 nullptr 则返回空字符串
 */
static std::string jstring_to_stdstr(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) {
        return std::string();
    }
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) {
        return std::string();
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

/**
 * 将 std::string 转换为 jstring。
 *
 * @param env  JNIEnv 指针
 * @param str  C++ 字符串
 * @return 新建的 jstring 对象（local reference）
 */
static jstring stdstr_to_jstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

/* ------------------------------------------------------------------ */
/* Android Context 路径获取工具函数                                    */
/* ------------------------------------------------------------------ */

/**
 * 从 Android Context 获取指定 File 方法返回的路径。
 *
 * 调用 context.<methodName>() 获取一个 java.io.File 对象，
 * 再调用 file.getAbsolutePath() 获取路径字符串。
 *
 * @param env        JNIEnv 指针
 * @param context    Android Context 对象
 * @param methodName Context 上无参且返回 File 的方法名（如 "getFilesDir"）
 * @return 文件绝对路径字符串；失败时返回空字符串
 */
static std::string get_file_path_from_context(JNIEnv* env,
                                              jobject context,
                                              const char* methodName) {
    if (env == nullptr || context == nullptr) {
        return std::string();
    }

    // 获取 Context 类
    jclass contextClass = env->GetObjectClass(context);
    if (contextClass == nullptr) {
        LOGE("Failed to get Context class");
        return std::string();
    }

    // 查找方法（如 getFilesDir()，返回 java.io.File）
    jmethodID method = env->GetMethodID(
        contextClass, methodName, "()Ljava/io/File;");
    if (method == nullptr) {
        LOGE("Method %s not found on Context", methodName);
        env->DeleteLocalRef(contextClass);
        return std::string();
    }

    // 调用方法获取 File 对象
    jobject fileObj = env->CallObjectMethod(context, method);
    if (fileObj == nullptr) {
        LOGE("Context.%s() returned null", methodName);
        env->DeleteLocalRef(contextClass);
        return std::string();
    }

    // 获取 File 类并查找 getAbsolutePath 方法
    jclass fileClass = env->GetObjectClass(fileObj);
    jmethodID getAbsolutePath = env->GetMethodID(
        fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    if (getAbsolutePath == nullptr) {
        LOGE("File.getAbsolutePath() method not found");
        env->DeleteLocalRef(fileClass);
        env->DeleteLocalRef(fileObj);
        env->DeleteLocalRef(contextClass);
        return std::string();
    }

    // 调用 getAbsolutePath() 获取路径字符串
    jstring jpath = static_cast<jstring>(
        env->CallObjectMethod(fileObj, getAbsolutePath));
    std::string path = jstring_to_stdstr(env, jpath);

    // 清理 local references
    if (jpath != nullptr) env->DeleteLocalRef(jpath);
    env->DeleteLocalRef(fileClass);
    env->DeleteLocalRef(fileObj);
    env->DeleteLocalRef(contextClass);

    return path;
}

/**
 * 从 Android Context 获取 nativeLibraryDir 路径。
 *
 * 调用 context.getApplicationInfo() 获取 ApplicationInfo，
 * 再读取其 nativeLibraryDir 字段。
 *
 * @param env      JNIEnv 指针
 * @param context  Android Context 对象
 * @return nativeLibraryDir 路径字符串；失败时返回空字符串
 */
static std::string get_native_library_dir(JNIEnv* env, jobject context) {
    if (env == nullptr || context == nullptr) {
        return std::string();
    }

    jclass contextClass = env->GetObjectClass(context);
    if (contextClass == nullptr) {
        return std::string();
    }

    // getApplicationInfo() 返回 android.content.pm.ApplicationInfo
    jmethodID getApplicationInfo = env->GetMethodID(
        contextClass, "getApplicationInfo",
        "()Landroid/content/pm/ApplicationInfo;");
    if (getApplicationInfo == nullptr) {
        env->DeleteLocalRef(contextClass);
        return std::string();
    }

    jobject appInfo = env->CallObjectMethod(context, getApplicationInfo);
    if (appInfo == nullptr) {
        env->DeleteLocalRef(contextClass);
        return std::string();
    }

    jclass appInfoClass = env->GetObjectClass(appInfo);

    // 读取 nativeLibraryDir 字段（String 类型）
    jfieldID fieldId = env->GetFieldID(
        appInfoClass, "nativeLibraryDir", "Ljava/lang/String;");
    std::string libDir;
    if (fieldId != nullptr) {
        jstring jlibDir = static_cast<jstring>(
            env->GetObjectField(appInfo, fieldId));
        libDir = jstring_to_stdstr(env, jlibDir);
        if (jlibDir != nullptr) env->DeleteLocalRef(jlibDir);
    }

    env->DeleteLocalRef(appInfoClass);
    env->DeleteLocalRef(appInfo);
    env->DeleteLocalRef(contextClass);

    return libDir;
}

/* ------------------------------------------------------------------ */
/* Python 环境设置                                                     */
/* ------------------------------------------------------------------ */

/**
 * 从 Android Context 获取文件路径，并设置 Python 运行所需的环境变量。
 *
 * 设置的环境变量包括：
 *   - PYTHONHOME: Python 标准库根目录（= PYBRIDGE_ASSETS）
 *   - PYTHONPATH: Python 模块搜索路径（含 stdlib 与 pybridge 模块目录）
 *   - PYBRIDGE_ASSETS: Python 资源根目录
 *   - PYBRIDGE_FILES: 用户文件目录（Skill 存放处的父目录）
 *
 * 目录约定：
 *   - PYBRIDGE_FILES = context.getFilesDir()
 *   - PYBRIDGE_ASSETS = PYBRIDGE_FILES + "/python"
 *   - PYTHONHOME     = PYBRIDGE_ASSETS
 *   - PYTHONPATH     = PYBRIDGE_ASSETS + ":" + PYBRIDGE_ASSETS + "/lib/python3.12"
 *
 * @param env      JNIEnv 指针
 * @param context  Android Context 对象
 */
static void setup_python_env(JNIEnv* env, jobject context) {
    // 获取应用文件目录（/data/data/<package>/files）
    std::string filesDir = get_file_path_from_context(
        env, context, "getFilesDir");

    // 获取 nativeLibraryDir（.so 文件所在目录）
    std::string nativeLibDir = get_native_library_dir(env, context);

    // PYBRIDGE_FILES: 用户文件根目录
    std::string pybridgeFiles = filesDir;
    // PYBRIDGE_ASSETS: Python 资源根目录（包含 stdlib、packages、pybridge 模块）
    std::string pybridgeAssets = filesDir + "/python";

    // PYTHONHOME: Python 标准库的根目录
    std::string pythonHome = pybridgeAssets;
    // PYTHONPATH: 模块搜索路径
    //   - pybridgeAssets: 使 pybridge 包可被导入
    //   - pybridgeAssets/lib/python3.12: 标准库路径
    std::string pythonPath = pybridgeAssets + ":" +
                             pybridgeAssets + "/lib/python3.12";
    // 将 nativeLibDir 也加入 PYTHONPATH，便于 ctypes 加载 .so
    if (!nativeLibDir.empty()) {
        pythonPath += ":" + nativeLibDir;
    }

    // 设置环境变量（setenv 在 Android 上可用）
    setenv("PYBRIDGE_FILES", pybridgeFiles.c_str(), 1);
    setenv("PYBRIDGE_ASSETS", pybridgeAssets.c_str(), 1);
    setenv("PYTHONHOME", pythonHome.c_str(), 1);
    setenv("PYTHONPATH", pythonPath.c_str(), 1);

    LOGI("Python environment configured:");
    LOGI("  PYBRIDGE_FILES  = %s", pybridgeFiles.c_str());
    LOGI("  PYBRIDGE_ASSETS = %s", pybridgeAssets.c_str());
    LOGI("  PYTHONHOME      = %s", pythonHome.c_str());
    LOGI("  PYTHONPATH      = %s", pythonPath.c_str());
}

/* ------------------------------------------------------------------ */
/* Python 错误处理工具函数                                             */
/* ------------------------------------------------------------------ */

/**
 * 获取并清除当前 Python 异常，返回错误描述字符串。
 *
 * 调用 PyErr_Fetch 获取异常类型、值和 traceback，将其格式化为字符串，
 * 然后清除异常状态。此函数保证调用后 Python 异常状态被清空。
 *
 * @return 错误描述字符串；若无异常则返回空字符串
 */
static std::string get_and_clear_python_error() {
    PyObject* type = nullptr;
    PyObject* value = nullptr;
    PyObject* traceback = nullptr;

    PyErr_Fetch(&type, &value, &traceback);
    PyErr_NormalizeException(&type, &value, &traceback);

    std::string error_msg;

    // 获取异常类型名
    if (type != nullptr) {
        PyObject* type_name = PyObject_GetAttrString(type, "__name__");
        if (type_name != nullptr) {
            const char* name = PyUnicode_AsUTF8(type_name);
            if (name != nullptr) {
                error_msg = std::string(name) + ": ";
            }
            Py_DECREF(type_name);
        }
    }

    // 获取异常值描述
    if (value != nullptr) {
        PyObject* str_obj = PyObject_Str(value);
        if (str_obj != nullptr) {
            const char* str = PyUnicode_AsUTF8(str_obj);
            if (str != nullptr) {
                error_msg += std::string(str);
            }
            Py_DECREF(str_obj);
        }
    }

    // 如果有 traceback，尝试获取完整 traceback 字符串
    if (traceback != nullptr) {
        PyObject* tb_module = PyImport_ImportModule("traceback");
        if (tb_module != nullptr) {
            PyObject* format_tb = PyObject_GetAttrString(
                tb_module, "format_exception");
            if (format_tb != nullptr) {
                PyObject* args = PyTuple_Pack(3, type, value, traceback);
                PyObject* tb_list = PyObject_CallObject(format_tb, args);
                if (tb_list != nullptr) {
                    PyObject* join = PyUnicode_FromString("");
                    PyObject* tb_str = PyUnicode_Join(join, tb_list);
                    if (tb_str != nullptr) {
                        const char* tb_chars = PyUnicode_AsUTF8(tb_str);
                        if (tb_chars != nullptr) {
                            error_msg += "\n" + std::string(tb_chars);
                        }
                        Py_DECREF(tb_str);
                    }
                    Py_DECREF(join);
                    Py_DECREF(tb_list);
                }
                Py_XDECREF(args);
                Py_DECREF(format_tb);
            }
            Py_DECREF(tb_module);
        }
    }

    // 清理引用
    Py_XDECREF(type);
    Py_XDECREF(value);
    Py_XDECREF(traceback);

    PyErr_Clear();

    if (error_msg.empty()) {
        error_msg = "Unknown Python error";
    }

    return error_msg;
}

/**
 * 检查 Python 是否发生了异常，若有则记录日志并清除。
 *
 * @param context_desc 异常上下文描述（用于日志）
 * @return true 表示有异常发生；false 表示无异常
 */
static bool check_python_error(const char* context_desc) {
    if (PyErr_Occurred()) {
        std::string err = get_and_clear_python_error();
        LOGE("Python error in %s: %s", context_desc, err.c_str());
        return true;
    }
    return false;
}

/* ------------------------------------------------------------------ */
/* Python <-> JSON 转换工具函数                                        */
/* ------------------------------------------------------------------ */

/**
 * 将 Python 对象转换为 JSON 字符串。
 *
 * 使用 Python 标准库的 json.dumps 进行序列化，而非手动拼接字符串。
 * 设置 default=str 以处理不可直接序列化的对象（如自定义类实例），
 * 设置 ensure_ascii=False 以正确处理中文等非 ASCII 字符。
 *
 * @param obj  待序列化的 Python 对象（借用引用）
 * @return JSON 字符串；失败时返回空字符串
 */
static std::string py_object_to_json_string(PyObject* obj) {
    if (obj == nullptr) {
        return std::string();
    }

    // 导入 json 模块
    PyObject* json_module = PyImport_ImportModule("json");
    if (json_module == nullptr) {
        check_python_error("py_object_to_json_string: import json");
        return std::string();
    }

    // 获取 json.dumps 函数
    PyObject* dumps_func = PyObject_GetAttrString(json_module, "dumps");
    if (dumps_func == nullptr) {
        check_python_error("py_object_to_json_string: get dumps");
        Py_DECREF(json_module);
        return std::string();
    }

    // 构建关键字参数：default=str, ensure_ascii=False
    PyObject* kwargs = PyDict_New();
    if (kwargs != nullptr) {
        PyObject* str_builtin = PyDict_GetItemString(
            PyEval_GetBuiltins(), "str");
        if (str_builtin != nullptr) {
            PyDict_SetItemString(kwargs, "default", str_builtin);
        }
        PyObject* py_false = Py_False;
        Py_INCREF(py_false);
        PyDict_SetItemString(kwargs, "ensure_ascii", py_false);
        Py_DECREF(py_false);
    }

    // 调用 json.dumps(obj, **kwargs)
    PyObject* args_tuple = PyTuple_Pack(1, obj);
    PyObject* result = PyObject_Call(dumps_func, args_tuple, kwargs);
    Py_XDECREF(args_tuple);
    Py_XDECREF(kwargs);
    Py_DECREF(dumps_func);
    Py_DECREF(json_module);

    if (result == nullptr) {
        check_python_error("py_object_to_json_string: call dumps");
        return std::string();
    }

    // 将结果字符串转为 C++ std::string
    std::string json_str;
    const char* chars = PyUnicode_AsUTF8(result);
    if (chars != nullptr) {
        json_str = std::string(chars);
    }
    Py_DECREF(result);

    return json_str;
}

/**
 * 将 JSON 字符串解析为 Python 对象。
 *
 * 使用 Python 标准库的 json.loads 进行反序列化。
 *
 * @param json_str  JSON 格式字符串
 * @return 解析后的 Python 对象（new reference）；失败时返回 nullptr
 */
static PyObject* json_string_to_py_object(const std::string& json_str) {
    if (json_str.empty()) {
        // 空字符串视为 None
        Py_INCREF(Py_None);
        return Py_None;
    }

    // 导入 json 模块
    PyObject* json_module = PyImport_ImportModule("json");
    if (json_module == nullptr) {
        check_python_error("json_string_to_py_object: import json");
        return nullptr;
    }

    // 获取 json.loads 函数
    PyObject* loads_func = PyObject_GetAttrString(json_module, "loads");
    if (loads_func == nullptr) {
        check_python_error("json_string_to_py_object: get loads");
        Py_DECREF(json_module);
        return nullptr;
    }

    // 构建参数并调用 json.loads(json_str)
    PyObject* py_str = PyUnicode_FromString(json_str.c_str());
    PyObject* args_tuple = PyTuple_Pack(1, py_str);
    PyObject* result = PyObject_CallObject(loads_func, args_tuple);

    Py_XDECREF(py_str);
    Py_XDECREF(args_tuple);
    Py_DECREF(loads_func);
    Py_DECREF(json_module);

    if (result == nullptr) {
        check_python_error("json_string_to_py_object: call loads");
        return nullptr;
    }

    return result;
}

/* ------------------------------------------------------------------ */
/* pybridge 模块操作工具函数                                           */
/* ------------------------------------------------------------------ */

/**
 * 导入并缓存 pybridge 模块。
 *
 * 若模块已缓存则直接返回。导入后 pybridge.__init__ 会自动执行
 * init_runtime()，完成 sys.path 配置等初始化工作。
 *
 * @return pybridge 模块对象（borrowed reference）；失败返回 nullptr
 */
static PyObject* get_pybridge_module() {
    if (g_pybridge_module != nullptr) {
        return g_pybridge_module;
    }

    // 导入 pybridge 模块
    PyObject* module = PyImport_ImportModule("pybridge");
    if (module == nullptr) {
        check_python_error("get_pybridge_module: import pybridge");
        return nullptr;
    }

    g_pybridge_module = module;
    g_pybridge_loaded = true;
    LOGI("pybridge module imported successfully");
    return module;
}

/**
 * 调用 pybridge 模块中的无参函数，返回结果对象。
 *
 * @param func_name  pybridge 模块中的函数名
 * @return 函数返回值（new reference）；失败返回 nullptr
 */
static PyObject* call_pybridge_func_no_args(const char* func_name) {
    PyObject* module = get_pybridge_module();
    if (module == nullptr) {
        return nullptr;
    }

    // 获取函数对象
    PyObject* func = PyObject_GetAttrString(module, func_name);
    if (func == nullptr) {
        check_python_error(
            (std::string("call_pybridge_func_no_args: get ") +
             func_name).c_str());
        return nullptr;
    }

    // 调用函数（无参数）
    PyObject* result = PyObject_CallObject(func, nullptr);
    Py_DECREF(func);

    if (result == nullptr) {
        check_python_error(
            (std::string("call_pybridge_func_no_args: call ") +
             func_name).c_str());
        return nullptr;
    }

    return result;
}

/**
 * 构造一个错误 JSON 字符串。
 *
 * @param error_msg  错误描述
 * @return JSON 格式的错误字符串，形如 {"success": false, "error": "..."}
 */
static std::string make_error_json(const std::string& error_msg) {
    // 使用 Python 的 json.dumps 构造，确保转义正确
    PyObject* dict = PyDict_New();
    if (dict == nullptr) {
        // 极端情况下 Python 也无法工作，手动构造最简 JSON
        return std::string(
            "{\"success\": false, \"error\": \"Failed to allocate dict\"}");
    }

    PyObject* py_false = Py_False;
    Py_INCREF(py_false);
    PyDict_SetItemString(dict, "success", py_false);
    Py_DECREF(py_false);

    PyObject* py_error = PyUnicode_FromString(error_msg.c_str());
    PyDict_SetItemString(dict, "error", py_error);
    Py_DECREF(py_error);

    std::string json_str = py_object_to_json_string(dict);
    Py_DECREF(dict);

    if (json_str.empty()) {
        // 回退：手动构造（转义双引号和反斜杠）
        std::string escaped = error_msg;
        for (size_t i = 0; i < escaped.size(); ++i) {
            if (escaped[i] == '"' || escaped[i] == '\\') {
                escaped.insert(i, "\\");
                ++i;
            }
        }
        json_str = "{\"success\": false, \"error\": \"" + escaped + "\"}";
    }

    return json_str;
}

/* ================================================================== */
/* JNI 导出函数                                                        */
/* ================================================================== */

/**
 * 初始化 Python 解释器并加载 pybridge 模块。
 *
 * 流程：
 *   1. 调用 setup_python_env 设置环境变量；
 *   2. 若 Python 尚未初始化，则调用 Py_Initialize()；
 *   3. 导入 pybridge 模块（其 __init__.py 会自动调用 init_runtime）；
 *   4. 显式调用 pybridge.init_runtime() 确保环境就绪。
 *
 * @param env      JNIEnv 指针
 * @param thiz     调用该方法的 Java 对象（PyBridge 实例）
 * @param context  Android Context 对象
 * @return JNI_TRUE 表示初始化成功，JNI_FALSE 表示失败
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_butler_pybridge_PyBridge_nativeInit(JNIEnv* env,
                                             jobject thiz,
                                             jobject context) {
    LOGI("nativeInit: starting PyBridge initialization");

    // 1. 设置 Python 环境变量
    setup_python_env(env, context);

    // 2. 初始化 Python 解释器（仅初始化一次）
    if (!g_python_initialized) {
        LOGI("Initializing Python interpreter...");
        Py_Initialize();
        if (!Py_IsInitialized()) {
            LOGE("Failed to initialize Python interpreter");
            return JNI_FALSE;
        }
        g_python_initialized = true;
        LOGI("Python interpreter initialized (version: %s)",
             Py_GetVersion());
    }

    // 3. 确保 GIL 并导入 pybridge 模块
    PyGILState_STATE gstate = PyGILState_Ensure();
    jboolean result = JNI_TRUE;

    try {
        // 导入 pybridge 模块（导入时自动执行 init_runtime）
        PyObject* module = get_pybridge_module();
        if (module == nullptr) {
            LOGE("Failed to import pybridge module");
            result = JNI_FALSE;
        } else {
            // 4. 显式调用 init_runtime() 确保运行时配置最新
            PyObject* init_result = call_pybridge_func_no_args(
                "init_runtime");
            if (init_result == nullptr) {
                LOGE("Failed to call pybridge.init_runtime()");
                result = JNI_FALSE;
            } else {
                // 检查返回结果中的 success 字段
                PyObject* success = PyDict_GetItemString(
                    init_result, "success");
                if (success != nullptr && PyObject_IsTrue(success)) {
                    LOGI("PyBridge runtime initialized successfully");
                    result = JNI_TRUE;
                } else {
                    LOGE("pybridge.init_runtime() reported failure");
                    result = JNI_FALSE;
                }
                Py_DECREF(init_result);
            }
        }
    } catch (...) {
        LOGE("Unexpected exception in nativeInit");
        result = JNI_FALSE;
    }

    PyGILState_Release(gstate);
    return result;
}

/**
 * 加载并执行指定 Skill。
 *
 * 调用 pybridge.run_skill(skill_id, args)，将返回的结果字典
 * 通过 json.dumps 转换为 JSON 字符串返回给 Java 层。
 *
 * @param env       JNIEnv 指针
 * @param thiz      调用该方法的 Java 对象
 * @param skillId   Skill 唯一标识符
 * @param argsJson  参数的 JSON 字符串（将被 json.loads 解析为 Python 对象）
 * @return 结果 JSON 字符串，形如：
 *         成功：{"success": true, "data": ...}
 *         失败：{"success": false, "error": "...", "traceback": "..."}
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_butler_pybridge_PyBridge_runSkill(JNIEnv* env,
                                           jobject thiz,
                                           jstring skillId,
                                           jstring argsJson) {
    // 检查 Python 是否已初始化
    if (!g_python_initialized) {
        return stdstr_to_jstring(env,
            make_error_json("Python runtime is not initialized"));
    }

    // 获取参数字符串
    std::string skill_id = jstring_to_stdstr(env, skillId);
    std::string args_json = jstring_to_stdstr(env, argsJson);

    LOGI("runSkill: skill_id=%s, args=%s",
         skill_id.c_str(), args_json.c_str());

    PyGILState_STATE gstate = PyGILState_Ensure();
    std::string result_json;

    try {
        PyObject* module = get_pybridge_module();
        if (module == nullptr) {
            result_json = make_error_json(
                "Failed to import pybridge module");
        } else {
            // 获取 run_skill 函数
            PyObject* func = PyObject_GetAttrString(module, "run_skill");
            if (func == nullptr) {
                result_json = make_error_json(
                    "pybridge.run_skill not found");
                check_python_error("runSkill: get run_skill");
            } else {
                // 将 argsJson 解析为 Python 对象
                PyObject* py_args = json_string_to_py_object(args_json);
                if (py_args == nullptr) {
                    result_json = make_error_json(
                        "Failed to parse args JSON: " + args_json);
                } else {
                    // 构建参数元组：(skill_id, args)
                    PyObject* py_skill_id =
                        PyUnicode_FromString(skill_id.c_str());
                    PyObject* args_tuple =
                        PyTuple_Pack(2, py_skill_id, py_args);

                    // 调用 run_skill(skill_id, args)
                    PyObject* result = PyObject_CallObject(func, args_tuple);

                    Py_XDECREF(py_skill_id);
                    Py_XDECREF(py_args);
                    Py_XDECREF(args_tuple);

                    if (result == nullptr) {
                        std::string err = get_and_clear_python_error();
                        result_json = make_error_json(
                            "run_skill raised: " + err);
                    } else {
                        // 将结果字典转为 JSON 字符串
                        result_json = py_object_to_json_string(result);
                        if (result_json.empty()) {
                            // 序列化失败，构造错误 JSON
                            std::string err =
                                get_and_clear_python_error();
                            result_json = make_error_json(
                                "Failed to serialize result: " + err);
                        }
                        Py_DECREF(result);
                    }
                }
                Py_DECREF(func);
            }
        }
    } catch (...) {
        result_json = make_error_json(
            "Unexpected C++ exception in runSkill");
    }

    PyGILState_Release(gstate);
    return stdstr_to_jstring(env, result_json);
}

/**
 * 列出所有已安装的 Skill。
 *
 * 调用 pybridge.list_installed_skills()，将返回的列表
 * 通过 json.dumps 转换为 JSON 数组字符串返回。
 *
 * @param env   JNIEnv 指针
 * @param thiz  调用该方法的 Java 对象
 * @return JSON 数组字符串，如 [{"skill_id": "...", "name": "...", ...}, ...]
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_butler_pybridge_PyBridge_listSkills(JNIEnv* env, jobject thiz) {
    // 检查 Python 是否已初始化
    if (!g_python_initialized) {
        return stdstr_to_jstring(env, "[]");
    }

    LOGI("listSkills: listing installed skills");

    PyGILState_STATE gstate = PyGILState_Ensure();
    std::string result_json;

    try {
        // 调用 pybridge.list_installed_skills()
        PyObject* result = call_pybridge_func_no_args(
            "list_installed_skills");
        if (result == nullptr) {
            // 失败时返回空数组
            result_json = "[]";
        } else {
            // 将列表转为 JSON 字符串
            result_json = py_object_to_json_string(result);
            if (result_json.empty()) {
                result_json = "[]";
            }
            Py_DECREF(result);
        }
    } catch (...) {
        result_json = "[]";
    }

    PyGILState_Release(gstate);
    LOGI("listSkills: returning %zu bytes", result_json.size());
    return stdstr_to_jstring(env, result_json);
}

/**
 * 安装 Skill 包。
 *
 * 调用 pybridge.install_skill(bsk_path)，将指定路径的 .bsk 文件
 * 复制到 Skill 存放目录。安装前会读取包内 manifest.json 以确定 skill_id。
 *
 * @param env      JNIEnv 指针
 * @param thiz     调用该方法的 Java 对象
 * @param bskPath  .bsk 文件的源路径
 * @return 结果 JSON 字符串，形如：
 *         成功：{"success": true, "skill_id": "...", "installed_path": "..."}
 *         失败：{"success": false, "error": "..."}
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_butler_pybridge_PyBridge_installSkill(JNIEnv* env,
                                               jobject thiz,
                                               jstring bskPath) {
    // 检查 Python 是否已初始化
    if (!g_python_initialized) {
        return stdstr_to_jstring(env,
            make_error_json("Python runtime is not initialized"));
    }

    std::string bsk_path = jstring_to_stdstr(env, bskPath);
    LOGI("installSkill: bsk_path=%s", bsk_path.c_str());

    PyGILState_STATE gstate = PyGILState_Ensure();
    std::string result_json;

    try {
        PyObject* module = get_pybridge_module();
        if (module == nullptr) {
            result_json = make_error_json(
                "Failed to import pybridge module");
        } else {
            // 获取 install_skill 函数
            PyObject* func = PyObject_GetAttrString(
                module, "install_skill");
            if (func == nullptr) {
                result_json = make_error_json(
                    "pybridge.install_skill not found");
                check_python_error("installSkill: get install_skill");
            } else {
                // 构建参数元组：(bsk_path,)
                PyObject* py_bsk_path =
                    PyUnicode_FromString(bsk_path.c_str());
                PyObject* args_tuple = PyTuple_Pack(1, py_bsk_path);

                // 调用 install_skill(bsk_path)
                PyObject* result = PyObject_CallObject(func, args_tuple);

                Py_XDECREF(py_bsk_path);
                Py_XDECREF(args_tuple);

                if (result == nullptr) {
                    std::string err = get_and_clear_python_error();
                    result_json = make_error_json(
                        "install_skill raised: " + err);
                } else {
                    result_json = py_object_to_json_string(result);
                    if (result_json.empty()) {
                        std::string err =
                            get_and_clear_python_error();
                        result_json = make_error_json(
                            "Failed to serialize result: " + err);
                    }
                    Py_DECREF(result);
                }
                Py_DECREF(func);
            }
        }
    } catch (...) {
        result_json = make_error_json(
            "Unexpected C++ exception in installSkill");
    }

    PyGILState_Release(gstate);
    return stdstr_to_jstring(env, result_json);
}

/**
 * 卸载指定 Skill。
 *
 * 调用 pybridge.uninstall_skill(skill_id)，删除 .bsk 文件、
 * 解压目录以及缓存中的记录。
 *
 * @param env      JNIEnv 指针
 * @param thiz     调用该方法的 Java 对象
 * @param skillId  要卸载的 Skill 唯一标识符
 * @return 结果 JSON 字符串，形如：
 *         成功：{"success": true, "error": null}
 *         失败：{"success": false, "error": "..."}
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_butler_pybridge_PyBridge_uninstallSkill(JNIEnv* env,
                                                 jobject thiz,
                                                 jstring skillId) {
    // 检查 Python 是否已初始化
    if (!g_python_initialized) {
        return stdstr_to_jstring(env,
            make_error_json("Python runtime is not initialized"));
    }

    std::string skill_id = jstring_to_stdstr(env, skillId);
    LOGI("uninstallSkill: skill_id=%s", skill_id.c_str());

    PyGILState_STATE gstate = PyGILState_Ensure();
    std::string result_json;

    try {
        PyObject* module = get_pybridge_module();
        if (module == nullptr) {
            result_json = make_error_json(
                "Failed to import pybridge module");
        } else {
            // 获取 uninstall_skill 函数
            PyObject* func = PyObject_GetAttrString(
                module, "uninstall_skill");
            if (func == nullptr) {
                result_json = make_error_json(
                    "pybridge.uninstall_skill not found");
                check_python_error("uninstallSkill: get uninstall_skill");
            } else {
                // 构建参数元组：(skill_id,)
                PyObject* py_skill_id =
                    PyUnicode_FromString(skill_id.c_str());
                PyObject* args_tuple = PyTuple_Pack(1, py_skill_id);

                // 调用 uninstall_skill(skill_id)
                PyObject* result = PyObject_CallObject(func, args_tuple);

                Py_XDECREF(py_skill_id);
                Py_XDECREF(args_tuple);

                if (result == nullptr) {
                    std::string err = get_and_clear_python_error();
                    result_json = make_error_json(
                        "uninstall_skill raised: " + err);
                } else {
                    result_json = py_object_to_json_string(result);
                    if (result_json.empty()) {
                        std::string err =
                            get_and_clear_python_error();
                        result_json = make_error_json(
                            "Failed to serialize result: " + err);
                    }
                    Py_DECREF(result);
                }
                Py_DECREF(func);
            }
        }
    } catch (...) {
        result_json = make_error_json(
            "Unexpected C++ exception in uninstallSkill");
    }

    PyGILState_Release(gstate);
    return stdstr_to_jstring(env, result_json);
}
