package com.skyretro.iptv.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyretro.iptv.data.model.*
import com.skyretro.iptv.data.repository.XtreamRepository
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

class HomeViewModel : ViewModel() {
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

    fun loadData(serverUrl: String, username: String, password: String, useOriginal: Boolean = false, customMapping: Map<String, SkyCategory> = emptyMap(), hiddenCategoryIds: Set<String> = emptySet()) {
        this.hiddenCategoryIds = hiddenCategoryIds
        credentials = Triple(serverUrl, username, password)
        _uiState.value = _uiState.value?.copy(isLoading = true, error = null, useOriginalCategories = useOriginal)

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
                            val skyGroups = buildSkyCategoryGroups(categories, customMapping, hiddenCategoryIds)
                            _uiState.value = _uiState.value?.copy(
                                isLoading = false,
                                skyCategories = skyGroups,
                                xtreamCategories = categories
                            )
                            
                            if (useOriginal) {
                                categories.firstOrNull()?.let { selectXtreamCategory(it) }
                            } else {
                                selectCategory(SkyCategory.ENTERTAINMENT)
                            }
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

    private fun fetchEpgForCurrentList(channels: List<LiveStream>) {
        val creds = credentials ?: return
        channels.take(20).forEach { stream ->
            if (stream.epgNow == null) {
                viewModelScope.launch {
                    val result = repository.getShortEpg(creds.first, creds.second, creds.third, stream.streamId)
                    result.onSuccess { epg ->
                        val now = epg.listings?.firstOrNull()?.getDecodedTitle()
                        if (!now.isNullOrBlank()) {
                            stream.epgNow = now
                            // Signal individual row refresh — no full list submitList
                            _epgUpdate.postValue(stream.streamId)
                        }
                    }
                }
            }
        }
    }

    fun buildStreamUrl(streamId: Int): String {
        val creds = credentials ?: return ""
        return repository.buildStreamUrl(creds.first, creds.second, creds.third, streamId)
    }
}
