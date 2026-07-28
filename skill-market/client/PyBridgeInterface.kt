package com.butler.skills

import android.content.Context
import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// ============================================================
// PyBridgeInterface.kt
// ============================================================
// PyBridge JNI 接口封装。
//
// 本文件定义了 Skill 市场客户端与 PyBridge 运行时之间的桥接接口。
// PyBridge 运行时（pybridge-runtime 模块）通过 JNI 嵌入 CPython 解释器，
// 负责 .bsk 格式 Skill 包的安装、卸载、加载与执行。
//
// JNI 绑定说明
// -------------
// 本对象声明的 external 函数需要与 C++ 层（pybridge_jni.cpp）的 JNI
// 导出函数一一对应。由于 Kotlin object 编译为单例类，JNI 函数名遵循
// `Java_com_butler_skills_PyBridgeInterface_{methodName}` 规则。
//
// 有两种方式实现绑定：
//   1. 静态绑定：C++ 侧将函数名改为
//      Java_com_butler_skills_PyBridgeInterface_nativeInit 等。
//   2. 动态绑定（推荐）：在 C++ 的 JNI_OnLoad 中调用 RegisterNatives，
//      显式注册方法名与函数指针的映射，不受类名/包名约束。
//
// native 方法的返回值均为 JSON 字符串（listSkills 返回 JSON 数组），
// 由本对象的高层封装方法负责解析为 Kotlin 数据类型。
//
// 使用方式
// ----------
//   // 1. 初始化（通常在 Application.onCreate 中调用一次）
//   PyBridgeInterface.ensureInitialized(context)
//
//   // 2. 安装 Skill
//   PyBridgeInterface.installSkill(bskFile.absolutePath)
//       .onSuccess { skillId -> Log.i(TAG, "Installed: $skillId") }
//       .onFailure { err -> Log.e(TAG, "Install failed", err) }
//
//   // 3. 列出已安装 Skill
//   val skills = PyBridgeInterface.listInstalledSkills()
//
//   // 4. 执行 Skill
//   PyBridgeInterface.runSkill("weather", """{"city":"Beijing"}""")
//
//   // 5. 卸载 Skill
//   PyBridgeInterface.uninstallSkill("weather")
// ============================================================


/**
 * PyBridge JNI 接口封装对象。
 *
 * 作为 Skill 市场客户端与 PyBridge 运行时之间的唯一交互入口，
 * 提供类型安全的高层 API。底层通过 JNI 调用嵌入式 CPython 运行时
 * （libpybridge.so），完成 .bsk Skill 包的安装、卸载、列表与执行。
 *
 * 本对象同时声明了 native 方法（external fun）和高层封装方法：
 * - **native 方法**：直接对应 C++ JNI 函数，返回原始 JSON 字符串。
 * - **高层封装方法**：解析 JSON 并以 Kotlin Result / 数据类返回，
 *   供 [SkillMarketManager] 和 [SkillMarketViewModel] 调用。
 *
 * 线程安全说明：
 * - [ensureInitialized] 使用双重检查锁定（double-checked locking），
 *   可安全地在多线程环境中调用。
 * - native 方法内部通过 Python GIL 保证线程安全。
 * - [initialized] 和 [libraryLoaded] 使用 @Volatile 保证可见性。
 *
 * @see SkillMarketManager  使用本接口进行 Skill 安装/卸载
 */
object PyBridgeInterface {

    private const val TAG = "PyBridgeInterface"

    /**
     * 用于 JSON 解析的配置。
     * - ignoreUnknownKeys: 忽略 JSON 中未声明的字段（后端可能新增字段）
     * - isLenient: 宽松模式，容忍部分非标准 JSON
     * - coerceInputValues: 将 null 强制转为默认值
     * - explicitNulls: 不在序列化输出中包含 null 字段
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * libpybridge.so 是否已加载。
     */
    @Volatile
    private var libraryLoaded = false

    /**
     * Python 运行时是否已初始化。
     */
    @Volatile
    private var initialized = false

    /**
     * 用于保护初始化过程的同步锁。
     */
    private val initLock = Any()

    // ================================================================
    // Native 方法声明（对应 C++ JNI 导出函数）
    // ================================================================

    /**
     * 初始化 Python 解释器并加载 pybridge 模块。
     *
     * 对应 C++ 函数 `nativeInit`。执行以下操作：
     * 1. 从 Android Context 获取文件路径并设置 Python 环境变量
     *    （PYTHONHOME、PYTHONPATH、PYBRIDGE_ASSETS、PYBRIDGE_FILES）；
     * 2. 调用 `Py_Initialize()` 启动 CPython 解释器（仅首次）；
     * 3. 导入 pybridge 模块并执行 `init_runtime()`。
     *
     * @param context Android Context，用于获取应用文件目录
     * @return true 表示初始化成功，false 表示失败
     */
    external fun nativeInit(context: Context): Boolean

