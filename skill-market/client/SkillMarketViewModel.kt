package com.butler.skills

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
// SkillMarketViewModel.kt
// ============================================================
// Skill 市场 MVVM ViewModel。
//
// 作为 UI 层（Activity / Fragment / Compose）与业务逻辑层
// （SkillMarketManager）之间的桥梁，负责：
//   1. 持有并暴露不可变的 UI 状态（StateFlow）；
//   2. 将 UI 事件（加载、搜索、筛选、下载安装、卸载、检查更新）
//      转化为对 SkillMarketManager 的协程调用；
//   3. 将操作结果（成功/失败/进度）反映到 UI 状态中。
//
// UI 层通过观察 [uiState] StateFlow 获取最新状态，无需关心
// 异步操作的调度细节。所有网络请求均在 viewModelScope 中启动，
// ViewModel 销毁时自动取消未完成的协程。
//
// 架构示意：
//   ┌─────────────┐     StateFlow      ┌──────────────────┐
//   │  UI Layer   │ ◄────────────────── │  ViewModel       │
//   │  (Compose)  │     user events     │                  │
//   │             │ ──────────────────► │                  │
//   └─────────────┘                     └────────┬─────────┘
//                                                │ suspend calls
//                                                ▼
//                                       ┌──────────────────┐
//                                       │ SkillMarketManager│
//                                       └────────┬─────────┘
//                                                │ HTTP / PyBridge
//                                                ▼
//                                       ┌──────────────────┐
//                                       │  Backend / Runtime│
//                                       └──────────────────┘
// ============================================================


/**
 * Skill 市场 UI ViewModel。
 *
 * 通过 [SkillMarketManager] 执行所有业务操作，并以 [StateFlow] 形式
 * 向 UI 层暴露响应式状态。UI 层只需观察 [uiState] 并调用对应的
 * 事件方法即可完成全部交互。
 *
 * 使用示例：
 * ```
 * class SkillMarketScreen : ComponentActivity() {
 *     private val viewModel: SkillMarketViewModel by viewModels {
 *         // 通过 DI 或工厂创建
 *         SkillMarketViewModelFactory(SkillMarketManager(applicationContext))
 *     }
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *
 *         // 首次加载
 *         viewModel.refresh()
 *
 *         setContent {
 *             val uiState by viewModel.uiState.collectAsState()
 *
 *             if (uiState.isLoading) {
 *                 LoadingIndicator()
 *             } else {
 *                 SkillList(
 *                     skills = uiState.skills,
 *                     onInstall = { skill -> viewModel.downloadAndInstall(skill.id, skill.version) },
 *                     onUninstall = { skill -> viewModel.uninstallSkill(skill.id) }
 *                 )
 *             }
 *
 *             uiState.downloadingSkillId?.let { skillId ->
 *                 DownloadProgressBar(
 *                     skillId = skillId,
 *                     progress = uiState.downloadProgress
 *                 )
 *             }
 *
 *             uiState.error?.let { errorMsg ->
 *                 Snackbar { Text(errorMsg) }
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @property marketManager Skill 市场管理器实例
 * @property arch          设备架构（默认 "arm64-v8a"）
 * @property appVersion    当前 App 版本号（默认 "1.0.0"）
 */
