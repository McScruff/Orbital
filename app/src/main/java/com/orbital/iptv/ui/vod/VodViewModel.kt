package com.orbital.iptv.ui.vod

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orbital.iptv.data.model.VodCategory
import com.orbital.iptv.data.model.VodStream
import com.orbital.iptv.data.repository.XtreamRepository
import kotlinx.coroutines.launch

data class VodUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val categories: List<VodCategory> = emptyList(),
    val selectedCategory: VodCategory? = null,
    val movies: List<VodStream> = emptyList()
)

class VodViewModel : ViewModel() {

    private val repository = XtreamRepository()
    private val _uiState = MutableLiveData(VodUiState())
    val uiState: LiveData<VodUiState> = _uiState

    private var creds: Triple<String, String, String>? = null

    fun loadCategories(serverUrl: String, username: String, password: String) {
        creds = Triple(serverUrl, username, password)
        _uiState.value = _uiState.value?.copy(isLoading = true, error = null)
        viewModelScope.launch {
            repository.getVodCategories(serverUrl, username, password).fold(
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

    fun selectCategory(category: VodCategory) {
        val c = creds ?: return
        _uiState.value = _uiState.value?.copy(isLoading = true, selectedCategory = category, movies = emptyList())
        viewModelScope.launch {
            repository.getVodStreams(c.first, c.second, c.third, category.categoryId).fold(
                onSuccess = { movies ->
                    _uiState.value = _uiState.value?.copy(isLoading = false, movies = movies)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value?.copy(isLoading = false, error = e.message)
                }
            )
        }
    }

    fun buildVodUrl(streamId: Int, ext: String): String {
        val c = creds ?: return ""
        return repository.buildVodUrl(c.first, c.second, c.third, streamId, ext)
    }

    fun getCredentials() = creds
}
