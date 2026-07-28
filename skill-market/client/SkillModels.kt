package com.butler.skills

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================================
// SkillModels.kt
// ============================================================
// Skill 市场客户端的全部数据模型。
//
// 使用 kotlinx.serialization 进行 JSON 序列化/反序列化。
// 后端 API 返回的字段使用 snake_case 命名（如 skill_type、min_app_version），
// Kotlin 侧使用 camelCase 命名，通过 @SerialName 注解进行映射。
//
// 模型来源对应后端 models.py 中的 Pydantic 模型：
//   - SkillInfo          <-> SkillListItem
//   - SkillListResponse  <-> SkillListResponse
//   - SkillDetail        <-> SkillDetail
//   - VersionInfo        <-> VersionInfo
// ============================================================


// ============================================================
// 一、API 响应数据模型
// ============================================================

/**
 * Skill 列表项信息。
 *
 * 对应后端 `SkillListItem` 模型，包含 Skill 的核心元数据以及
 * 最新版本号、下载链接、下载次数等信息。当客户端在查询时传入
 * 已安装 skill ID 集合，`isInstalled` 字段会反映安装状态。
 *
 * @property id           Skill 唯一标识符（对应 manifest 中的 skill_id）
 * @property name         Skill 展示名称
 * @property version      当前（最新）版本号，如 "1.0.0"
 * @property description  Skill 功能描述
 * @property category     分类，如 "productivity"、"entertainment"
 * @property author       作者
 * @property skillType    Skill 类型："pure_python" 或 "c_extension"
 * @property arch         支持的架构列表，如 ["arm64-v8a", "universal"]
 * @property minAppVersion 所需的最低 App 版本
 * @property downloadUrl  .bsk 包下载链接
 * @property downloadCount 总下载次数（所有版本合计）
 * @property sizeBytes    包文件大小（字节），可能为 0（服务端未提供时）
 * @property isInstalled  客户端是否已安装该 skill
 */
@Serializable
data class SkillInfo(
    @SerialName("id")
    val id: String,

    @SerialName("name")
    val name: String,

    @SerialName("version")
    val version: String,

    @SerialName("description")
    val description: String = "",

    @SerialName("category")
    val category: String = "other",

    @SerialName("author")
    val author: String = "",

    @SerialName("skill_type")
    val skillType: String = "pure_python",

    @SerialName("arch")
    val arch: List<String> = emptyList(),

    @SerialName("min_app_version")
    val minAppVersion: String = "0.0.0",

    @SerialName("download_url")
    val downloadUrl: String = "",

    @SerialName("download_count")
    val downloadCount: Int = 0,

    @SerialName("size_bytes")
    val sizeBytes: Long = 0L,

    @SerialName("is_installed")
    val isInstalled: Boolean = false
)

/**
 * Skill 列表分页响应。
 *
 * 对应后端 `SkillListResponse` 模型。
 *
 * @property skills   当前页的 Skill 列表
 * @property total    符合查询条件的 Skill 总数（分页前）
 * @property page     当前页码（从 1 开始）
 * @property pageSize 每页大小
 */
@Serializable
data class SkillListResponse(
    @SerialName("skills")
    val skills: List<SkillInfo> = emptyList(),

    @SerialName("total")
    val total: Int = 0,

    @SerialName("page")
    val page: Int = 1,

    @SerialName("page_size")
    val pageSize: Int = 20
)

/**
 * Skill 详情信息。
 *
 * 对应后端 `SkillDetail` 模型，在列表项基础上附加全部版本历史。
 * 客户端通过 [SkillMarketManager.getSkillDetail] 获取。
 *
 * @property id          Skill 唯一标识符
 * @property name        Skill 展示名称
 * @property description Skill 功能描述
 * @property category    分类
 * @property author      作者
 * @property versions    所有版本信息列表（按时间倒序）
 */
@Serializable
data class SkillDetail(
    @SerialName("id")
    val id: String,

    @SerialName("name")
    val name: String,

    @SerialName("description")
    val description: String = "",

    @SerialName("category")
    val category: String = "other",

    @SerialName("author")
    val author: String = "",

    @SerialName("versions")
    val versions: List<VersionInfo> = emptyList()
)

/**
 * 单个 Skill 版本的附加信息。
 *
 * 对应后端 `VersionInfo` 模型。每个版本包含 SHA256 校验和，
 * 客户端在下载完成后可据此校验文件完整性。
 *
 * @property version       版本号，如 "1.2.0"
 * @property sha256        包文件的 SHA256 校验和（十六进制小写）
 * @property uploadDate    上传时间（ISO 8601 格式字符串）
 * @property downloadCount 该版本的累计下载次数
 * @property sizeBytes     包文件大小（字节）
 */
@Serializable
data class VersionInfo(
    @SerialName("version")
    val version: String,

    @SerialName("sha256")
    val sha256: String,

    @SerialName("upload_date")
    val uploadDate: String = "",

    @SerialName("download_count")
    val downloadCount: Int = 0,

    @SerialName("size_bytes")
    val sizeBytes: Long = 0L
)


