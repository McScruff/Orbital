package com.skyretro.iptv.ui.series

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyretro.iptv.data.model.SeriesCategory
import com.skyretro.iptv.data.model.SeriesStream
import com.skyretro.iptv.data.repository.XtreamRepository
import kotlinx.coroutines.launch

data class SeriesUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val categories: List<SeriesCategory> = emptyList(),
    val selectedCategory: SeriesCategory? = null,
    val shows: List<SeriesStream> = emptyList()
)

class SeriesViewModel : ViewModel() {

    private val repository = XtreamRepository()
    private val _uiState = MutableLiveData(SeriesUiState())
    val uiState: LiveData<SeriesUiState> = _uiState

    private var creds: Triple<String, String, String>? = null

    fun loadCategories(serverUrl: String, username: String, password: String) {
        creds = Triple(serverUrl, username, password)
        _uiState.value = _uiState.value?.copy(isLoading = true, error = null)
        viewModelScope.launch {
            repository.getSeriesCategories(serverUrl, username, password).fold(
                onSuccess = { cats ->
                    _uiState.value = _uiState.value?.copy(isLoading = false, categories = cats)
                    cats.firstOrNull()?.let { selectCategory(it) }
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value?.copy(isLoading = false, error = e.message)
                }
            )
        }
    }

    fun selectCategory(category: SeriesCategory) {
        val c = creds ?: return
        _uiState.value = _uiState.value?.copy(isLoading = true, selectedCategory = category, shows = emptyList())
        viewModelScope.launch {
            repository.getSeriesByCategory(c.first, c.second, c.third, category.categoryId).fold(
                onSuccess = { shows ->
                    _uiState.value = _uiState.value?.copy(isLoading = false, shows = shows)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value?.copy(isLoading = false, error = e.message)
                }
            )
        }
    }

    fun getCredentials() = creds

    suspend fun getAllSeriesForCache(serverUrl: String, username: String, password: String): List<SeriesStream>? =
        repository.getAllSeries(serverUrl, username, password).getOrNull()
}