    /**
     * 加载并执行指定 Skill 的入口函数。
     *
     * 对应 C++ 函数 `runSkill`。调用 Python 侧
     * `pybridge.run_skill(skill_id, args)`，将结果字典通过 json.dumps
     * 序列化为 JSON 字符串返回。
     *
     * @param skillId  Skill 唯一标识符
     * @param argsJson 参数的 JSON 字符串（将被 Python 侧 json.loads 解析为 dict/list）
     * @return 结果 JSON 字符串：
     *         成功：{"success": true, "data": ...}
     *         失败：{"success": false, "error": "...", "traceback": "..."}
     */
    external fun runSkill(skillId: String, argsJson: String): String

    /**
     * 列出所有已安装的 Skill。
     *
     * 对应 C++ 函数 `listSkills`。调用 Python 侧
     * `pybridge.list_installed_skills()`，扫描 Skill 存放目录下所有
     * .bsk 文件并读取其 manifest.json。
     *
     * @return JSON 数组字符串，如
     *         [{"skill_id": "...", "name": "...", "version": "...", ...}, ...]
     *         若运行时未初始化则返回 "[]"
     */
    external fun listSkills(): String

    /**
     * 安装 .bsk 格式的 Skill 包。
     *
     * 对应 C++ 函数 `installSkill`。调用 Python 侧
     * `pybridge.install_skill(bsk_path)`，将 .bsk 文件复制到 Skill
     * 存放目录（`{filesDir}/skills/{skill_id}.bsk`），并从包内
     * manifest.json 读取 skill_id 确定目标文件名。
     *
     * @param bskPath .bsk 文件的源路径（绝对路径）
     * @return 结果 JSON 字符串：
     *         成功：{"success": true, "skill_id": "...", "installed_path": "..."}
     *         失败：{"success": false, "error": "..."}
     */
    external fun installSkill(bskPath: String): String

    /**
     * 卸载指定 Skill。
     *
     * 对应 C++ 函数 `uninstallSkill`。调用 Python 侧
     * `pybridge.uninstall_skill(skill_id)`，删除 .bsk 文件、解压目录
     * 以及运行时缓存中的记录。
     *
     * @param skillId 要卸载的 Skill 唯一标识符
     * @return 结果 JSON 字符串：
     *         成功：{"success": true, "error": null}
     *         失败：{"success": false, "error": "..."}
     */
    external fun uninstallSkill(skillId: String): String

    // ================================================================
    // 初始化管理
    // ================================================================

    /**
     * 确保 PyBridge 运行时已初始化。
     *
     * 若尚未初始化，则加载 libpybridge.so 并调用 [nativeInit]。
     * 该方法是幂等的，多次调用不会重复初始化。
     * 使用双重检查锁定（double-checked locking）保证线程安全。
     *
     * 通常在 Application.onCreate() 中调用一次即可。
     *
     * @param context Android Context
     * @return true 表示初始化成功（或已初始化），false 表示初始化失败
     */
    fun ensureInitialized(context: Context): Boolean {
        if (initialized) {
            return true
        }

        synchronized(initLock) {
            if (initialized) {
                return true
            }

            return try {
                // 1. 加载 libpybridge.so（仅加载一次）
                if (!libraryLoaded) {
                    Log.i(TAG, "Loading libpybridge.so...")
                    System.loadLibrary("pybridge")
                    libraryLoaded = true
                }

                // 2. 调用 native 初始化
                Log.i(TAG, "Initializing PyBridge runtime...")
                val success = nativeInit(context)

                if (success) {
                    initialized = true
                    Log.i(TAG, "PyBridge runtime initialized successfully")
                    true
                } else {
                    Log.e(TAG, "PyBridge nativeInit returned false")
                    false
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libpybridge.so", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during PyBridge initialization", e)
                false
            }
        }
    }

    /**
     * 检查运行时是否已初始化。
     *
     * @return true 表示已初始化
     */
    fun isInitialized(): Boolean = initialized

    /**
     * 检查运行时是否已初始化，未初始化时抛出异常。
     *
     * @throws IllegalStateException 若运行时尚未初始化
     */
    private fun requireInitialized() {
        check(initialized) {
            "PyBridge runtime is not initialized. " +
                "Call ensureInitialized(context) first."
        }
    }

    // ================================================================
    // 高层封装方法 —— 供 SkillMarketManager 调用
    // ================================================================

