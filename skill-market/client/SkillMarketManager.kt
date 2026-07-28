package com.butler.skills

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

// ============================================================
// SkillMarketManager.kt
// ============================================================
// Skill 市场客户端管理器。
//
// 该类是 Skill 市场客户端的核心组件，负责：
//   1. 通过 HTTP API 与 Skill 市场后端交互（浏览、搜索、查询详情）；
//   2. 下载 .bsk 格式的 Skill 包到本地缓存目录；
//   3. 通过 PyBridge 运行时安装、卸载 Skill；
//   4. 扫描本地已安装的 Skill 并检查更新。
//
// 所有网络和文件 I/O 操作均使用 Kotlin 协程在 Dispatchers.IO 上执行，
// 确保不会阻塞主线程。方法返回 kotlin.Result 以统一错误处理。
//
// 目录结构约定
// -------------
//   {filesDir}/skills/                 — Skill 存放目录（PyBridge 管理）
//     ├── weather.bsk                  — 已安装的 Skill 包
//     ├── translator.bsk
//     └── .extracted/                  — PyBridge 解压缓存（由运行时管理）
//   {cacheDir}/skill-downloads/        — 下载临时目录
//     └── weather_1.0.0.bsk            — 下载中的 .bsk 文件
//
// API 约定
// -------------
//   GET  /api/skills                   — 分页查询 Skill 列表
//   GET  /api/skills/{skill_id}        — 获取 Skill 详情（含版本历史）
//   GET  /api/skills/{skill_id}/download?version=... — 下载 .bsk 包
//   GET  /api/categories               — 获取分类列表
// ============================================================