// ============================================================
// 二、更新检查与本地安装信息
// ============================================================

/**
 * Skill 更新信息。
 *
 * 由 [SkillMarketManager.checkUpdates] 返回，描述一个已安装 Skill
 * 与服务端最新版本之间的差异。
 *
 * @property skillId        Skill 唯一标识符
 * @property currentVersion 客户端当前已安装的版本号
 * @property latestVersion  服务端最新可用版本号
 * @property skillName      Skill 展示名称（便于 UI 展示）
 */
@Serializable
data class SkillUpdateInfo(
    @SerialName("skill_id")
    val skillId: String,

    @SerialName("current_version")
    val currentVersion: String,

    @SerialName("latest_version")
    val latestVersion: String,

    @SerialName("skill_name")
    val skillName: String
)

/**
 * 本地已安装 Skill 信息。
 *
 * 由 [SkillMarketManager.getInstalledSkills] 扫描本地 skills 目录得到。
 * 该模型不参与网络序列化，因此不需要 @Serializable 注解。
 *
 * @property skillId      Skill 唯一标识符
 * @property name         Skill 展示名称（来自 manifest.json）
 * @property version      已安装版本号（来自 manifest.json）
 * @property skillType    Skill 类型："pure_python" 或 "c_extension"
 * @property installedDate 安装时间戳（.bsk 文件的最后修改时间，毫秒）
 */
data class InstalledSkillInfo(
    val skillId: String,
    val name: String,
    val version: String,
    val skillType: String,
    val installedDate: Long
)


// ============================================================
// 三、PyBridge JNI 返回结果解析模型
// ============================================================

/**
 * PyBridge JNI 调用的统一返回结构。
 *
 * JNI 层（pybridge_jni.cpp）将 Python 函数的返回值通过 json.dumps
 * 序列化为 JSON 字符串。不同操作的返回结构略有不同，但均包含
 * `success` 字段。以下模型覆盖了安装、卸载等操作的返回格式。
 *
 * @property success     操作是否成功
 * @property skillId     成功安装时返回的 skill_id（仅 install_skill）
 * @property installedPath 安装后的目标文件路径（仅 install_skill）
 * @property error       失败时的错误描述
 */
@Serializable
data class PyBridgeResult(
    @SerialName("success")
    val success: Boolean = false,

    @SerialName("skill_id")
    val skillId: String? = null,

    @SerialName("installed_path")
    val installedPath: String? = null,

    @SerialName("error")
    val error: String? = null
)

/**
 * PyBridge list_installed_skills() 返回的单条 Skill 信息。
 *
 * 对应 Python 侧 SkillLoader.list_all() 返回的字典元素，
 * 字段来自 manifest.json。
 *
 * @property skillId      Skill 唯一标识符
 * @property name         Skill 展示名称
 * @property version      版本号
 * @property description  功能描述
 * @property entryFile    入口模块文件名
 * @property entryFunction 入口函数名称
 * @property author       作者
 */
@Serializable
data class PyBridgeSkillEntry(
    @SerialName("skill_id")
    val skillId: String,

    @SerialName("name")
    val name: String = "",

    @SerialName("version")
    val version: String = "",

    @SerialName("description")
    val description: String = "",

    @SerialName("entry_file")
    val entryFile: String = "main.py",

    @SerialName("entry_function")
    val entryFunction: String = "run",

    @SerialName("author")
    val author: String = ""
)


// ============================================================
// 四、本地 manifest.json 解析模型
// ============================================================

/**
 * .bsk 包内 manifest.json 的数据模型。
 *
 * 客户端在扫描本地已安装 Skill 时，需要从 .bsk（ZIP）包中读取
 * manifest.json 以获取元信息。该模型对应 Python 侧
 * SkillLoader._load_manifest() 解析的结构。
 *
 * @property skillId       Skill 唯一标识符
 * @property name          Skill 展示名称
 * @property version       语义化版本号
 * @property description   功能描述
 * @property entryFile     入口模块文件名
 * @property entryFunction 入口函数名称
 * @property author        作者
 * @property skillType     Skill 类型（可选，未声明时默认 "pure_python"）
 */
@Serializable
data class SkillManifest(
    @SerialName("skill_id")
    val skillId: String,

    @SerialName("name")
    val name: String = "",

    @SerialName("version")
    val version: String = "",

    @SerialName("description")
    val description: String = "",

    @SerialName("entry_file")
    val entryFile: String = "main.py",

    @SerialName("entry_function")
    val entryFunction: String = "run",

    @SerialName("author")
    val author: String = "",

    @SerialName("skill_type")
    val skillType: String = "pure_python"
)


// ============================================================
// 五、API 错误响应模型
// ============================================================

/**
 * 后端通用错误响应。
 *
 * 对应后端 `ErrorResponse` 模型。HTTP 状态码非 2xx 时，
 * 响应体为此结构。
 *
 * @property error  错误类型/简短描述
 * @property detail 错误详细信息
 */
@Serializable
data class ApiErrorResponse(
    @SerialName("error")
    val error: String = "",

    @SerialName("detail")
    val detail: String = ""
)