    /**
     * 安装 .bsk 格式的 Skill 包。
     *
     * 调用 native [installSkill]，将 .bsk 文件复制到 Skill 存放目录，
     * 然后从包内 manifest.json 读取 skill_id。安装完成后清除该 skill
     * 的运行时缓存，确保下次加载使用新版本。
     *
     * @param bskPath .bsk 文件的绝对路径
     * @return 成功时返回 skill_id；失败时返回包含错误信息的 Result
     */
    fun installSkillResult(bskPath: String): Result<String> {
        if (!initialized) {
            return Result.failure(
                IllegalStateException("PyBridge runtime is not initialized")
            )
        }

        return try {
            val resultJson = installSkill(bskPath)
            val result = json.decodeFromString(PyBridgeResult.serializer(), resultJson)

            if (result.success && result.skillId != null) {
                Log.i(TAG, "Skill installed: ${result.skillId} -> ${result.installedPath}")
                Result.success(result.skillId)
            } else {
                val errorMsg = result.error ?: "Unknown installation error"
                Log.e(TAG, "Skill installation failed: $errorMsg")
                Result.failure(RuntimeException(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "installSkillResult error", e)
            Result.failure(e)
        }
    }

    /**
     * 卸载指定 Skill。
     *
     * 调用 native [uninstallSkill]，删除 .bsk 文件、解压目录及缓存记录。
     *
     * @param skillId 要卸载的 Skill 唯一标识符
     * @return 成功时返回 Unit；失败时返回包含错误信息的 Result
     */
    fun uninstallSkillResult(skillId: String): Result<Unit> {
        if (!initialized) {
            return Result.failure(
                IllegalStateException("PyBridge runtime is not initialized")
            )
        }

        return try {
            val resultJson = uninstallSkill(skillId)
            val result = json.decodeFromString(PyBridgeResult.serializer(), resultJson)

            if (result.success) {
                Log.i(TAG, "Skill uninstalled: $skillId")
                Result.success(Unit)
            } else {
                val errorMsg = result.error ?: "Unknown uninstall error"
                Log.e(TAG, "Skill uninstall failed: $errorMsg")
                Result.failure(RuntimeException(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "uninstallSkillResult error", e)
            Result.failure(e)
        }
    }

    /**
     * 列出所有已安装的 Skill。
     *
     * 调用 native [listSkills]，解析返回的 JSON 数组。
     * 若运行时未初始化或解析失败，返回空列表。
     *
     * @return 已安装 Skill 的信息列表
     */
    fun listInstalledSkills(): List<PyBridgeSkillEntry> {
        if (!initialized) {
            Log.w(TAG, "listInstalledSkills called before initialization")
            return emptyList()
        }

        return try {
            val resultJson = listSkills()
            json.decodeFromString(
                ListSerializer(PyBridgeSkillEntry.serializer()),
                resultJson
            )
        } catch (e: Exception) {
            Log.e(TAG, "listInstalledSkills error", e)
            emptyList()
        }
    }

    /**
     * 加载并执行指定 Skill 的入口函数。
     *
     * 调用 native [runSkill]，将参数 JSON 传递给 Python 侧的入口函数，
     * 并返回执行结果。
     *
     * @param skillId  Skill 唯一标识符
     * @param argsJson 参数的 JSON 字符串（通常为 JSON 对象或数组）
     * @return 成功时返回结果数据（JSON 字符串格式）；失败时返回包含错误信息的 Result
     */
    fun runSkillResult(skillId: String, argsJson: String): Result<String> {
        if (!initialized) {
            return Result.failure(
                IllegalStateException("PyBridge runtime is not initialized")
            )
        }

        return try {
            val resultJson = runSkill(skillId, argsJson)
            val result = json.decodeFromString(RunSkillResult.serializer(), resultJson)

            if (result.success) {
                // 将 data 字段重新序列化为 JSON 字符串返回
                val dataJson = result.data
                    ?.let { json.encodeToString(JsonElement.serializer(), it) }
                    ?: "null"
                Result.success(dataJson)
            } else {
                val errorMsg = result.error ?: "Unknown execution error"
                Log.e(TAG, "runSkill failed for '$skillId': $errorMsg")
                Result.failure(RuntimeException(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "runSkillResult error for '$skillId'", e)
            Result.failure(e)
        }
    }

    // ================================================================
    // 内部辅助模型
    // ================================================================

    /**
     * run_skill 返回结果的解析模型。
     *
     * Python 侧 run_skill 返回的字典结构：
     * {"success": bool, "data": Any|None, "error": str|None, "traceback": str|None}
     *
     * 由于 data 字段类型不确定，使用 [JsonElement] 接收。
     *
     * @property success   操作是否成功
     * @property data      成功时为入口函数返回值（任意 JSON 值）
     * @property error     失败时的错误描述
     * @property traceback 失败时的完整 Python traceback
     */
    @kotlinx.serialization.Serializable
    private data class RunSkillResult(
        @kotlinx.serialization.SerialName("success")
        val success: Boolean = false,

        @kotlinx.serialization.SerialName("data")
        val data: JsonElement? = null,

        @kotlinx.serialization.SerialName("error")
        val error: String? = null,

        @kotlinx.serialization.SerialName("traceback")
        val traceback: String? = null
    )
}