class SkillMarketViewModel(
    private val marketManager: SkillMarketManager,
    private val arch: String = "arm64-v8a",
    private val appVersion: String = "1.0.0"
) : ViewModel() {

    companion object {
        private const val TAG = "SkillMarketViewModel"

        /** 默认每页大小。 */
        private const val DEFAULT_PAGE_SIZE = 20
    }

    /**
     * Skill 市场页面 UI 状态。
     *
     * 包含页面展示所需的全部数据，采用不可变 data class 设计。
     * 通过 [MutableStateFlow] + [update] 实现原子性更新。
     *
     * @property isLoading          是否正在加载 Skill 列表（非下载操作）
     * @property skills             从服务端获取的 Skill 列表（已标记安装状态）
     * @property installedSkills    本地已安装的 Skill 列表
     * @property updates            有可用更新的 Skill 列表
     * @property categories         分类列表
     * @property selectedCategory   当前选中的分类筛选，null 表示不限
     * @property searchQuery        当前搜索关键词，空字符串表示不搜索
     * @property error              错误信息，null 表示无错误
     * @property downloadingSkillId 正在下载安装的 Skill ID，null 表示无下载任务
     * @property downloadProgress   下载进度（0.0 ~ 1.0）
     * @property totalSkills        服务端 Skill 总数（用于分页展示）
     * @property currentPage        当前页码
     */
    data class SkillMarketUiState(
        val isLoading: Boolean = false,
        val skills: List<SkillInfo> = emptyList(),
        val installedSkills: List<InstalledSkillInfo> = emptyList(),
        val updates: List<SkillUpdateInfo> = emptyList(),
        val categories: List<String> = emptyList(),
        val selectedCategory: String? = null,
        val searchQuery: String = "",
        val error: String? = null,
        val downloadingSkillId: String? = null,
        val downloadProgress: Float = 0f,
        val totalSkills: Int = 0,
        val currentPage: Int = 1
    )

    /**
     * 可变的 UI 状态流，内部使用。
     */
    private val _uiState = MutableStateFlow(SkillMarketUiState())

    /**
     * 暴露给 UI 层的只读状态流。
     *
     * UI 层通过 `collectAsState()` 或 `collect` 订阅状态变化。
     */
    val uiState: StateFlow<SkillMarketUiState> = _uiState.asStateFlow()


    // ================================================================
    // 初始化
    // ================================================================

    init {
        // 初始化时加载本地已安装的 Skill 列表（不依赖网络）
        loadInstalledSkills()
    }


    // ================================================================
    // 一、Skill 列表加载
    // ================================================================

    /**
     * 加载 Skill 列表。
     *
     * 根据当前的筛选条件（分类、搜索关键词）和页码，从服务端获取
     * Skill 列表，并标记每个 Skill 的本地安装状态。
     *
     * 为确保安装状态标记准确，会先在 IO 线程上加载本地已安装 Skill
     * 列表，然后再发起服务端请求。这样避免了异步加载导致的竞态条件。
     */
    fun loadSkills() {
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            // 先在 IO 线程加载本地已安装列表，确保后续标记安装状态准确
            val installed = withContext(Dispatchers.IO) {
                marketManager.getInstalledSkills()
            }
            _uiState.update { it.copy(installedSkills = installed) }

            // 然后获取服务端 Skill 列表
            val searchQuery = _uiState.value.searchQuery.trim()
            val category = _uiState.value.selectedCategory
            val page = _uiState.value.currentPage

            marketManager.fetchSkills(
                arch = arch,
                appVersion = appVersion,
                category = category,
                search = searchQuery.ifEmpty { null },
                page = page
            ).onSuccess { response ->
                val markedSkills = markInstalledSkills(response.skills)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        skills = markedSkills,
                        totalSkills = response.total,
                        error = null
                    )
                }
                Log.d(TAG, "Loaded ${markedSkills.size} skills (total: ${response.total})")
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "加载失败: ${error.message}"
                    )
                }
                Log.e(TAG, "loadSkills failed", error)
            }
        }
    }

    /**
     * 搜索 Skill。
     *
     * 设置搜索关键词并重置页码为 1，然后加载匹配的 Skill 列表。
     * 传入空字符串则清除搜索条件。
     *
     * @param query 搜索关键词
     */
    fun searchSkills(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                currentPage = 1,
                skills = emptyList()
            )
        }
        loadSkills()
    }

    /**
     * 按分类筛选 Skill。
     *
     * 设置分类筛选条件并重置页码为 1，然后加载匹配的 Skill 列表。
     * 传入 null 则清除分类筛选。
     *
     * @param category 分类名称，传 null 表示不限分类
     */
    fun filterByCategory(category: String?) {
        _uiState.update {
            it.copy(
                selectedCategory = category,
                currentPage = 1,
                skills = emptyList()
            )
        }
        loadSkills()
    }

    /**
     * 加载更多（分页）。
     *
     * 页码 +1 后加载下一页数据，追加到现有列表末尾。
     * 仅当当前列表数量小于总数时才执行。
     */
    fun loadMore() {
        val currentState = _uiState.value
        if (currentState.skills.size >= currentState.totalSkills) {
            Log.d(TAG, "loadMore: already at last page")
            return
        }

        _uiState.update { it.copy(currentPage = it.currentPage + 1) }

        viewModelScope.launch {
            val searchQuery = _uiState.value.searchQuery.trim()
            val category = _uiState.value.selectedCategory
            val page = _uiState.value.currentPage

            marketManager.fetchSkills(
                arch = arch,
                appVersion = appVersion,
                category = category,
                search = searchQuery.ifEmpty { null },
                page = page
            ).onSuccess { response ->
                val markedSkills = markInstalledSkills(response.skills)
                _uiState.update {
                    it.copy(
                        skills = it.skills + markedSkills,
                        totalSkills = response.total,
                        error = null
                    )
                }
                Log.d(TAG, "Loaded page $page: +${markedSkills.size} skills")
            }.onFailure { error ->
                // 加载更多失败时回退页码
                _uiState.update {
                    it.copy(
                        currentPage = it.currentPage - 1,
                        error = "加载更多失败: ${error.message}"
                    )
                }
                Log.e(TAG, "loadMore failed", error)
            }
        }
    }


    // ================================================================
    // 二、下载与安装
    // ================================================================

    /**
     * 下载并安装 Skill。
     *
     * 执行 [SkillMarketManager.downloadAndInstall]，下载过程中
     * 实时更新 `downloadProgress` 和 `downloadingSkillId`。
     * 安装完成后刷新已安装列表和 Skill 列表（更新安装状态）。
     *
     * 同一时间只允许一个下载任务。若已有下载任务进行中，该调用会被忽略。
     *
     * @param skillId Skill 唯一标识符
     * @param version 要安装的版本号
     */
    fun downloadAndInstall(skillId: String, version: String) {
        // 防止重复下载
        if (_uiState.value.downloadingSkillId != null) {
            Log.w(TAG, "downloadAndInstall: another download is in progress")
            return
        }

        _uiState.update {
            it.copy(
                downloadingSkillId = skillId,
                downloadProgress = 0f,
                error = null
            )
        }

        viewModelScope.launch {
            marketManager.downloadAndInstall(
                skillId = skillId,
                version = version,
                onProgress = { progress ->
                    // 下载进度回调（在 IO 线程执行，StateFlow 更新是线程安全的）
                    if (progress >= 0) {
                        _uiState.update { it.copy(downloadProgress = progress) }
                    }
                }
            ).onSuccess { installedSkillId ->
                Log.i(TAG, "Skill installed: $installedSkillId")

                _uiState.update {
                    it.copy(
                        downloadingSkillId = null,
                        downloadProgress = 0f,
                        error = null
                    )
                }

                // 刷新已安装列表和 Skill 列表（更新安装状态）
                loadInstalledSkills()
                loadSkills()
            }.onFailure { error ->
                Log.e(TAG, "downloadAndInstall failed for '$skillId'", error)

                _uiState.update {
                    it.copy(
                        downloadingSkillId = null,
                        downloadProgress = 0f,
                        error = "安装失败: ${error.message}"
                    )
                }
            }
        }
    }


    // ================================================================
    // 三、卸载
    // ================================================================

    /**
     * 卸载 Skill。
     *
     * 调用 [SkillMarketManager.uninstallSkill]，卸载完成后刷新
     * 已安装列表和 Skill 列表（更新安装状态）。
     *
     * @param skillId 要卸载的 Skill 唯一标识符
     */
    fun uninstallSkill(skillId: String) {
        viewModelScope.launch {
            marketManager.uninstallSkill(skillId)
                .onSuccess {
                    Log.i(TAG, "Skill uninstalled: $skillId")
                    // 刷新已安装列表和 Skill 列表
                    loadInstalledSkills()
                    loadSkills()
                }.onFailure { error ->
                    Log.e(TAG, "uninstallSkill failed for '$skillId'", error)
                    _uiState.update {
                        it.copy(error = "卸载失败: ${error.message}")
                    }
                }
        }
    }


    // ================================================================
    // 四、更新检查
    // ================================================================

    /**
     * 检查 Skill 更新。
     *
     * 调用 [SkillMarketManager.checkUpdates]，查询所有已安装 Skill
     * 的服务端最新版本，与本地版本比较后更新 `updates` 状态。
     */
    fun checkUpdates() {
        viewModelScope.launch {
            marketManager.checkUpdates()
                .onSuccess { updates ->
                    _uiState.update { it.copy(updates = updates, error = null) }
                    Log.d(TAG, "Found ${updates.size} update(s)")
                }.onFailure { error ->
                    Log.e(TAG, "checkUpdates failed", error)
                    _uiState.update {
                        it.copy(error = "检查更新失败: ${error.message}")
                    }
                }
        }
    }


    // ================================================================
    // 五、刷新与分类加载
    // ================================================================

    /**
     * 刷新全部数据。
     *
     * 并行执行以下操作：
     * 1. 加载本地已安装 Skill 列表；
     * 2. 加载分类列表；
     * 3. 加载 Skill 列表（重置页码和筛选条件）；
     * 4. 检查更新。
     *
     * 适用于页面首次进入或用户手动下拉刷新。
     */
    fun refresh() {
        // 重置筛选条件和页码
        _uiState.update {
            it.copy(
                selectedCategory = null,
                searchQuery = "",
                currentPage = 1,
                error = null
            )
        }

        // 加载本地已安装列表（同步，不依赖网络）
        loadInstalledSkills()

        // 加载分类列表
        loadCategories()

        // 加载 Skill 列表
        loadSkills()

        // 检查更新
        checkUpdates()
    }

    /**
     * 加载分类列表。
     *
     * 从服务端获取分类列表并更新 UI 状态。
     */
    fun loadCategories() {
        viewModelScope.launch {
            marketManager.getCategories()
                .onSuccess { categories ->
                    _uiState.update { it.copy(categories = categories) }
                    Log.d(TAG, "Loaded ${categories.size} categories")
                }.onFailure { error ->
                    Log.e(TAG, "loadCategories failed", error)
                    // 分类加载失败不阻塞主流程，仅记录日志
                }
        }
    }


    // ================================================================
    // 六、UI 辅助方法
    // ================================================================

    /**
     * 清除错误状态。
     *
     * UI 层在显示错误消息后（如 Snackbar 消失时）调用此方法清除错误。
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 清除搜索条件。
     *
     * 重置搜索关键词并重新加载全部 Skill。
     */
    fun clearSearch() {
        searchSkills("")
    }


    // ================================================================
    // 七、私有辅助方法
    // ================================================================

    /**
     * 加载本地已安装 Skill 列表。
     *
     * 调用 [SkillMarketManager.getInstalledSkills] 扫描本地目录。
     * 文件 I/O 操作在 Dispatchers.IO 上执行，避免阻塞主线程。
     * 该方法异步执行，调用后不会立即更新状态。
     */
    private fun loadInstalledSkills() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) {
                marketManager.getInstalledSkills()
            }
            _uiState.update { it.copy(installedSkills = installed) }
        }
    }

    /**
     * 标记 Skill 列表的安装状态。
     *
     * 根据本地已安装的 Skill ID 集合，将服务端返回的 SkillInfo
     * 的 `isInstalled` 字段设置为正确值。
     *
     * @param skills 服务端返回的 Skill 列表
     * @return 标记安装状态后的 Skill 列表
     */
    private fun markInstalledSkills(skills: List<SkillInfo>): List<SkillInfo> {
        val installedIds = _uiState.value.installedSkills
            .map { it.skillId }
            .toSet()

        return skills.map { skill ->
            skill.copy(isInstalled = skill.id in installedIds)
        }
    }


    // ================================================================
    // ViewModel 生命周期
    // ================================================================

    /**
     * ViewModel 被销毁时清理资源。
     *
     * viewModelScope 中的所有协程会自动取消。
     * OkHttp 客户端的连接池由 JVM 管理，无需手动关闭。
     */
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "SkillMarketViewModel cleared")
    }
}
