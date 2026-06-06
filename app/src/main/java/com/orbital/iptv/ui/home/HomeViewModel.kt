package com.orbital.iptv.ui.home

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.orbital.iptv.data.model.*
import com.orbital.iptv.data.repository.XtreamRepository
import com.orbital.iptv.utils.ContentCache
import com.orbital.iptv.utils.EpgCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val skyCategories: List<SkyCategoryGroup> = emptyList(),
    val xtreamCategories: List<LiveCategory> = emptyList(),
    val selectedSkyCategory: SkyCategory = SkyCategory.ENTERTAINMENT,
    val selectedXtreamCategory: LiveCategory? = null,
    val channels: List<LiveStream> = emptyList(),
    val useOriginalCategories: Boolean = false
)

data class SkyCategoryGroup(
    val skyCategory: SkyCategory,
    val xtreamCategoryIds: List<String>
)

class HomeViewModel(application: android.app.Application) : AndroidViewModel(application) {

    companion object {
        const val FAV_CATEGORY_ID = "__favourites__"
        val FAV_CATEGORY = LiveCategory(FAV_CATEGORY_ID, "FAVOURITES", 0)
    }

    private val repository = XtreamRepository()

    private val _uiState = MutableLiveData(HomeUiState())
    val uiState: LiveData<HomeUiState> = _uiState

    // Posts stream IDs when EPG arrives so the activity can refresh just that row.
    private val _epgUpdate = MutableLiveData<Int>()
    val epgUpdate: LiveData<Int> = _epgUpdate

    private var allStreams: List<LiveStream> = emptyList()
    private var allCategories: List<LiveCategory> = emptyList()
    private var credentials: Triple<String, String, String>? = null
    private var hiddenCategoryIds: Set<String> = emptySet()

