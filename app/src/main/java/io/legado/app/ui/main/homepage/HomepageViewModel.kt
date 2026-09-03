package io.legado.app.ui.main.homepage

import android.app.Application
import android.text.Html
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.repository.HomepageModulesRepository
import io.legado.app.domain.gateway.HomepageModulesGateway
import io.legado.app.domain.model.BookShelfState
import io.legado.app.domain.model.CustomSetItem
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.domain.model.ModuleDef
import io.legado.app.domain.model.ModuleItem
import io.legado.app.help.book.BookshelfMatcher
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.sortUrls
import io.legado.app.model.rss.Rss
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 聚合主页 ViewModel
 *
 * 负责主页的数据加载、状态管理和业务逻辑，包括：
 * - 模块内容的异步加载与状态管理（加载中/成功/错误）
 * - 书源 homepageModules JSON 的同步与增量更新（基于 MD5 哈希的变更检测）
 * - 自定义集的创建、编辑、删除和排序
 * - 模块的启用/禁用、排序、编辑和删除
 * - 书架状态查询与书籍添加
 * - 发现页分类（ExploreKind）的获取
 *
 * 模块 ID 编码格式：setId::sourceUrl::moduleKey；集 ID 前缀：src_（书源集）、rss_（订阅源集）、cs_（用户自定义集）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomepageViewModel(application: Application) : BaseViewModel(application) {

    companion object {
        private const val CUSTOM_SET_URL_PREFIX = "custom://"

        /** 将自定义集 ID 转换为 URL 格式 */
        fun customSetUrl(id: String) = "$CUSTOM_SET_URL_PREFIX$id"

        /** 判断 URL 是否为自定义集 */
        fun isCustomSetUrl(url: String) = url.startsWith(CUSTOM_SET_URL_PREFIX)

        /** 从 URL 中提取自定义集 ID */
        fun customSetIdFromUrl(url: String): String = url.removePrefix(CUSTOM_SET_URL_PREFIX)

        /** 判断模块是否为无限流类型（每个集仅允许存在一个） */
        fun isInfinite(type: String?): Boolean {
            return type == HomepageModuleType.Waterfall.key
                    || type == HomepageModuleType.InfiniteGrid.key
        }

        /** 从书源的 homepageModules JSON 解析模块定义列表 */
        private fun parseModuleDefs(sourceUrl: String, json: String): List<ModuleDef> =
            GSON.fromJsonArray<ModuleDef>(json).getOrDefault(emptyList())
                .map { it.copy(sourceUrl = sourceUrl) }

        /** 计算 JSON 字符串的 MD5 哈希值，用于增量同步的变更检测 */
        private fun jsonHash(json: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(json.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** 按集分组并保持顺序（自定义集优先，书源集随后） */
        private fun List<ModuleItem>.groupBySourceOrdered(): Map<String, List<ModuleItem>> {
            val result = linkedMapOf<String, MutableList<ModuleItem>>()
            for (module in this) {
                val key = module.customSetId?.let { customSetUrl(it) } ?: module.sourceUrl
                result.getOrPut(key) { mutableListOf() }.add(module)
            }
            return result
        }
    }

    private val gateway: HomepageModulesGateway =
        HomepageModulesRepository(appDb.homepageModuleDao, appDb.homepageCustomSetDao)

    private val _effects = MutableSharedFlow<HomepageEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private val loadJobs = ConcurrentHashMap<String, Job>()

    private val _isRefreshing = MutableStateFlow(false)
    private val _refreshingModuleIds = MutableStateFlow<Set<String>>(emptySet())
    private val _configVersion = MutableStateFlow(0L)
    private val _moduleContentStates = MutableStateFlow<Map<String, ModuleLoadState>>(emptyMap())
    private val _bookSourcesCache =
        MutableStateFlow<Map<String, BookSourcePartLite>>(emptyMap())
    private val _rssSourceNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 分源Tab 模式：当前选中页索引与页对应的集 URL 列表（由 UI 回传，用于按需加载） */
    private var currentTabIndex = 0
    private val _currentTabSetUrls = MutableStateFlow<List<String>>(emptyList())

    private val localModulesFlow = gateway.flowEnabled()
    val allModulesCache = gateway.flowAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 自定义集列表同步读取：每次 _configVersion 变化时直接从数据库重新读取，
     * 确保排序变更后各 Flow 立即拿到最新顺序，规避 Room Flow 异步发射延迟。
     */
    private val customSetsSync = _configVersion.mapLatest {
        gateway.flowCustomSets().first()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val orderedModuleDefsFlow = combine(localModulesFlow, _configVersion) { modules, _ ->
        modules.groupBySourceOrdered()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val rawModulesFlow = combine(
        orderedModuleDefsFlow,
        _moduleContentStates,
        _bookSourcesCache,
        customSetsSync,
    ) { grouped, contentStates, sourcesCache, customSets ->
        val setNames = customSets.associate { it.id to it.name }
        val sortedSetIds = customSets.sortedBy { it.sortOrder }.map { it.id }
        val hidden = hiddenSetUrls

        sortedSetIds.flatMap { setId ->
            val isSourceSet = setId.startsWith("src_") || setId.startsWith("rss_")
            val setUrl = if (isSourceSet) setId else customSetUrl(setId)
            if (setUrl in hidden) return@flatMap emptyList()
            val mods = grouped[customSetUrl(setId)] ?: emptyList()
            mods.map { module ->
                val source = sourcesCache[module.sourceUrl]
                val sourceName = source?.name ?: module.sourceUrl
                val setName = module.customSetId?.let { setNames[it] } ?: sourceName
                val exploreUrl = module.url ?: source?.exploreUrl
                HomepageModuleUi(
                    sourceUrl = module.sourceUrl,
                    setName = setName,
                    globalId = module.id,
                    type = HomepageModuleType.fromKey(module.type),
                    title = module.displayTitle,
                    exploreUrl = exploreUrl,
                    customSetId = module.customSetId,
                    layoutConfig = module.layoutConfig,
                    state = contentStates[module.id] ?: ModuleLoadState.Loading,
                )
            }
        }
    }

    private val displayModulesFlow = combine(
        rawModulesFlow,
        BookshelfMatcher.version
    ) { modules, _ ->
        modules.map { module ->
            updateModuleShelfState(module) { item ->
                BookshelfMatcher.getState(item.book.name, item.book.author, item.book.bookUrl)
            }
        }
    }

    /**
     * 更新模块中书籍的书架状态，统一处理 Loaded 和 RankingTabs 两种状态。
     */
    private fun updateModuleShelfState(
        module: HomepageModuleUi,
        resolveState: (HomepageBookItemUi) -> BookShelfState
    ): HomepageModuleUi {
        val state = module.state
        return when (state) {
            is ModuleLoadState.Loaded -> {
                module.copy(state = state.copy(
                    books = state.books.map { item ->
                        val newShelfState = resolveState(item)
                        if (item.shelfState == newShelfState) item
                        else item.copy(shelfState = newShelfState)
                    }
                ))
            }
            is ModuleLoadState.RankingTabs -> {
                module.copy(state = state.copy(
                    tabs = state.tabs.map { tab ->
                        val books = tab.books ?: return@map tab
                        tab.copy(books = books.map { item ->
                            val newShelfState = resolveState(item)
                            if (item.shelfState == newShelfState) item
                            else item.copy(shelfState = newShelfState)
                        })
                    }
                ))
            }
            else -> module
        }
    }

    // ==================== 管理数据流 ====================

    private val hiddenSetUrls: Set<String>
        get() {
            val json = AppConfig.homepageSourceHidden
            if (json.isBlank()) return emptySet()
            return GSON.fromJsonArray<String>(json).getOrDefault(emptySet()).toSet()
        }

    private fun saveHiddenSetUrls(urls: Set<String>) {
        AppConfig.homepageSourceHidden = GSON.toJson(urls)
    }

    /** 集列表管理界面的数据流 */
    val setsFlow = combine(customSetsSync, allModulesCache, _configVersion) { sets, modules, _ ->
        val hidden = hiddenSetUrls
        sets.map { cs ->
            val isSourceSet = cs.id.startsWith("src_") || cs.id.startsWith("rss_")
            val setUrl = if (isSourceSet) cs.id else customSetUrl(cs.id)
            val count = modules.count { it.customSetId == cs.id }
            val sourceType = when {
                cs.id.startsWith("src_") -> "book"
                cs.id.startsWith("rss_") -> "rss"
                else -> null
            }
            HomepageSourceManageUi(
                sourceUrl = setUrl,
                sourceName = cs.name,
                isSelected = setUrl !in hidden,
                moduleCount = count,
                isCustomSet = !isSourceSet,
                sourceType = sourceType,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 可添加模块的书源列表（启用且具备发现能力的书源） */
    val browseSourcesFlow = combine(
        _bookSourcesCache,
        allModulesCache,
        _configVersion
    ) { sources, modules, _ ->
        sources.values.map { source ->
            val count = modules.count { it.sourceUrl == source.url }
            HomepageSourceManageUi(
                sourceUrl = source.url,
                sourceName = source.name,
                sourceGroup = source.group,
                moduleCount = count,
                isCustomSet = false,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val manageStateFlow = combine(
        setsFlow,
        browseSourcesFlow,
        allModulesCache,
        _bookSourcesCache,
        _rssSourceNames
    ) { sets, browseSources, modules, sources, rssNames ->
        val sourceNames = sources.values.associate { it.url to it.name } + rssNames
        val allJoined = modules.map { mod ->
            HomepageModuleManageUi(
                id = mod.id,
                sourceUrl = mod.sourceUrl,
                sourceName = sourceNames[mod.sourceUrl] ?: mod.sourceUrl,
                moduleKey = mod.moduleKey,
                title = mod.displayTitle,
                customSetTitle = mod.customSetTitle,
                customSetId = mod.customSetId,
                isVisible = mod.isEnabled,
                type = mod.type,
                url = mod.url,
                args = mod.args,
                layoutConfig = mod.layoutConfig,
                originalTitle = mod.title,
                sourceType = if (rssNames.containsKey(mod.sourceUrl)) "rss" else "book",
            )
        }
        HomepageManageUiState(
            sets = sets,
            browseSources = browseSources,
            rssSources = rssNames.map { (url, name) ->
                HomepageSourceManageUi(
                    sourceUrl = url,
                    sourceName = name,
                    isCustomSet = false,
                )
            },
            allJoinedModules = allJoined,
            sourceNames = sourceNames,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomepageManageUiState())

    val uiState: StateFlow<HomepageUiState> = combine(
        displayModulesFlow,
        _isRefreshing,
        manageStateFlow,
        _configVersion
    ) { modules, isRefreshing, manageState, _ ->
        HomepageUiState(
            modules = modules,
            isRefreshing = isRefreshing,
            manageState = manageState,
            layoutMode = AppConfig.homepageLayoutMode,
            preloadMode = AppConfig.homepagePreload,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomepageUiState())

    init {
        // 跟踪所有启用了发现功能的书源（用于浏览书源模块列表），不自动同步模块
        viewModelScope.launch {
            appDb.bookSourceDao.flowEnabledExplore().collect { sources ->
                _bookSourcesCache.value = sources.associate {
                    it.bookSourceUrl to BookSourcePartLite(
                        url = it.bookSourceUrl,
                        name = it.bookSourceName,
                        group = it.bookSourceGroup,
                        exploreUrl = null,
                    )
                }
            }
        }

        // 跟踪所有订阅源名称
        viewModelScope.launch {
            appDb.rssSourceDao.flowAll().collect { sources ->
                _rssSourceNames.value = sources.associate { it.sourceUrl to it.sourceName }
            }
        }

        // 自动加载进入 Loading 状态的模块；分源Tab 模式下只加载当前 Tab（含预加载相邻集）的模块
        viewModelScope.launch {
            uiState.mapLatest { it.modules }.collect { modules ->
                val loadSetIds = shouldLoadSetIds()
                modules.forEach { ui ->
                    if (ui.state is ModuleLoadState.Loading && loadJobs[ui.globalId]?.isActive != true) {
                        val shouldLoad = when {
                            _isRefreshing.value -> ui.globalId in _refreshingModuleIds.value
                            loadSetIds != null -> ui.customSetId != null && ui.customSetId in loadSetIds
                            else -> true
                        }
                        if (shouldLoad) {
                            val module = gateway.getById(ui.globalId)
                            if (module != null) loadModule(module)
                        }
                    }
                }
            }
        }

        // 监听模块状态变化，更新刷新状态
        viewModelScope.launch {
            _moduleContentStates.collect { states ->
                if (_isRefreshing.value) {
                    val targetIds = _refreshingModuleIds.value
                    val allLoaded = if (targetIds.isNotEmpty()) {
                        targetIds.all { id ->
                            val state = states[id]
                            state != null && state !is ModuleLoadState.Loading
                        }
                    } else {
                        states.values.none { it is ModuleLoadState.Loading } && states.isNotEmpty()
                    }
                    if (allLoaded) {
                        kotlinx.coroutines.delay(400)
                        _isRefreshing.value = false
                        _refreshingModuleIds.value = emptySet()
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
    }

    // ==================== 模块加载 ====================

    private suspend fun syncModulesFromSource(source: BookSource) {
        val json = source.homepageModules ?: return
        if (json.isBlank()) return
        ensureSetForSource(source.bookSourceUrl, source.bookSourceName)
        val parsedDefs = parseModuleDefs(source.bookSourceUrl, json)
        val newHash = jsonHash(json)

        val existingModules = gateway.flowBySource(source.bookSourceUrl).first()
        val existingById = existingModules.associateBy { it.id }
        val parsedIds = parsedDefs.map { it.globalId }.toSet()

        val toUpsert = mutableListOf<ModuleItem>()
        for (i in parsedDefs.indices) {
            val def = parsedDefs[i]
            val existing = existingById[def.globalId]
            if (existing != null) {
                if (existing.isUserCreated) continue
                if (existing.sourceJsonHash == newHash) continue
                toUpsert.add(
                    existing.copy(
                        type = def.type, title = def.title, args = def.args, url = def.url,
                        sourceJsonHash = newHash, syncedAt = System.currentTimeMillis()
                    )
                )
            } else {
                toUpsert.add(
                    ModuleItem(
                        id = def.globalId,
                        sourceUrl = source.bookSourceUrl,
                        moduleKey = def.key,
                        type = def.type,
                        title = def.title,
                        args = def.args,
                        url = def.url,
                        isEnabled = true,
                        customSetId = "src_${source.bookSourceUrl}",
                        sortOrder = i,
                        sourceJsonHash = newHash,
                        syncedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        if (toUpsert.isNotEmpty()) gateway.upsertAll(toUpsert)
        if (parsedIds.isNotEmpty()) gateway.deleteStale(source.bookSourceUrl, parsedIds.toList())
    }

    private fun loadModule(module: ModuleItem) {
        loadJobs[module.id]?.cancel()
        if (module.type == HomepageModuleType.ButtonGroup.key) {
            loadJobs[module.id] = viewModelScope.launch {
                kotlin.runCatching {
                    val selectedTitles = parseKindTitlesFromArgs(module.args)
                    if (selectedTitles.isNullOrEmpty()) {
                        emptyList<ExploreKind>()
                    } else {
                        val rssSource = appDb.rssSourceDao.getByKey(module.sourceUrl)
                        if (rssSource != null) {
                            val allKinds = rssSource.sortUrls().map { (title, url) ->
                                ExploreKind(title = title, url = url)
                            }
                            selectedTitles.mapNotNull { t -> allKinds.find { it.title == t } }
                        } else {
                            val source = appDb.bookSourceDao.getBookSource(module.sourceUrl)
                                ?: throw Exception("Source not found")
                            val allKinds = withContext(Dispatchers.IO) { source.exploreKinds() }
                            selectedTitles.mapNotNull { t -> allKinds.find { it.title == t } }
                        }
                    }
                }.onSuccess { kinds ->
                    _moduleContentStates.update { it + (module.id to ModuleLoadState.Buttons(kinds)) }
                }.onFailure { e ->
                    _moduleContentStates.update { it + (module.id to ModuleLoadState.Error(e.stackTraceStr)) }
                }
            }.also { it.invokeOnCompletion { loadJobs.remove(module.id) } }
            return
        }
        // 多分类 tab 模式：args 包含 [{t:标题, u:URL}]（1~N 个），按钮组以外所有类型通用，
        // 单分类即单 tab，头统一为 tab 样式，不再分单选多选
        val rankingCategoryPairs = if (module.type == HomepageModuleType.ButtonGroup.key) {
            null
        } else {
            parseRankingCategories(module.args)
        }

        if (!rankingCategoryPairs.isNullOrEmpty()) {
            val rssSource = appDb.rssSourceDao.getByKey(module.sourceUrl)
            val initialTabs = rankingCategoryPairs.map { (title, url) ->
                RankingTabData(
                    title = title,
                    exploreUrl = url.ifBlank { null },
                    page = 1,
                    hasMore = true,
                    isLoadingMore = false
                )
            }
            _moduleContentStates.update { it + (module.id to ModuleLoadState.RankingTabs(initialTabs)) }
            if (rankingCategoryPairs.isNotEmpty()) {
                val (title, url) = rankingCategoryPairs[0]
                loadRankingTab(module.id, module.sourceUrl, rssSource, 0, title, url, page = 1)
            }
            return
        }
        loadJobs[module.id] = viewModelScope.launch {
            kotlin.runCatching {
                // 检查是否为订阅源模块
                val rssSource = appDb.rssSourceDao.getByKey(module.sourceUrl)
                if (rssSource != null) {
                    val sortUrl = module.url ?: rssSource.sourceUrl
                    val sortName = module.title.ifBlank { rssSource.sourceName }
                    val (articles, _) = withContext(Dispatchers.IO) {
                        Rss.getArticlesAwait(sortName, sortUrl, rssSource, page = 1)
                    }
                    val books = articles.map { article ->
                        val introText = article.description?.let {
                            Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                        }
                        SearchBook(
                            bookUrl = article.link,
                            origin = rssSource.sourceUrl,
                            originName = rssSource.sourceName,
                            name = article.title,
                            coverUrl = article.image,
                            intro = introText,
                            author = rssSource.sourceName,
                            latestChapterTitle = article.pubDate,
                        )
                    }
                    books to false
                } else {
                    // 有 args 的已在上面走 tab 流，到这里 args 为空，直接用模块 URL
                    exploreBooks(
                        sourceUrl = module.sourceUrl,
                        moduleUrl = module.url,
                        page = 1
                    )
                }
            }.onSuccess { (books, hasMore) ->
                _moduleContentStates.update {
                    it + (module.id to ModuleLoadState.Loaded(
                        books = books.map { book ->
                            HomepageBookItemUi(
                                book = book,
                                shelfState = BookshelfMatcher.getState(
                                    book.name, book.author, book.bookUrl
                                )
                            )
                        },
                        hasMore = hasMore,
                        page = 1,
                        isLoadingMore = false
                    ))
                }
            }.onFailure { e ->
                _moduleContentStates.update { it + (module.id to ModuleLoadState.Error(e.stackTraceStr)) }
            }
        }.also { it.invokeOnCompletion { loadJobs.remove(module.id) } }
    }

    private suspend fun exploreBooks(
        sourceUrl: String,
        moduleUrl: String?,
        page: Int
    ): Pair<List<SearchBook>, Boolean> = withContext(Dispatchers.IO) {
        val source = appDb.bookSourceDao.getBookSource(sourceUrl)
            ?: throw Exception("Source not found: $sourceUrl")
        val url = moduleUrl ?: source.exploreUrl
            ?: throw Exception("No explore url: $sourceUrl")
        val books = WebBook.exploreBookAwait(source, url, page)
        books to books.isNotEmpty()
    }

    fun loadMoreModule(globalId: String) {
        val currentState = _moduleContentStates.value[globalId] as? ModuleLoadState.Loaded ?: return
        if (currentState.isLoadingMore || !currentState.hasMore) return
        val nextPage = currentState.page + 1
        _moduleContentStates.update { it + (globalId to currentState.copy(isLoadingMore = true)) }
        viewModelScope.launch {
            kotlin.runCatching {
                val module = gateway.getById(globalId) ?: throw Exception("Module not found")
                // 有 args 的走 tab 流的 loadMoreRankingTab，到这里 args 为空，直接用模块 URL
                exploreBooks(
                    sourceUrl = module.sourceUrl,
                    moduleUrl = module.url,
                    page = nextPage
                )
            }.onSuccess { (books, hasMore) ->
                _moduleContentStates.update { states ->
                    val lastState = states[globalId] as? ModuleLoadState.Loaded ?: return@update states
                    val existingUrls = lastState.books.map { it.book.bookUrl }.toSet()
                    val deduped = books.filter { it.bookUrl !in existingUrls }.map { book ->
                        HomepageBookItemUi(
                            book = book,
                            shelfState = BookshelfMatcher.getState(
                                book.name, book.author, book.bookUrl
                            )
                        )
                    }
                    val finalHasMore = if (deduped.isEmpty()) false else hasMore
                    states + (globalId to ModuleLoadState.Loaded(
                        books = lastState.books + deduped,
                        hasMore = finalHasMore,
                        isLoadingMore = false,
                        page = nextPage
                    ))
                }
            }.onFailure { e ->
                _moduleContentStates.update { states ->
                    val lastState = states[globalId] as? ModuleLoadState.Loaded ?: return@update states
                    states + (globalId to lastState.copy(isLoadingMore = false))
                }
                _effects.tryEmit(HomepageEffect.ShowSnackbar("加载更多失败: ${e.message}"))
            }
        }
    }

    /** 切换排行榜 Tab 时按需加载当前选中分类的内容 */
    fun selectRankingTab(globalId: String, index: Int) {
        val prevState = _moduleContentStates.value[globalId] as? ModuleLoadState.RankingTabs ?: return
        _moduleContentStates.update { states ->
            val current = states[globalId] as? ModuleLoadState.RankingTabs ?: return@update states
            states + (globalId to current.copy(selectedIndex = index))
        }
        val tab = prevState.tabs.getOrNull(index) ?: return

        viewModelScope.launch {
            val module = gateway.getById(globalId) ?: return@launch
            val rssSource = appDb.rssSourceDao.getByKey(module.sourceUrl)
            val state = _moduleContentStates.value[globalId] as? ModuleLoadState.RankingTabs
                ?: return@launch
            val currentTab = state.tabs.getOrNull(index) ?: return@launch
            if (currentTab.books == null && currentTab.errorMessage == null) {
                val tabJobKey = "${globalId}_tab_$index"
                if (loadJobs[tabJobKey]?.isActive != true) {
                    loadRankingTab(
                        globalId, module.sourceUrl, rssSource, index,
                        currentTab.title, currentTab.exploreUrl ?: "", page = 1
                    )
                }
            }
        }
    }

    // ==================== 多分类 Tab 加载 ====================

    private fun loadRankingTab(
        moduleId: String,
        sourceUrl: String,
        rssSource: RssSource?,
        index: Int,
        title: String,
        url: String,
        page: Int = 1
    ) {
        val jobKey = "${moduleId}_tab_$index"
        loadJobs[jobKey]?.cancel()
        loadJobs[jobKey] = viewModelScope.launch {
            kotlin.runCatching {
                val books = if (rssSource != null) {
                    val (articles, _) = withContext(Dispatchers.IO) {
                        Rss.getArticlesAwait(
                            title.ifBlank { rssSource.sourceName }, url, rssSource, page = page
                        )
                    }
                    articles.map { article ->
                        SearchBook(
                            bookUrl = article.link,
                            origin = rssSource.sourceUrl,
                            originName = rssSource.sourceName,
                            name = article.title,
                            coverUrl = article.image,
                            intro = article.description?.let {
                                Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                            },
                            author = rssSource.sourceName,
                            latestChapterTitle = article.pubDate,
                        )
                    }
                } else {
                    exploreBooks(
                        sourceUrl = sourceUrl,
                        moduleUrl = url.ifBlank { null },
                        page = page
                    ).first
                }
                books.map { book ->
                    HomepageBookItemUi(
                        book = book,
                        shelfState = BookshelfMatcher.getState(
                            book.name, book.author, book.bookUrl
                        )
                    )
                }
            }.onSuccess { bookItems ->
                _moduleContentStates.update { states ->
                    val current = states[moduleId] as? ModuleLoadState.RankingTabs
                        ?: return@update states
                    val updatedTabs = current.tabs.toMutableList()
                    val oldTab = updatedTabs[index]
                    val existingUrls = oldTab.books?.map { it.book.bookUrl }?.toSet() ?: emptySet()
                    val deduped = bookItems.filter { it.book.bookUrl !in existingUrls }
                    val newBooks = if (oldTab.books != null) oldTab.books + deduped else bookItems
                    val hasMore = bookItems.isNotEmpty()
                    updatedTabs[index] = oldTab.copy(
                        books = newBooks,
                        page = page,
                        hasMore = hasMore,
                        isLoadingMore = false,
                        errorMessage = null
                    )
                    states + (moduleId to current.copy(tabs = updatedTabs))
                }
            }.onFailure { e ->
                _moduleContentStates.update { states ->
                    val current = states[moduleId] as? ModuleLoadState.RankingTabs
                        ?: return@update states
                    val updatedTabs = current.tabs.toMutableList()
                    updatedTabs[index] = updatedTabs[index].copy(
                        errorMessage = e.stackTraceStr,
                        isLoadingMore = false
                    )
                    states + (moduleId to current.copy(tabs = updatedTabs))
                }
            }
        }.also { it.invokeOnCompletion { loadJobs.remove(jobKey) } }
    }

    fun loadMoreRankingTab(globalId: String, tabIndex: Int) {
        val state = _moduleContentStates.value[globalId] as? ModuleLoadState.RankingTabs ?: return
        val tab = state.tabs.getOrNull(tabIndex) ?: return

        if (tab.isLoadingMore) return

        val nextPage = tab.page + 1
        val effectiveHasMore = if (!tab.hasMore && tab.books != null && tab.books.isNotEmpty()) {
            true
        } else {
            tab.hasMore
        }
        if (!effectiveHasMore) return

        _moduleContentStates.update { states ->
            val current = states[globalId] as? ModuleLoadState.RankingTabs ?: return@update states
            val updatedTabs = current.tabs.toMutableList()
            updatedTabs[tabIndex] = updatedTabs[tabIndex].copy(
                isLoadingMore = true,
                hasMore = true,
                errorMessage = null
            )
            states + (globalId to current.copy(tabs = updatedTabs))
        }

        viewModelScope.launch {
            val module = gateway.getById(globalId) ?: return@launch
            val rssSource = appDb.rssSourceDao.getByKey(module.sourceUrl)
            loadRankingTab(
                moduleId = globalId,
                sourceUrl = module.sourceUrl,
                rssSource = rssSource,
                index = tabIndex,
                title = tab.title,
                url = tab.exploreUrl ?: "",
                page = nextPage
            )
        }
    }

    /**
     * 刷新主页模块内容（重新加载已存在的模块数据，不自动从书源同步新模块）
     */
    fun onRefresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            loadJobs.values.forEach { it.cancel() }
            loadJobs.clear()
            val loadSetIds = shouldLoadSetIds()
            _refreshingModuleIds.value = uiState.value.modules
                .filter { loadSetIds?.let { ids -> it.customSetId != null && it.customSetId in ids } ?: true }
                .map { it.globalId }.toSet()
            _moduleContentStates.value = emptyMap()
        }
    }

    // ==================== 布局模式 ====================

    /** 设置主页布局模式（0: 混合列表, 1: 分源Tab） */
    fun setLayoutMode(mode: Int) {
        AppConfig.homepageLayoutMode = mode
        notifyConfigChanged()
    }

    /** 设置分源Tab 预加载模式（0: 仅当前集, 1: 当前集 + 相邻集） */
    fun setPreloadMode(mode: Int) {
        AppConfig.homepagePreload = mode
        notifyConfigChanged()
    }

    /**
     * 更新分源Tab 模式下当前选中的页索引与集 URL 列表（由 UI 在页面稳定后回传），
     * 用于限制仅加载当前 Tab（及开启预加载时的相邻 Tab）的模块。
     */
    fun updateCurrentTab(tabIndex: Int, setUrls: List<String>) {
        currentTabIndex = tabIndex
        _currentTabSetUrls.value = setUrls
    }

    /**
     * 计算分源Tab 模式下应当加载的集 ID 集合；混合列表模式返回 null（表示全部加载）。
     * 集 URL 与模块 customSetId 的对应：源集（src_/rss_）URL 即 ID，自定义集需去掉 custom:// 前缀。
     */
    private fun shouldLoadSetIds(): Set<String>? {
        if (AppConfig.homepageLayoutMode == 0) return null
        val urls = _currentTabSetUrls.value
        if (urls.isEmpty()) return emptySet()
        val preload = AppConfig.homepagePreload == 1
        val start = if (preload) (currentTabIndex - 1).coerceAtLeast(0) else currentTabIndex
        val end = if (preload) (currentTabIndex + 1).coerceAtMost(urls.lastIndex) else currentTabIndex
        return (start..end).mapNotNull { index ->
            urls.getOrNull(index)?.let { url ->
                if (isCustomSetUrl(url)) customSetIdFromUrl(url) else url
            }
        }.toSet()
    }

    fun retryModule(globalId: String) {
        _moduleContentStates.update { it + (globalId to ModuleLoadState.Loading) }
    }

    /** 确保书源对应的集存在（不存在则自动创建） */
    private suspend fun ensureSetForSource(sourceUrl: String, sourceName: String): String {
        val setId = "src_$sourceUrl"
        if (gateway.getCustomSetById(setId) == null) gateway.upsertCustomSet(
            CustomSetItem(id = setId, name = sourceName)
        )
        return setId
    }

    /** 确保订阅源对应的集存在 */
    private suspend fun ensureRssSetForSource(sourceUrl: String, sourceName: String): String {
        val setId = "rss_$sourceUrl"
        if (gateway.getCustomSetById(setId) == null) gateway.upsertCustomSet(
            CustomSetItem(id = setId, name = sourceName)
        )
        return setId
    }

    fun getCurrentBookShelfState(book: SearchBook): BookShelfState {
        return BookshelfMatcher.getState(
            name = book.name,
            author = book.author,
            bookUrl = book.bookUrl
        )
    }

    fun onAddToShelf(book: SearchBook) {
        execute {
            val b = book.toBook()
            b.removeType(BookType.notShelf)
            if (b.order == 0) b.order = appDb.bookDao.minOrder - 1
            appDb.bookDao.insert(b)
        }
    }

    fun onBookClick(book: SearchBook) {
        viewModelScope.launch {
            // RSS 订阅源文章不保存搜索历史（searchBooks 表对书源有外键约束语义）
            if (!appDb.rssSourceDao.has(book.origin)) {
                appDb.searchBookDao.insert(book)
            }
        }
    }

    // ==================== 管理方法 ====================

    fun toggleSet(setUrl: String, visible: Boolean) {
        val hidden = hiddenSetUrls.toMutableSet()
        if (visible) hidden.remove(setUrl) else hidden.add(setUrl)
        saveHiddenSetUrls(hidden)
        notifyConfigChanged()
    }

    fun syncSourceModules(sourceUrl: String, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            val fullSource = appDb.bookSourceDao.getBookSource(sourceUrl) ?: return@launch
            syncModulesFromSource(fullSource)
            notifyConfigChanged()
            onDone?.invoke()
        }
    }

    fun toggleModule(moduleId: String, enabled: Boolean) {
        viewModelScope.launch {
            gateway.setEnabled(moduleId, enabled)
            notifyConfigChanged()
        }
    }

    private fun notifyConfigChanged() {
        _configVersion.update { it + 1 }
    }

    /** 将已存在的模块加入指定集（或启用），不存在则按定义新建 */
    fun joinModule(sourceUrl: String, setId: String?, def: ModuleDef) {
        viewModelScope.launch {
            val effectiveSetId = setId ?: run {
                val source = _bookSourcesCache.value[sourceUrl]
                ensureSetForSource(sourceUrl, source?.name ?: sourceUrl)
            }
            val globalId = ModuleDef.globalIdOf(sourceUrl, def.key, effectiveSetId)
            val existing = gateway.getById(globalId)
            if (existing != null) {
                gateway.setEnabled(globalId, true)
                gateway.setCustomSetId(globalId, effectiveSetId)
            } else {
                gateway.upsertAll(listOf(
                    ModuleItem(
                        id = globalId,
                        sourceUrl = sourceUrl,
                        moduleKey = def.key,
                        type = def.type,
                        title = def.title,
                        args = def.args,
                        url = def.url,
                        isEnabled = true,
                        customSetId = effectiveSetId,
                        sortOrder = allModulesCache.value.count { it.customSetId == effectiveSetId },
                        isUserCreated = true,
                        layoutConfig = def.layoutConfig,
                        syncedAt = System.currentTimeMillis()
                    )
                ))
            }
            notifyConfigChanged()
        }
    }

    /** 手动添加自定义模块；key 空白时按时间戳生成 */
    fun addCustomModule(sourceUrl: String, setId: String?, def: ModuleDef) {
        viewModelScope.launch {
            val effectiveSetId = setId ?: ensureSetForSource(
                sourceUrl, _bookSourcesCache.value[sourceUrl]?.name ?: sourceUrl
            )
            val key = def.key.ifBlank { "custom_${System.currentTimeMillis()}" }
            val globalId = ModuleDef.globalIdOf(sourceUrl, key, effectiveSetId)
            gateway.upsertAll(listOf(
                ModuleItem(
                    id = globalId,
                    sourceUrl = sourceUrl,
                    moduleKey = key,
                    type = def.type,
                    title = def.title,
                    args = def.args,
                    url = def.url,
                    isEnabled = true,
                    customSetId = effectiveSetId,
                    sortOrder = allModulesCache.value.count { it.customSetId == effectiveSetId },
                    isUserCreated = true,
                    layoutConfig = def.layoutConfig,
                    syncedAt = System.currentTimeMillis()
                )
            ))
            notifyConfigChanged()
        }
    }

    /** 获取书源发现分类（支持 @js:/<js> 动态分类） */
    suspend fun getExploreKinds(sourceUrl: String): List<ExploreKind> {
        return runCatching {
            val source = appDb.bookSourceDao.getBookSource(sourceUrl) ?: return emptyList()
            withContext(Dispatchers.IO) { source.exploreKinds() }
        }.getOrDefault(emptyList())
    }

    /** 获取订阅源分类列表 */
    suspend fun getRssKinds(sourceUrl: String): List<Pair<String, String>> {
        return runCatching {
            val rssSource = appDb.rssSourceDao.getByKey(sourceUrl) ?: return emptyList()
            withContext(Dispatchers.IO) { rssSource.sortUrls() }
        }.getOrDefault(emptyList()).ifEmpty { listOf("" to sourceUrl) }
    }

    /** 为订阅源添加自定义模块 */
    fun addRssCustomModule(sourceUrl: String, setId: String?, def: ModuleDef, sourceName: String) {
        viewModelScope.launch {
            val effectiveSetId = setId ?: ensureRssSetForSource(sourceUrl, sourceName)
            val key = def.key.ifBlank { "rss_${System.currentTimeMillis()}" }
            val globalId = ModuleDef.globalIdOf(sourceUrl, key, effectiveSetId)
            gateway.upsertAll(listOf(
                ModuleItem(
                    id = globalId,
                    sourceUrl = sourceUrl,
                    moduleKey = key,
                    type = def.type,
                    title = def.title,
                    args = def.args,
                    url = def.url,
                    isEnabled = true,
                    customSetId = effectiveSetId,
                    sortOrder = allModulesCache.value.count { it.customSetId == effectiveSetId },
                    isUserCreated = true,
                    layoutConfig = def.layoutConfig,
                    syncedAt = System.currentTimeMillis()
                )
            ))
            notifyConfigChanged()
        }
    }

    /**
     * 编辑模块：只更新 type/url/args/layoutConfig；无限流模块每集仅允许一个；
     * 标题与原定义不同时写入 customTitle 以保留用户改名。
     */
    fun updateModule(globalId: String, def: ModuleDef) {
        viewModelScope.launch {
            val existing = gateway.getById(globalId) ?: return@launch
            if (isInfinite(def.type) && !isInfinite(existing.type)) {
                val siblings = allModulesCache.value.filter {
                    it.customSetId == existing.customSetId && it.id != existing.id
                }
                if (siblings.any { isInfinite(it.type) }) {
                    _effects.tryEmit(HomepageEffect.ShowSnackbar("每个集只能有一个无限流模块"))
                    return@launch
                }
            }
            val newCustomTitle = if (def.title != existing.title) {
                if (def.title.isBlank()) null else def.title
            } else {
                existing.customTitle
            }
            gateway.upsertAll(listOf(
                existing.copy(
                    type = def.type,
                    url = def.url,
                    args = def.args,
                    layoutConfig = def.layoutConfig,
                    customTitle = newCustomTitle,
                )
            ))
            notifyConfigChanged()
        }
    }

    /**
     * 删除模块：源集模块连带删除所有自定义集中的副本，自定义集副本只删自身。
     */
    fun deleteModule(globalId: String) {
        viewModelScope.launch {
            val module = gateway.getById(globalId) ?: return@launch
            val isSourceSet = module.customSetId?.startsWith("src_") == true
                    || module.customSetId?.startsWith("rss_") == true
            if (isSourceSet) {
                gateway.deleteBySourceAndKey(module.sourceUrl, module.moduleKey)
            } else {
                gateway.delete(globalId)
            }
            _moduleContentStates.update { states ->
                states.filterKeys { key ->
                    key != globalId
                }
            }
            notifyConfigChanged()
        }
    }

    fun reorderModules(orderedIds: List<String>) {
        viewModelScope.launch {
            gateway.batchSetSortOrders(
                orderedIds.mapIndexed { index, id -> id to index }.toMap()
            )
            notifyConfigChanged()
        }
    }

    fun reorderCustomSets(orderedUrls: List<String>) {
        viewModelScope.launch {
            val orders = orderedUrls.mapNotNull { url ->
                if (isCustomSetUrl(url)) customSetIdFromUrl(url) else null
            }.mapIndexed { index, id -> id to index }
            gateway.batchSetCustomSetSortOrders(orders.toMap())
            notifyConfigChanged()
        }
    }

    fun setCustomSetTitle(moduleId: String, title: String?) {
        viewModelScope.launch {
            gateway.setCustomSetTitle(moduleId, title)
            notifyConfigChanged()
        }
    }

    fun createCustomSet(name: String) {
        viewModelScope.launch {
            gateway.createCustomSet(name)
            notifyConfigChanged()
        }
    }

    fun renameCustomSet(id: String, name: String) {
        viewModelScope.launch {
            gateway.renameCustomSet(id, name)
            notifyConfigChanged()
        }
    }

    /**
     * 删除集：源集（src_/rss_）连集内模块（含各集副本）一并删除；
     * 自定义集只删除属于该集的模块。
     */
    fun deleteCustomSet(id: String) {
        viewModelScope.launch {
            val isSourceSet = id.startsWith("src_") || id.startsWith("rss_")
            if (isSourceSet) {
                val sourceUrl = id.removePrefix("src_").removePrefix("rss_")
                val modules = allModulesCache.value.filter { it.sourceUrl == sourceUrl }
                val keys = modules.map { it.moduleKey }.distinct()
                for (key in keys) {
                    gateway.deleteBySourceAndKey(sourceUrl, key)
                }
                _moduleContentStates.update { states ->
                    states.filterKeys { key ->
                        modules.none { it.id == key }
                    }
                }
                gateway.deleteCustomSet(id)
            } else {
                gateway.deleteCustomSet(id)
            }
            notifyConfigChanged()
        }
    }

    /**
     * 把模块分配到自定义集：目标集已有该模块则仅启用，否则创建副本；
     * 移除（customSetId 为 null）时，源集模块禁用，自定义集副本删除。
     */
    fun assignModuleToCustomSet(moduleId: String, customSetId: String?) {
        viewModelScope.launch {
            val existing = gateway.getById(moduleId) ?: return@launch
            if (customSetId == null) {
                val isSourceSet = existing.customSetId?.startsWith("src_") == true
                        || existing.customSetId?.startsWith("rss_") == true
                if (isSourceSet) {
                    gateway.setEnabled(moduleId, false)
                } else {
                    gateway.delete(moduleId)
                }
            } else {
                val newId = ModuleDef.globalIdOf(
                    existing.sourceUrl, existing.moduleKey, customSetId
                )
                val target = gateway.getById(newId)
                if (target != null) {
                    gateway.setEnabled(newId, true)
                } else {
                    gateway.upsertAll(listOf(
                        existing.copy(
                            id = newId,
                            customSetId = customSetId,
                            isEnabled = true,
                        )
                    ))
                }
            }
            notifyConfigChanged()
        }
    }

    // ==================== args 解析 ====================

    /** 解析多分类 tab 的 args：[{t:标题, u:URL}]；1~N 个都按 tab 模式（单分类即单 tab） */
    private fun parseRankingCategories(args: String?): List<Pair<String, String>>? {
        if (args.isNullOrBlank()) return null
        return runCatching {
            GSON.fromJsonArray<Map<String, String>>(args).getOrDefault(emptyList())
                .mapNotNull { item ->
                    val t = item["t"] ?: return@mapNotNull null
                    t to (item["u"] ?: "")
                }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 解析按钮组 args 的分类标题，兼容新 [{t,u}] 与旧 ["title"] 两种格式 */
    private fun parseKindTitlesFromArgs(args: String?): List<String>? {
        if (args.isNullOrBlank()) return null
        return runCatching {
            val pairs = GSON.fromJsonArray<Map<String, String>>(args).getOrDefault(emptyList())
            if (pairs.isNotEmpty() && pairs.all { it.containsKey("t") }) {
                pairs.mapNotNull { it["t"] }
            } else {
                GSON.fromJsonArray<String>(args).getOrDefault(emptyList())
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}

/** 书源轻量缓存 DTO：避免大量书源时持有完整 BookSource */
data class BookSourcePartLite(
    val url: String,
    val name: String,
    val group: String?,
    val exploreUrl: String?,
)