/**
 * Skill 市场客户端管理器。
 *
 * 封装了与 Skill 市场后端的全部交互逻辑，包括 Skill 的浏览、搜索、
 * 下载、安装、卸载和更新检查。通过 [PyBridgeInterface] 与嵌入式
 * Python 运行时协作完成 Skill 的安装与卸载。
 *
 * 使用示例：
 * ```
 * val manager = SkillMarketManager(context)
 *
 * // 浏览 Skill 列表
 * manager.fetchSkills(arch = "arm64-v8a", appVersion = "1.0.0",
 *     category = null, search = null, page = 1)
 *     .onSuccess { response -> /* 更新 UI */ }
 *
 * // 一键下载并安装
 * manager.downloadAndInstall("weather", "1.0.0") { progress ->
 *     /* 更新进度条 */
 * }.onSuccess { skillId -> /* 安装成功 */ }
 * ```
 *
 * @property context     Android Context，用于获取文件目录
 * @property apiBaseUrl  Skill 市场后端 API 基础地址
 * @property httpClient  OkHttp 客户端实例
 */
class SkillMarketManager(
    private val context: Context,
    private val apiBaseUrl: String = "https://butler.example.com/api",
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    companion object {

        private const val TAG = "SkillMarketManager"

        /** 下载缓冲区大小（8KB）。 */
        private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024

        /** Skill 存放目录名，位于 filesDir 下。 */
        private const val SKILLS_DIR_NAME = "skills"

        /** 下载临时目录名，位于 cacheDir 下。 */
        private const val DOWNLOAD_DIR_NAME = "skill-downloads"

        /** .bsk 文件后缀。 */
        private const val BSK_SUFFIX = ".bsk"

        /** manifest.json 在 .bsk 包内的路径。 */
        private const val MANIFEST_ENTRY = "manifest.json"

        /**
         * 创建默认的 OkHttp 客户端。
         *
         * 配置：
         * - 连接超时：15 秒
         * - 读取超时：60 秒（适应大文件下载）
         * - 写入超时：30 秒
         * - 自动重试失败连接
         *
         * @return 配置好的 OkHttpClient 实例
         */
        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    /**
     * 用于 JSON 解析的配置。
     * - ignoreUnknownKeys: 忽略后端新增的未知字段
     * - isLenient: 宽松模式
     * - coerceInputValues: null 转为默认值
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * Skill 存放目录：`{context.filesDir}/skills/`。
     *
     * 该目录由 PyBridge 运行时管理，每个 `{skill_id}.bsk` 文件代表
     * 一个已安装的 Skill。与 Python 侧 `_SKILLS_DIR` 一致。
     */
    private val skillsDir: File = File(context.filesDir, SKILLS_DIR_NAME).also { dir ->
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    /**
     * 下载临时目录：`{context.cacheDir}/skill-downloads/`。
     *
     * 下载的 .bsk 文件先存放于此，校验通过后再由 PyBridge 安装
     * （复制）到 skillsDir。安装完成后自动清理。
     */
    private val downloadDir: File = File(context.cacheDir, DOWNLOAD_DIR_NAME).also { dir ->
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }


    // ================================================================
    // 一、Skill 列表查询
    // ================================================================

    /**
     * 分页查询 Skill 列表。
     *
     * 调用 `GET /api/skills`，支持按架构、分类过滤和关键词搜索。
     *
     * @param arch       设备架构，如 "arm64-v8a"，用于过滤兼容的 Skill
     * @param appVersion 当前 App 版本号，用于过滤 min_app_version 兼容的 Skill
     * @param category   分类过滤，传 null 表示不限分类
     * @param search     搜索关键词，传 null 表示不搜索
     * @param page       页码，从 1 开始
     * @return 成功时返回 [SkillListResponse]；失败时返回包含错误信息的 Result
     */
    suspend fun fetchSkills(
        arch: String,
        appVersion: String,
        category: String?,
        search: String?,
        page: Int
    ): Result<SkillListResponse> {
        val queryParams = mutableMapOf(
            "arch" to arch,
            "app_version" to appVersion,
            "page" to page.toString()
        )
        if (category != null) {
            queryParams["category"] = category
        }
        if (search != null) {
            queryParams["search"] = search
        }

        return apiGet("skills", queryParams, SkillListResponse.serializer())
    }

    /**
     * 获取 Skill 详情（含全部版本历史）。
     *
     * 调用 `GET /api/skills/{skill_id}`。
     *
     * @param skillId Skill 唯一标识符
     * @return 成功时返回 [SkillDetail]；失败时返回包含错误信息的 Result
     */
    suspend fun getSkillDetail(skillId: String): Result<SkillDetail> {
        val path = "skills/${urlEncode(skillId)}"
        return apiGet(path, emptyMap(), SkillDetail.serializer())
    }

    /**
     * 全文搜索 Skill。
     *
     * 内部调用 [fetchSkills]，将搜索关键词传入 search 参数。
     *
     * @param query 搜索关键词
     * @param arch  设备架构（默认 "arm64-v8a"）
     * @param appVersion 当前 App 版本号（默认 "0.0.0"）
     * @return 成功时返回匹配的 Skill 列表；失败时返回包含错误信息的 Result
     */
    suspend fun searchSkills(
        query: String,
        arch: String = "arm64-v8a",
        appVersion: String = "0.0.0"
    ): Result<List<SkillInfo>> {
        return fetchSkills(
            arch = arch,
            appVersion = appVersion,
            category = null,
            search = query,
            page = 1
        ).map { it.skills }
    }

    /**
     * 获取分类列表。
     *
     * 调用 `GET /api/categories`。后端返回 JSON 字符串数组，
     * 如 `["productivity", "entertainment", "utilities"]`。
     *
     * @return 成功时返回分类名称列表；失败时返回包含错误信息的 Result
     */
    suspend fun getCategories(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(buildUrl("categories"))
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "getCategories failed: HTTP ${response.code}, body=$errorBody")
                    return@use Result.failure(
                        HttpException(response.code, "Failed to fetch categories")
                    )
                }

                val body = response.body?.string() ?: "[]"
                val categories = json.decodeFromString<List<String>>(body)
                Result.success(categories)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCategories error", e)
            Result.failure(e)
        }
    }


    // ================================================================
    // 二、Skill 下载
    // ================================================================

    /**
     * 下载 .bsk 格式的 Skill 包到本地临时目录。
     *
     * 执行流程：
     * 1. 调用 [getSkillDetail] 获取版本信息，提取目标版本的 SHA256 校验和；
     * 2. 构造下载 URL 并通过 OkHttp 下载文件；
     * 3. 下载过程中通过 [onProgress] 回调报告进度（0.0 ~ 1.0）；
     * 4. 下载完成后计算文件的 SHA256，与服务端返回的校验和比对；
     * 5. 校验通过则返回下载的文件，否则删除文件并返回失败。
     *
     * 下载的文件存放于 `{cacheDir}/skill-downloads/{skillId}_{version}.bsk`。
     * 调用方在安装完成后应删除该临时文件（[downloadAndInstall] 会自动处理）。
     *
     * @param skillId   Skill 唯一标识符
     * @param version   要下载的版本号
     * @param onProgress 下载进度回调，参数为 0.0 ~ 1.0 之间的浮点数。
     *                   传 null 表示不需要进度回调。
     * @return 成功时返回下载的 .bsk 文件；失败时返回包含错误信息的 Result
     */
    suspend fun downloadSkill(
        skillId: String,
        version: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // 1. 获取 Skill 详情，提取目标版本的 SHA256
            val detailResult = getSkillDetail(skillId)
            val detail = detailResult.getOrElse { e ->
                return@withContext Result.failure(
                    IOException("Failed to fetch skill detail for download: ${e.message}", e)
                )
            }

            val versionInfo = detail.versions.find { it.version == version }
                ?: return@withContext Result.failure(
                    IllegalArgumentException(
                        "Version '$version' not found for skill '$skillId'. " +
                            "Available: ${detail.versions.map { it.version }}"
                    )
                )

            val expectedSha256 = versionInfo.sha256.lowercase()

            // 2. 构造下载 URL
            val downloadUrl = buildUrl("skills/${urlEncode(skillId)}/download",
                mapOf("version" to version))

            Log.i(TAG, "Downloading skill '$skillId' v$version from $downloadUrl")

            // 3. 下载文件
            val outputFile = File(downloadDir, "${skillId}_${version}${BSK_SUFFIX}")
            val request = Request.Builder()
                .url(downloadUrl)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "Download failed: HTTP ${response.code}, body=$errorBody")
                    return@withContext Result.failure<File>(
                        HttpException(response.code, "Download failed: HTTP ${response.code}")
                    )
                }

                val responseBody = response.body
                    ?: return@withContext Result.failure<File>(IOException("Empty response body"))

                val contentLength = responseBody.contentLength()

                // 读取响应体并写入文件，同时报告进度
                responseBody.byteStream().use { input ->
                    outputFile.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var totalRead = 0L
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            // 报告下载进度
                            if (onProgress != null) {
                                val progress = if (contentLength > 0) {
                                    (totalRead.toFloat() / contentLength.toFloat())
                                        .coerceIn(0f, 1f)
                                } else {
                                    // 无法获取总大小时，报告 -1 表示不确定
                                    -1f
                                }
                                onProgress(progress)
                            }
                        }
                    }
                }
            }

            // 下载完成，报告 100%
            onProgress?.invoke(1f)

            // 4. 校验文件完整性（SHA256）
            val actualSha256 = computeSha256(outputFile).lowercase()
            if (actualSha256 != expectedSha256) {
                Log.e(TAG, "SHA256 mismatch for '$skillId' v$version: " +
                    "expected=$expectedSha256, actual=$actualSha256")
                outputFile.delete()
                return@withContext Result.failure<File>(
                    IOException("SHA256 verification failed: file may be corrupted")
                )
            }

            Log.i(TAG, "Skill downloaded and verified: ${outputFile.name} " +
                "(${outputFile.length()} bytes)")
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "downloadSkill error for '$skillId' v$version", e)
            Result.failure(e)
        }
    }


    // ================================================================
    // 三、Skill 安装与卸载
    // ================================================================

    /**
     * 安装 .bsk 格式的 Skill 包。
     *
     * 调用 [PyBridgeInterface.installSkillResult]，将 .bsk 文件复制到
     * Skill 存放目录（`{filesDir}/skills/{skill_id}.bsk`）。PyBridge
     * 运行时会从包内 manifest.json 读取 skill_id 确定目标文件名，
     * 并清除该 skill 的运行时缓存以确保加载新版本。
     *
     * @param bskFile 已下载的 .bsk 文件
     * @return 成功时返回 skill_id；失败时返回包含错误信息的 Result
     */
    suspend fun installSkill(bskFile: File): Result<String> = withContext(Dispatchers.IO) {
        if (!bskFile.exists()) {
            return@withContext Result.failure(
                BskFileNotFoundException("BSK file not found: ${bskFile.absolutePath}")
            )
        }

        Log.i(TAG, "Installing skill from: ${bskFile.absolutePath}")
        PyBridgeInterface.installSkillResult(bskFile.absolutePath)
    }

    /**
     * 卸载指定 Skill。
     *
     * 调用 [PyBridgeInterface.uninstallSkillResult]，删除 .bsk 文件、
     * 解压目录以及运行时缓存中的记录。
     *
     * @param skillId 要卸载的 Skill 唯一标识符
     * @return 成功时返回 Unit；失败时返回包含错误信息的 Result
     */
    suspend fun uninstallSkill(skillId: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Uninstalling skill: $skillId")
        PyBridgeInterface.uninstallSkillResult(skillId)
    }

    /**
     * 一键下载并安装 Skill。
     *
     * 封装了 [downloadSkill] + [installSkill] 的完整流程：
     * 1. 下载 .bsk 文件到临时目录（带进度回调）；
     * 2. 校验文件完整性（SHA256）；
     * 3. 通过 PyBridge 安装到 Skill 存放目录；
     * 4. 安装完成后删除临时下载文件；
     * 5. 返回安装后的 skill_id。
     *
     * 如果下载或安装任一步骤失败，会清理临时文件并返回失败。
     *
     * @param skillId   Skill 唯一标识符
     * @param version   要安装的版本号
     * @param onProgress 下载进度回调，参数为 0.0 ~ 1.0 之间的浮点数
     * @return 成功时返回 skill_id；失败时返回包含错误信息的 Result
     */
    suspend fun downloadAndInstall(
        skillId: String,
        version: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        var downloadedFile: File? = null

        try {
            // 1. 下载
            val downloadResult = downloadSkill(skillId, version, onProgress)
            downloadedFile = downloadResult.getOrElse { e ->
                return@withContext Result.failure(e)
            }

            // 2. 安装
            val installResult = installSkill(downloadedFile)
            if (installResult.isFailure) {
                return@withContext Result.failure(
                    installResult.exceptionOrNull()
                        ?: IOException("Installation failed for unknown reason")
                )
            }

            val installedSkillId = installResult.getOrThrow()
            Log.i(TAG, "Skill '$installedSkillId' v$version downloaded and installed successfully")

            Result.success(installedSkillId)
        } finally {
            // 3. 清理临时下载文件（无论成功或失败）
            downloadedFile?.let { file ->
                if (file.exists()) {
                    val deleted = file.delete()
                    if (!deleted) {
                        Log.w(TAG, "Failed to delete temp download: ${file.absolutePath}")
                    }
                }
            }
        }
    }


    // ================================================================
    // 四、本地 Skill 管理
    // ================================================================

    /**
     * 扫描本地已安装的 Skill 列表。
     *
     * 遍历 Skill 存放目录（`{filesDir}/skills/`）下所有 .bsk 文件，
     * 从每个包内读取 manifest.json 以获取元信息。无法读取 manifest
     * 的包会被跳过（以 skill_id 作为文件名推断）。
     *
     * 该方法不依赖 PyBridge 运行时，即使运行时未初始化也能工作，
     * 适用于在 UI 启动时快速展示已安装状态。
     *
     * @return 已安装 Skill 的信息列表
     */
    fun getInstalledSkills(): List<InstalledSkillInfo> {
        val result = mutableListOf<InstalledSkillInfo>()

        val bskFiles = skillsDir.listFiles { file ->
            file.isFile && file.name.endsWith(BSK_SUFFIX)
        } ?: return result

        for (bskFile in bskFiles) {
            // 从文件名提取 skill_id（去掉 .bsk 后缀）
            val skillId = bskFile.name.removeSuffix(BSK_SUFFIX)
            val installedDate = bskFile.lastModified()

            // 尝试从包内读取 manifest.json 获取详细信息
            val manifest = readManifest(bskFile)
            if (manifest != null) {
                result.add(
                    InstalledSkillInfo(
                        skillId = manifest.skillId,
                        name = manifest.name.ifEmpty { skillId },
                        version = manifest.version,
                        skillType = manifest.skillType,
                        installedDate = installedDate
                    )
                )
            } else {
                // manifest 读取失败时，仅使用文件名信息
                result.add(
                    InstalledSkillInfo(
                        skillId = skillId,
                        name = skillId,
                        version = "unknown",
                        skillType = "pure_python",
                        installedDate = installedDate
                    )
                )
            }
        }

        return result.sortedByDescending { it.installedDate }
    }


    // ================================================================
    // 五、更新检查
    // ================================================================

    /**
     * 检查所有已安装 Skill 的更新。
     *
     * 执行流程：
     * 1. 通过 [getInstalledSkills] 获取本地已安装的 Skill 列表；
     * 2. 对每个已安装 Skill，并发请求服务端详情获取最新版本号；
     * 3. 比较本地版本与服务端最新版本（语义化版本比较）；
     * 4. 返回所有需要更新的 Skill 列表。
     *
     * 版本比较规则：按 "." 分割为数字段逐段比较，
     * 如 "1.2.0" < "1.10.0"（数字比较，非字符串比较）。
     *
     * @return 成功时返回需要更新的 Skill 列表；失败时返回包含错误信息的 Result
     */
    suspend fun checkUpdates(): Result<List<SkillUpdateInfo>> = withContext(Dispatchers.IO) {
        try {
            val installedSkills = getInstalledSkills()
            if (installedSkills.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            // 并发查询每个已安装 skill 的服务端详情
            val updateInfos = coroutineScope {
                installedSkills.map { installed ->
                    async {
                        try {
                            val detailResult = getSkillDetail(installed.skillId)
                            val detail = detailResult.getOrElse { return@async null }

                            // 获取最新版本（versions 列表的第一个元素，假设按时间倒序）
                            val latestVersion = detail.versions.firstOrNull()?.version
                                ?: return@async null

                            // 比较版本号
                            val comparison = compareVersions(installed.version, latestVersion)
                            if (comparison < 0) {
                                // 本地版本 < 服务端版本，需要更新
                                SkillUpdateInfo(
                                    skillId = installed.skillId,
                                    currentVersion = installed.version,
                                    latestVersion = latestVersion,
                                    skillName = installed.name
                                )
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to check updates for '${installed.skillId}'", e)
                            null
                        }
                    }
                }.awaitAll()
            }

            val updates = updateInfos.filterNotNull()
            Log.i(TAG, "Update check complete: ${updates.size} update(s) available " +
                "out of ${installedSkills.size} installed skill(s)")
            Result.success(updates)
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdates error", e)
            Result.failure(e)
        }
    }


    // ================================================================
    // 六、私有辅助方法
    // ================================================================

    /**
     * 通用的 GET 请求方法。
     *
     * 构造 URL、发送请求、检查响应状态码、解析 JSON 响应体。
     *
     * @param path         API 路径（相对于 apiBaseUrl），如 "skills" 或 "skills/weather"
     * @param queryParams  查询参数键值对
     * @param deserializer 响应体的反序列化器
     * @return 成功时返回反序列化后的对象；失败时返回包含错误信息的 Result
     */
    private suspend fun <T> apiGet(
        path: String,
        queryParams: Map<String, String>,
        deserializer: DeserializationStrategy<T>
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(path, queryParams)
            Log.d(TAG, "API GET: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "API GET $path failed: HTTP ${response.code}, body=$errorBody")
                    return@use Result.failure(
                        HttpException(response.code, parseErrorMessage(errorBody, response.code))
                    )
                }

                val body = response.body?.string()
                    ?: return@use Result.failure(IOException("Empty response body"))

                val result = json.decodeFromString(deserializer, body)
                Result.success(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "API GET $path error", e)
            Result.failure(e)
        }
    }

    /**
     * 构造完整的 API URL。
     *
     * @param path        API 路径（相对于 apiBaseUrl）
     * @param queryParams 查询参数键值对
     * @return 完整的 URL 字符串
     */
    private fun buildUrl(path: String, queryParams: Map<String, String> = emptyMap()): String {
        val base = apiBaseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        val url = "$base/$cleanPath"

        if (queryParams.isEmpty()) {
            return url
        }

        val queryString = queryParams.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        return "$url?$queryString"
    }

    /**
     * URL 编码（UTF-8）。
     *
     * @param value 待编码的字符串
     * @return 编码后的字符串
     */
    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }

    /**
     * 计算文件的 SHA256 校验和。
     *
     * @param file 目标文件
     * @return 十六进制小写的 SHA256 字符串
     */
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    /**
     * 从 .bsk（ZIP）包中读取 manifest.json。
     *
     * @param bskFile .bsk 文件
     * @return 解析后的 [SkillManifest]；读取失败时返回 null
     */
    private fun readManifest(bskFile: File): SkillManifest? {
        return try {
            ZipFile(bskFile).use { zip ->
                val entry = zip.getEntry(MANIFEST_ENTRY) ?: return null
                zip.getInputStream(entry).use { stream ->
                    val content = stream.bufferedReader().readText()
                    json.decodeFromString(SkillManifest.serializer(), content)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read manifest from ${bskFile.name}: ${e.message}")
            null
        }
    }

    /**
     * 比较两个语义化版本号。
     *
     * 按 "." 分割为数字段逐段比较。非数字段视为 0。
     * 例如：compareVersions("1.2.0", "1.10.0") 返回 -1
     *       compareVersions("2.0.0", "1.9.9") 返回 1
     *       compareVersions("1.0.0", "1.0.0") 返回 0
     *
     * @param v1 版本号 1
     * @param v2 版本号 2
     * @return 负数表示 v1 < v2，0 表示相等，正数表示 v1 > v2
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }

    /**
     * 从错误响应体中解析错误消息。
     *
     * 尝试解析为 [ApiErrorResponse]，失败时返回默认消息。
     *
     * @param body      响应体字符串
     * @param statusCode HTTP 状态码
     * @return 错误消息字符串
     */
    private fun parseErrorMessage(body: String, statusCode: Int): String {
        return try {
            val errorResponse = json.decodeFromString(ApiErrorResponse.serializer(), body)
            if (errorResponse.detail.isNotEmpty()) {
                "${errorResponse.error}: ${errorResponse.detail}"
            } else {
                errorResponse.error.ifEmpty { "HTTP $statusCode" }
            }
        } catch (e: Exception) {
            "HTTP $statusCode"
        }
    }


    // ================================================================
    // 七、自定义异常
    // ================================================================

    /**
     * HTTP 错误异常。
     *
     * 当服务端返回非 2xx 状态码时抛出。
     *
     * @property statusCode HTTP 状态码
     * @property message    错误描述
     */
    class HttpException(
        val statusCode: Int,
        override val message: String
    ) : Exception(message)

    /**
     * BSK 文件未找到异常。
     *
     * 当指定的 .bsk 文件不存在时抛出。使用独立类名以避免与
     * java.io.FileNotFoundException 混淆。
     *
     * @property message 错误描述
     */
    class BskFileNotFoundException(
        override val message: String
    ) : Exception(message)
}
