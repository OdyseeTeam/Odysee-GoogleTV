package com.odysee.androidtv.feature.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.odysee.androidtv.core.auth.AuthRepository
import com.odysee.androidtv.core.auth.AuthSessionStore
import com.odysee.androidtv.core.network.OdyseeApiClient
import com.odysee.androidtv.feature.discover.data.DiscoverRepository
import com.odysee.androidtv.feature.discover.model.Category
import com.odysee.androidtv.feature.discover.model.VideoClaim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String = "",
    val contentTitle: String = "",
    val videos: List<VideoClaim> = emptyList(),
    val loadingCategories: Boolean = false,
    val loadingVideos: Boolean = false,
    val inSearchView: Boolean = false,
    val inChannelView: Boolean = false,
    val searchQuery: String = "",
    val activeChannelId: String = "",
    val activeChannelName: String = "",
    val activeChannelAvatarUrl: String = "",
    val channelFollowed: Boolean = false,
    val channelFollowBusy: Boolean = false,
    val errorMessage: String = "",
)

class DiscoverViewModel(application: Application) : AndroidViewModel(application) {
    private val apiClient = OdyseeApiClient()
    private val authRepository = AuthRepository(
        api = apiClient,
        store = AuthSessionStore(application.applicationContext),
    )
    private val discoverRepository = DiscoverRepository(
        api = apiClient,
        authRepository = authRepository,
    )

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private data class CachedCategoryVideos(
        val cachedAtMs: Long,
        val videos: List<VideoClaim>,
    )

    private enum class FeedMode {
        CATEGORY,
        SEARCH,
        CHANNEL,
    }

    private var signedIn = false
    private var watchLaterAvailable = false
    private var baseCategories: List<Category> = emptyList()
    private var loadVideosJob: Job? = null
    private val categoryCache = mutableMapOf<String, CachedCategoryVideos>()
    private var paginationMode = FeedMode.CATEGORY
    private var paginationCategoryId = ""
    private var paginationSearchQuery = ""
    private var paginationChannelId = ""
    private var paginationCacheRootId = ""
    private var paginationPageSize = CATEGORY_PAGE_SIZE
    private var paginationNextPage = 2
    private var paginationCanLoadMore = false
    private val paginationSeenClaimIds = mutableSetOf<String>()
    private var primaryLoadRequestId = 0L
    private var followStateRequestId = 0L

    init {
        loadCategories()
    }

    fun onAuthStateChanged(isSignedIn: Boolean) {
        if (signedIn == isSignedIn) return
        signedIn = isSignedIn
        clearCategoryCache()
        followStateRequestId += 1L

        if (!signedIn) {
            watchLaterAvailable = false
            _uiState.update {
                it.copy(
                    channelFollowed = false,
                    channelFollowBusy = false,
                )
            }
            syncCategoriesAndSelection()
            return
        }

        syncCategoriesAndSelection(
            preferredCategoryId = FOLLOWING_CATEGORY.id,
            forcePreferred = true,
        )
        viewModelScope.launch {
            val hasWatchLater = runCatching { discoverRepository.hasWatchLaterVideos() }.getOrDefault(false)
            if (!signedIn) return@launch
            if (watchLaterAvailable == hasWatchLater) return@launch
            watchLaterAvailable = hasWatchLater
            syncCategoriesAndSelection(preferredCategoryId = FOLLOWING_CATEGORY.id)
        }
    }

    private fun syncCategoriesAndSelection(
        preferredCategoryId: String? = null,
        forcePreferred: Boolean = false,
    ) {
        val currentSelection = _uiState.value.selectedCategoryId
        val updatedCategories = buildVisibleCategories(baseCategories)
        val preferredSelection = preferredCategoryId
            ?.takeIf { preferred -> updatedCategories.any { it.id == preferred } }
        val nextSelection = when {
            updatedCategories.isEmpty() -> ""
            forcePreferred && preferredSelection != null -> preferredSelection
            currentSelection.isNotBlank() && updatedCategories.any { it.id == currentSelection } -> currentSelection
            preferredSelection != null -> preferredSelection
            else -> updatedCategories.first().id
        }

        _uiState.update {
            it.copy(categories = updatedCategories, selectedCategoryId = nextSelection)
        }

        if (nextSelection.isNotBlank()) {
            selectCategory(nextSelection)
        }
    }