    fun loadData(serverUrl: String, username: String, password: String) {
        credentials = Triple(serverUrl, username, password)
        _uiState.value = _uiState.value?.copy(isLoading = true, error = null, useOriginalCategories = true)

        viewModelScope.launch {
            // Load categories
            val catResult = repository.getLiveCategories(serverUrl, username, password)
            catResult.fold(
                onSuccess = { categories ->
                    allCategories = categories

                    // Load all streams
                    val streamsResult = repository.getLiveStreams(serverUrl, username, password)
                    streamsResult.fold(
                        onSuccess = { streams ->
                            allStreams = streams
                            viewModelScope.launch { ContentCache.saveLiveStreams(getApplication(), serverUrl, streams) }
                            _uiState.value = _uiState.value?.copy(
                                isLoading = false,
                                xtreamCategories = categories
                            )
                            categories.firstOrNull()?.let { selectXtreamCategory(it) }
                        },
                        onFailure = { error ->
                            _uiState.value = _uiState.value?.copy(
                                isLoading = false,
                                error = "Failed to load channels: ${error.message}"
                            )
                        }
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value?.copy(
                        isLoading = false,
                        error = "Failed to load categories: ${error.message}"
                    )
                }
            )
        }
    }

    private fun buildSkyCategoryGroups(categories: List<LiveCategory>, customMapping: Map<String, SkyCategory> = emptyMap(), hiddenIds: Set<String> = emptySet()): List<SkyCategoryGroup> {
        val skyCatMap = mutableMapOf<SkyCategory, MutableList<String>>()
        SkyCategory.values().forEach { skyCatMap[it] = mutableListOf() }

        categories.forEach { cat ->
            if (cat.categoryId in hiddenIds) return@forEach
            val skyCategory = customMapping[cat.categoryId] ?: mapCategoryToSky(cat.categoryName)
            skyCatMap[skyCategory]?.add(cat.categoryId)
        }

        return SkyCategory.values().map { sky ->
            SkyCategoryGroup(sky, skyCatMap[sky] ?: emptyList())
        }
    }

    fun selectCategory(skyCategory: SkyCategory) {
        val state = _uiState.value ?: return
        val group = state.skyCategories.find { it.skyCategory == skyCategory }

        val filtered = if (group == null || group.xtreamCategoryIds.isEmpty()) {
            val matchedIds = state.skyCategories
                .filter { it.skyCategory != SkyCategory.OTHER_CHANNELS }
                .flatMap { it.xtreamCategoryIds }
            allStreams.filter { it.categoryId !in matchedIds && it.categoryId !in hiddenCategoryIds }
        } else {
            allStreams.filter { it.categoryId in group.xtreamCategoryIds }
        }

        // Single state update — avoids two successive submitList calls
        _uiState.value = state.copy(selectedSkyCategory = skyCategory, channels = filtered)
        fetchEpgForCurrentList(filtered)
    }

    fun selectXtreamCategory(category: LiveCategory) {
        val filtered = allStreams.filter { it.categoryId == category.categoryId }
        _uiState.value = _uiState.value?.copy(selectedXtreamCategory = category, channels = filtered)
        fetchEpgForCurrentList(filtered)
    }

    fun selectFavouriteChannels(ids: Set<Int>) {
        val filtered = allStreams.filter { it.streamId in ids }
        _uiState.value = _uiState.value?.copy(selectedXtreamCategory = FAV_CATEGORY, channels = filtered)
        fetchEpgForCurrentList(filtered)
    }

    private fun fetchEpgForCurrentList(channels: List<LiveStream>) {
        val creds = credentials ?: return
        val app = getApplication<android.app.Application>()

        channels.forEach { stream ->
            if (stream.epgNow != null) return@forEach   // already populated this session
            viewModelScope.launch {
                // Cache hit (per-file 24 h TTL) — no API call needed
                val cached = EpgCache.get(app, stream.streamId)
                if (cached != null) {
                    val title = cached.currentEpgTitle()
                    if (!title.isNullOrBlank()) {
                        stream.epgNow = title
                        _epgUpdate.postValue(stream.streamId)
                    }
                    return@launch
                }
                // Not cached — fetch from API
                val result = repository.getShortEpg(creds.first, creds.second, creds.third, stream.streamId)
                result.onSuccess { epg ->
                    val listings = epg.listings ?: return@onSuccess
                    EpgCache.put(app, stream.streamId, listings)
                    val title = listings.currentEpgTitle()
                    if (!title.isNullOrBlank()) {
                        stream.epgNow = title
                        _epgUpdate.postValue(stream.streamId)
                    }
                }
            }
        }
    }

    private fun List<EpgListing>.currentEpgTitle(): String? {
        val nowSec = System.currentTimeMillis() / 1000
        val sorted = sortedBy { it.startTimestamp?.toLongOrNull() ?: 0L }
        val current = sorted.firstOrNull { l ->
            val start = l.startTimestamp?.toLongOrNull() ?: return@firstOrNull false
            val end   = l.stopTimestamp?.toLongOrNull()  ?: return@firstOrNull false
            nowSec in start..end
        } ?: sorted.firstOrNull()
        return current?.getDecodedTitle()?.takeIf { it.isNotBlank() }
    }

    fun buildStreamUrl(streamId: Int): String {
        val creds = credentials ?: return ""
        return repository.buildStreamUrl(creds.first, creds.second, creds.third, streamId)
    }

    fun getAllStreams(): List<LiveStream> = allStreams

    fun refreshServer() {
        val creds = credentials ?: return
        val app = getApplication<android.app.Application>()
        viewModelScope.launch {
            // Wipe all caches so everything is re-fetched fresh
            EpgCache.clearAll(app)
            ContentCache.clearAll(app)
            allStreams.forEach { it.epgNow = null }

            // Re-load live channel list (triggers loading state in UI)
            loadData(creds.first, creds.second, creds.third)

            // Re-fetch VOD movies + series in background
            launch(Dispatchers.IO) {
                repository.getAllVodStreams(creds.first, creds.second, creds.third)
                    .onSuccess { ContentCache.saveMovies(app, creds.first, it) }
                repository.getAllSeries(creds.first, creds.second, creds.third)
                    .onSuccess { ContentCache.saveSeries(app, creds.first, it) }
            }
        }
    }
}