    fun refreshAll() {
        clearCategoryCache()
        loadCategories()
    }

    fun retryCurrentFeed() {
        val state = _uiState.value
        if (state.loadingCategories || state.loadingVideos) {
            return
        }

        when {
            state.categories.isEmpty() -> refreshAll()
            state.inSearchView && state.searchQuery.isNotBlank() -> search(state.searchQuery)
            state.inChannelView && state.activeChannelId.isNotBlank() -> {
                openChannelFeed(
                    channelIdRaw = state.activeChannelId,
                    channelNameRaw = state.activeChannelName,
                    channelAvatarUrl = state.activeChannelAvatarUrl,
                )
            }
            state.selectedCategoryId.isNotBlank() -> selectCategory(state.selectedCategoryId)
            else -> refreshAll()
        }
    }

    fun refreshCategory(categoryIdRaw: String) {
        val categoryId = categoryIdRaw.trim()
        if (categoryId.isBlank()) {
            return
        }
        val state = _uiState.value
        if (state.loadingCategories || state.loadingVideos) {
            return
        }
        if (state.categories.none { it.id == categoryId }) {
            return
        }
        clearCategoryCacheFor(categoryId)
        selectCategory(categoryId)
    }

    fun selectCategory(categoryId: String) {
        val selectedCategory = _uiState.value.categories.firstOrNull { it.id == categoryId } ?: return
        val categoryChanged = _uiState.value.selectedCategoryId != categoryId
        val cacheRootId = selectedCategory.id
        val cacheKey = getCategoryCacheKey(cacheRootId, page = 1)
        val cachedVideos = getCachedCategoryVideos(cacheKey)

        if (cachedVideos != null) {
            configurePagination(
                mode = FeedMode.CATEGORY,
                categoryId = selectedCategory.id,
                cacheRootId = cacheRootId,
                pageSize = CATEGORY_PAGE_SIZE,
                seedVideos = cachedVideos,
                canLoadMore = cachedVideos.size >= CATEGORY_PAGE_SIZE && cachedVideos.size % CATEGORY_PAGE_SIZE == 0,
            )
            _uiState.update {
                it.copy(
                    selectedCategoryId = categoryId,
                    contentTitle = selectedCategory.title,
                    loadingVideos = false,
                    inSearchView = false,
                    inChannelView = false,
                    searchQuery = "",
                    activeChannelId = "",
                    activeChannelName = "",
                    activeChannelAvatarUrl = "",
                    channelFollowed = false,
                    channelFollowBusy = false,
                    errorMessage = "",
                    videos = cachedVideos,
                )
            }
            return
        }

        configurePagination(
            mode = FeedMode.CATEGORY,
            categoryId = selectedCategory.id,
            cacheRootId = cacheRootId,
            pageSize = CATEGORY_PAGE_SIZE,
            seedVideos = emptyList(),
            canLoadMore = false,
        )
        _uiState.update {
            it.copy(
                selectedCategoryId = categoryId,
                contentTitle = selectedCategory.title,
                loadingVideos = true,
                inSearchView = false,
                inChannelView = false,
                searchQuery = "",
                activeChannelId = "",
                activeChannelName = "",
                activeChannelAvatarUrl = "",
                channelFollowed = false,
                channelFollowBusy = false,
                errorMessage = "",
                videos = if (categoryChanged) emptyList() else it.videos,
            )
        }

        val requestId = nextPrimaryLoadRequestId()
        loadVideosJob = viewModelScope.launch {
            runCatching {
                discoverRepository.loadCategoryVideos(selectedCategory, page = 1, pageSize = CATEGORY_PAGE_SIZE)
            }.onSuccess { videos ->
                if (requestId != primaryLoadRequestId) {
                    return@onSuccess
                }
                setCachedCategoryVideos(cacheKey, videos)
                configurePagination(
                    mode = FeedMode.CATEGORY,
                    categoryId = selectedCategory.id,
                    cacheRootId = cacheRootId,
                    pageSize = CATEGORY_PAGE_SIZE,
                    seedVideos = videos,
                    canLoadMore = videos.size >= CATEGORY_PAGE_SIZE,
                )
                _uiState.update {
                    it.copy(
                        videos = videos,
                        loadingVideos = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@onFailure
                }
                if (requestId != primaryLoadRequestId) {
                    return@onFailure
                }
                _uiState.update {
                    it.copy(
                        videos = emptyList(),
                        loadingVideos = false,
                        errorMessage = error.message ?: "Failed to load videos",
                    )
                }
            }
        }
    }

    fun search(queryRaw: String) {
        val query = queryRaw.trim()
        if (query.isBlank()) {
            return
        }
        val cacheRootId = "search:${query.lowercase()}"
        val cacheKey = getCategoryCacheKey(cacheRootId, page = 1)
        val cachedVideos = getCachedCategoryVideos(cacheKey)
        if (cachedVideos != null) {
            configurePagination(
                mode = FeedMode.SEARCH,
                searchQuery = query,
                cacheRootId = cacheRootId,
                pageSize = SEARCH_PAGE_SIZE,
                seedVideos = cachedVideos,
                canLoadMore = cachedVideos.size >= SEARCH_PAGE_SIZE && cachedVideos.size % SEARCH_PAGE_SIZE == 0,
            )
            _uiState.update {
                it.copy(
                    contentTitle = "Search: \"$query\"",
                    loadingVideos = false,
                    inSearchView = true,
                    inChannelView = false,
                    searchQuery = query,
                    activeChannelId = "",
                    activeChannelName = "",
                    activeChannelAvatarUrl = "",
                    channelFollowed = false,
                    channelFollowBusy = false,
                    errorMessage = "",
                    videos = cachedVideos,
                )
            }
            return
        }

        configurePagination(
            mode = FeedMode.SEARCH,
            searchQuery = query,
            cacheRootId = cacheRootId,
            pageSize = SEARCH_PAGE_SIZE,
            seedVideos = emptyList(),
            canLoadMore = false,
        )
        _uiState.update {
            it.copy(
                contentTitle = "Search: \"$query\"",
                loadingVideos = true,
                inSearchView = true,
                inChannelView = false,
                searchQuery = query,
                activeChannelId = "",
                activeChannelName = "",
                activeChannelAvatarUrl = "",
                channelFollowed = false,
                channelFollowBusy = false,
                errorMessage = "",
                videos = emptyList(),
            )
        }

        val requestId = nextPrimaryLoadRequestId()
        loadVideosJob = viewModelScope.launch {
            runCatching {
                discoverRepository.searchVideos(query, page = 1, pageSize = SEARCH_PAGE_SIZE)
            }.onSuccess { videos ->
                if (requestId != primaryLoadRequestId) {
                    return@onSuccess
                }
                setCachedCategoryVideos(cacheKey, videos)
                configurePagination(
                    mode = FeedMode.SEARCH,
                    searchQuery = query,
                    cacheRootId = cacheRootId,
                    pageSize = SEARCH_PAGE_SIZE,
                    seedVideos = videos,
                    canLoadMore = videos.size >= SEARCH_PAGE_SIZE,
                )
                _uiState.update {
                    it.copy(
                        videos = videos,
                        loadingVideos = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@onFailure
                }
                if (requestId != primaryLoadRequestId) {
                    return@onFailure
                }
                _uiState.update {
                    it.copy(
                        videos = emptyList(),
                        loadingVideos = false,
                        errorMessage = error.message ?: "Search failed",
                    )
                }
            }
        }
    }

    fun openChannelFeed(
        channelIdRaw: String,
        channelNameRaw: String,
        channelAvatarUrl: String = "",
    ) {
        val channelId = channelIdRaw.trim()
        if (channelId.isBlank()) {
            return
        }
        val channelName = channelNameRaw.trim().ifBlank { "Channel" }
        val cacheRootId = "channel:${channelId.lowercase()}"
        val cacheKey = getCategoryCacheKey(cacheRootId, page = 1)
        val cachedVideos = getCachedCategoryVideos(cacheKey)
        if (cachedVideos != null) {
            configurePagination(
                mode = FeedMode.CHANNEL,
                channelId = channelId,
                cacheRootId = cacheRootId,
                pageSize = CHANNEL_PAGE_SIZE,
                seedVideos = cachedVideos,
                canLoadMore = cachedVideos.size >= CHANNEL_PAGE_SIZE && cachedVideos.size % CHANNEL_PAGE_SIZE == 0,
            )
            _uiState.update {
                it.copy(
                    contentTitle = channelName,
                    loadingVideos = false,
                    inSearchView = false,
                    inChannelView = true,
                    searchQuery = "",
                    activeChannelId = channelId,
                    activeChannelName = channelName,
                    activeChannelAvatarUrl = channelAvatarUrl,
                    channelFollowed = false,
                    channelFollowBusy = signedIn,
                    errorMessage = "",
                    videos = cachedVideos,
                )
            }
            refreshChannelFollowState(channelId)
            return
        }

        configurePagination(
            mode = FeedMode.CHANNEL,
            channelId = channelId,
            cacheRootId = cacheRootId,
            pageSize = CHANNEL_PAGE_SIZE,
            seedVideos = emptyList(),
            canLoadMore = false,
        )
        _uiState.update {
            it.copy(
                contentTitle = channelName,
                loadingVideos = true,
                inSearchView = false,
                inChannelView = true,
                searchQuery = "",
                activeChannelId = channelId,
                activeChannelName = channelName,
                activeChannelAvatarUrl = channelAvatarUrl,
                channelFollowed = false,
                channelFollowBusy = signedIn,
                errorMessage = "",
                videos = emptyList(),
            )
        }
        refreshChannelFollowState(channelId)

        val requestId = nextPrimaryLoadRequestId()
        loadVideosJob = viewModelScope.launch {
            runCatching {
                discoverRepository.loadChannelVideos(channelId, page = 1, pageSize = CHANNEL_PAGE_SIZE)
            }.onSuccess { videos ->
                if (requestId != primaryLoadRequestId) {
                    return@onSuccess
                }
                setCachedCategoryVideos(cacheKey, videos)
                configurePagination(
                    mode = FeedMode.CHANNEL,
                    channelId = channelId,
                    cacheRootId = cacheRootId,
                    pageSize = CHANNEL_PAGE_SIZE,
                    seedVideos = videos,
                    canLoadMore = videos.size >= CHANNEL_PAGE_SIZE,
                )
                _uiState.update {
                    it.copy(
                        videos = videos,
                        loadingVideos = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@onFailure
                }
                if (requestId != primaryLoadRequestId) {
                    return@onFailure
                }
                _uiState.update {
                    it.copy(
                        videos = emptyList(),
                        loadingVideos = false,
                        errorMessage = error.message ?: "Failed to load channel videos",
                    )
                }
            }
        }
    }

    fun toggleActiveChannelFollow() {
        val state = _uiState.value
        if (!signedIn || !state.inChannelView || state.activeChannelId.isBlank() || state.channelFollowBusy) {
            return
        }
        val channelId = state.activeChannelId
        val channelName = state.activeChannelName.ifBlank { "Channel" }
        val wasFollowed = state.channelFollowed
        val requestId = nextFollowStateRequestId()

        _uiState.update { current ->
            if (!current.inChannelView || !sameChannelId(current.activeChannelId, channelId)) {
                return@update current
            }
            current.copy(channelFollowBusy = true)
        }

        viewModelScope.launch {
            val result = runCatching {
                if (wasFollowed) {
                    discoverRepository.unfollowChannel(channelIdRaw = channelId, channelNameRaw = channelName)
                } else {
                    discoverRepository.followChannel(channelIdRaw = channelId, channelNameRaw = channelName)
                }
            }

            if (result.isSuccess) {
                invalidateFollowingCache()
            }

            _uiState.update { current ->
                if (
                    requestId != followStateRequestId ||
                    !current.inChannelView ||
                    !sameChannelId(current.activeChannelId, channelId)
                ) {
                    return@update current
                }
                if (result.isSuccess) {
                    current.copy(
                        channelFollowed = !wasFollowed,
                        channelFollowBusy = false,
                    )
                } else {
                    current.copy(channelFollowBusy = false)
                }
            }

            if (result.isFailure) {
                val expectedState = !wasFollowed
                val serverFollowing = runCatching {
                    discoverRepository.isChannelFollowed(channelId)
                }.getOrNull()
                if (serverFollowing != null && serverFollowing == expectedState) {
                    invalidateFollowingCache()
                    _uiState.update { current ->
                        if (
                            requestId != followStateRequestId ||
                            !current.inChannelView ||
                            !sameChannelId(current.activeChannelId, channelId)
                        ) {
                            return@update current
                        }
                        current.copy(
                            channelFollowed = serverFollowing,
                            channelFollowBusy = false,
                        )
                    }
                }
            }
            refreshChannelFollowState(channelId)
        }
    }

    fun openSignedInDefaultChannel(
        channelId: String,
        channelName: String,
        avatarUrl: String = "",
    ) {
        if (channelId.isBlank()) {
            return
        }
        openChannelFeed(
            channelIdRaw = channelId,
            channelNameRaw = channelName.ifBlank { "My Channel" },
            channelAvatarUrl = avatarUrl,
        )
    }

    fun loadMoreVideos() {
        val currentState = _uiState.value
        if (currentState.loadingVideos || !paginationCanLoadMore || paginationCacheRootId.isBlank()) {
            return
        }

        val requestMode = paginationMode
        val requestCategoryId = paginationCategoryId
        val requestSearchQuery = paginationSearchQuery
        val requestChannelId = paginationChannelId
        val requestCacheRootId = paginationCacheRootId
        val requestPageSize = paginationPageSize
        val requestPage = paginationNextPage

        _uiState.update {
            it.copy(
                loadingVideos = true,
                errorMessage = "",
            )
        }

        loadVideosJob?.cancel()
        loadVideosJob = viewModelScope.launch {
            runCatching {
                when (requestMode) {
                    FeedMode.CATEGORY -> {
                        val category = _uiState.value.categories.firstOrNull { it.id == requestCategoryId }
                            ?: return@runCatching emptyList()
                        discoverRepository.loadCategoryVideos(
                            category = category,
                            page = requestPage,
                            pageSize = requestPageSize,
                        )
                    }
                    FeedMode.SEARCH -> discoverRepository.searchVideos(
                        queryRaw = requestSearchQuery,
                        page = requestPage,
                        pageSize = requestPageSize,
                    )
                    FeedMode.CHANNEL -> discoverRepository.loadChannelVideos(
                        channelIdRaw = requestChannelId,
                        page = requestPage,
                        pageSize = requestPageSize,
                    )
                }
            }.onSuccess { pageVideos ->
                if (requestCacheRootId != paginationCacheRootId || requestMode != paginationMode) {
                    return@onSuccess
                }

                val currentVideos = _uiState.value.videos
                val mergedVideos = appendUniqueVideos(currentVideos, pageVideos)
                val addedCount = mergedVideos.size - currentVideos.size

                setCachedCategoryVideos(getCategoryCacheKey(requestCacheRootId, requestPage), pageVideos)
                setCachedCategoryVideos(getCategoryCacheKey(requestCacheRootId, page = 1), mergedVideos)

                val canLoadAnotherPage = pageVideos.size >= requestPageSize && addedCount > 0
                paginationCanLoadMore = canLoadAnotherPage
                if (canLoadAnotherPage) {
                    paginationNextPage = requestPage + 1
                }

                _uiState.update {
                    it.copy(
                        videos = mergedVideos,
                        loadingVideos = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@onFailure
                }
                _uiState.update {
                    it.copy(
                        loadingVideos = false,
                        errorMessage = error.message ?: "Failed to load more videos",
                    )
                }
            }
        }
    }

    private fun loadCategories() {
        _uiState.update { it.copy(loadingCategories = true, errorMessage = "") }

        viewModelScope.launch {
            runCatching {
                discoverRepository.loadCategories()
            }.onSuccess { categories ->
                baseCategories = categories
                val visibleCategories = buildVisibleCategories(categories)
                val selected = visibleCategories.firstOrNull()?.id.orEmpty()

                _uiState.update {
                    it.copy(
                        categories = visibleCategories,
                        selectedCategoryId = selected,
                        contentTitle = visibleCategories.firstOrNull()?.title.orEmpty(),
                        loadingCategories = false,
                        videos = emptyList(),
                    )
                }

                if (selected.isNotBlank()) {
                    selectCategory(selected)
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@onFailure
                }
                _uiState.update {
                    it.copy(
                        loadingCategories = false,
                        loadingVideos = false,
                        errorMessage = error.message ?: "Failed to load categories",
                    )
                }
            }
        }
    }

    private fun configurePagination(
        mode: FeedMode,
        categoryId: String = "",
        searchQuery: String = "",
        channelId: String = "",
        cacheRootId: String,
        pageSize: Int,
        seedVideos: List<VideoClaim>,
        canLoadMore: Boolean,
    ) {
        val safePageSize = pageSize.coerceAtLeast(1)
        val loadedPages = if (seedVideos.isEmpty()) 1 else ((seedVideos.size - 1) / safePageSize) + 1

        paginationMode = mode
        paginationCategoryId = categoryId
        paginationSearchQuery = searchQuery
        paginationChannelId = channelId
        paginationCacheRootId = cacheRootId
        paginationPageSize = safePageSize
        paginationNextPage = loadedPages + 1
        paginationCanLoadMore = canLoadMore
        paginationSeenClaimIds.clear()
        seedVideos.forEach { video ->
            val normalizedClaimId = video.claimId.trim().lowercase()
            if (normalizedClaimId.isNotBlank()) {
                paginationSeenClaimIds += normalizedClaimId
            }
        }
    }

    private fun appendUniqueVideos(
        existingVideos: List<VideoClaim>,
        incomingVideos: List<VideoClaim>,
    ): List<VideoClaim> {
        if (incomingVideos.isEmpty()) {
            return existingVideos
        }

        val out = existingVideos.toMutableList()
        incomingVideos.forEach { video ->
            val normalizedClaimId = video.claimId.trim().lowercase()
            if (normalizedClaimId.isBlank()) {
                val duplicate = out.any {
                    it.canonicalUrl.isNotBlank() &&
                        it.canonicalUrl == video.canonicalUrl &&
                        it.title == video.title
                }
                if (!duplicate) {
                    out += video
                }
                return@forEach
            }

            if (paginationSeenClaimIds.add(normalizedClaimId)) {
                out += video
            }
        }
        return out
    }

    private fun nextPrimaryLoadRequestId(): Long {
        primaryLoadRequestId += 1L
        return primaryLoadRequestId
    }

    private fun getCategoryCacheKey(categoryId: String, page: Int): String = "$categoryId::$page"

    private fun getCachedCategoryVideos(cacheKey: String): List<VideoClaim>? {
        val entry = categoryCache[cacheKey] ?: return null
        if ((System.currentTimeMillis() - entry.cachedAtMs) > CATEGORY_CACHE_TTL_MS) {
            categoryCache.remove(cacheKey)
            return null
        }
        return entry.videos
    }

    private fun setCachedCategoryVideos(cacheKey: String, videos: List<VideoClaim>) {
        categoryCache[cacheKey] = CachedCategoryVideos(
            cachedAtMs = System.currentTimeMillis(),
            videos = videos,
        )
    }

    private fun clearCategoryCacheFor(cacheRootIdRaw: String) {
        val cacheRootId = cacheRootIdRaw.trim()
        if (cacheRootId.isBlank()) {
            return
        }
        val prefix = "$cacheRootId::"
        categoryCache.keys
            .filter { it.startsWith(prefix) }
            .toList()
            .forEach(categoryCache::remove)
    }

    private fun clearCategoryCache() {
        categoryCache.clear()
        paginationCanLoadMore = false
        paginationNextPage = 2
        paginationSeenClaimIds.clear()
    }

    private fun invalidateFollowingCache() {
        val followingPrefix = "${FOLLOWING_CATEGORY.id}::"
        categoryCache.keys
            .filter { it.startsWith(followingPrefix) }
            .toList()
            .forEach(categoryCache::remove)
    }

    private fun refreshChannelFollowState(channelIdRaw: String) {
        val channelId = channelIdRaw.trim()
        if (!signedIn || channelId.isBlank()) {
            _uiState.update {
                it.copy(
                    channelFollowed = false,
                    channelFollowBusy = false,
                )
            }
            return
        }

        val requestId = nextFollowStateRequestId()
        _uiState.update { state ->
            if (!state.inChannelView || !sameChannelId(state.activeChannelId, channelId)) {
                return@update state
            }
            state.copy(channelFollowBusy = true)
        }

        viewModelScope.launch {
            val isFollowed = runCatching {
                discoverRepository.isChannelFollowed(channelId)
            }.getOrNull()

            _uiState.update { state ->
                if (
                    requestId != followStateRequestId ||
                    !state.inChannelView ||
                    !sameChannelId(state.activeChannelId, channelId)
                ) {
                    return@update state
                }
                if (isFollowed == null) {
                    state.copy(channelFollowBusy = false)
                } else {
                    state.copy(
                        channelFollowed = isFollowed,
                        channelFollowBusy = false,
                    )
                }
            }
        }
    }

    private fun nextFollowStateRequestId(): Long {
        followStateRequestId += 1L
        return followStateRequestId
    }

    private fun sameChannelId(left: String, right: String): Boolean =
        left.trim().equals(right.trim(), ignoreCase = true)

    private fun buildVisibleCategories(base: List<Category>): List<Category> {
        val mutable = mutableListOf<Category>()
        if (signedIn) {
            mutable.add(FOLLOWING_CATEGORY)
            if (watchLaterAvailable) {
                mutable.add(WATCH_LATER_CATEGORY)
            }
        }
        base.forEach { category ->
            val id = category.id
            if (id == FOLLOWING_CATEGORY.id || id == WATCH_LATER_CATEGORY.id || id == "watch-later") {
                return@forEach
            }
            mutable.add(category)
        }
        return mutable
    }

    private companion object {
        const val CATEGORY_CACHE_TTL_MS = 5 * 60 * 1000L
        const val CATEGORY_PAGE_SIZE = 24
        const val SEARCH_PAGE_SIZE = 36
        const val CHANNEL_PAGE_SIZE = 36

        val FOLLOWING_CATEGORY = Category(
            id = "following",
            title = "Following",
            sortOrder = -100,
        )
        val WATCH_LATER_CATEGORY = Category(
            id = "watchlater",
            title = "Watch Later",
            sortOrder = -90,
        )
    }
}
