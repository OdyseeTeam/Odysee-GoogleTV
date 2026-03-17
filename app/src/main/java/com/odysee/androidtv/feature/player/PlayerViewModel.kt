package com.odysee.androidtv.feature.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.odysee.androidtv.core.auth.AuthRepository
import com.odysee.androidtv.core.auth.AuthSessionStore
import com.odysee.androidtv.core.network.OdyseeApiClient
import com.odysee.androidtv.feature.discover.data.DiscoverRepository
import com.odysee.androidtv.feature.discover.model.VideoClaim
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val video: VideoClaim? = null,
    val streamUrl: String = "",
    val streamCandidates: List<String> = emptyList(),
    val currentStreamIndex: Int = 0,
    val streamGeneration: Long = 0L,
    val resumePositionMs: Long = 0L,
    val loading: Boolean = false,
    val reaction: String = "",
    val reactionBusy: Boolean = false,
    val recommendationsLoading: Boolean = false,
    val recommendedVideos: List<VideoClaim> = emptyList(),
    val nextRecommendedVideo: VideoClaim? = null,
    val errorMessage: String = "",
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val apiClient = OdyseeApiClient()
    private val authRepository = AuthRepository(
        api = apiClient,
        store = AuthSessionStore(application.applicationContext),
    )
    private val discoverRepository = DiscoverRepository(
        api = apiClient,
        authRepository = authRepository,
    )

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var recoveryJob: Job? = null
    private var recommendationsJob: Job? = null
    private var reactionRequestId: Long = 0L
    private var viewLogSentForClaimId: String = ""
    private var viewLogSendingForClaimId: String = ""
    private val playbackSessionSeenClaimIds = linkedSetOf<String>()

    fun openVideo(video: VideoClaim) {
        openVideoInternal(video = video, autoplayTransition = false)
    }

    fun autoplayNextRecommended(): Boolean {
        val nextVideo = _uiState.value.nextRecommendedVideo ?: return false
        openVideoInternal(video = nextVideo, autoplayTransition = true)
        return true
    }

    fun playRecommendedVideo(video: VideoClaim) {
        openVideoInternal(video = video, autoplayTransition = true)
    }

    private fun openVideoInternal(video: VideoClaim, autoplayTransition: Boolean) {
        loadJob?.cancel()
        recoveryJob?.cancel()
        recommendationsJob?.cancel()
        if (!autoplayTransition) {
            playbackSessionSeenClaimIds.clear()
        }
        video.claimId.trim().lowercase().takeIf { it.isNotBlank() }?.let {
            playbackSessionSeenClaimIds += it
        }
        reactionRequestId += 1
        viewLogSentForClaimId = ""
        viewLogSendingForClaimId = ""
        _uiState.update {
            it.copy(
                video = video,
                streamUrl = "",
                streamCandidates = emptyList(),
                currentStreamIndex = 0,
                resumePositionMs = 0L,
                loading = true,
                reaction = "",
                reactionBusy = false,
                recommendationsLoading = true,
                recommendedVideos = emptyList(),
                nextRecommendedVideo = null,
                errorMessage = "",
            )
        }
        loadJob = viewModelScope.launch {
            runCatching {
                discoverRepository.resolveStreamCandidates(video)
            }.onSuccess { candidates ->
                val streamUrl = candidates.firstOrNull().orEmpty()
                if (streamUrl.isBlank()) {
                    throw IllegalStateException("No stream URL available")
                }
                _uiState.update {
                    it.copy(
                        streamUrl = streamUrl,
                        streamCandidates = candidates,
                        currentStreamIndex = 0,
                        streamGeneration = it.streamGeneration + 1L,
                        resumePositionMs = 0L,
                        loading = false,
                        recommendationsLoading = true,
                        errorMessage = "",
                    )
                }
                fetchReactionsForActiveVideo()
                fetchRecommendationsForVideo(video)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        recommendationsLoading = false,
                        errorMessage = error.message ?: "Unable to load stream",
                    )
                }
            }
        }
    }

    private fun fetchRecommendationsForVideo(video: VideoClaim) {
        recommendationsJob?.cancel()
        val claimId = video.claimId.trim().lowercase()
        recommendationsJob = viewModelScope.launch {
            runCatching {
                discoverRepository.loadRecommendedVideos(video = video)
            }.onSuccess { recommendations ->
                val activeClaimId = _uiState.value.video?.claimId?.trim()?.lowercase().orEmpty()
                if (activeClaimId != claimId) {
                    return@onSuccess
                }
                val nextRecommended = pickNextRecommendation(recommendations)
                _uiState.update {
                    it.copy(
                        recommendationsLoading = false,
                        recommendedVideos = recommendations,
                        nextRecommendedVideo = nextRecommended,
                    )
                }
            }.onFailure {
                val activeClaimId = _uiState.value.video?.claimId?.trim()?.lowercase().orEmpty()
                if (activeClaimId != claimId) {
                    return@onFailure
                }
                _uiState.update {
                    it.copy(
                        recommendationsLoading = false,
                        recommendedVideos = emptyList(),
                        nextRecommendedVideo = null,
                    )
                }
            }
        }
    }

    private fun pickNextRecommendation(recommendations: List<VideoClaim>): VideoClaim? {
        return recommendations.firstOrNull { candidate ->
            val normalizedClaimId = candidate.claimId.trim().lowercase()
            normalizedClaimId.isNotBlank() && !playbackSessionSeenClaimIds.contains(normalizedClaimId)
        } ?: recommendations.firstOrNull()
    }

    fun recordViewIfNeeded() {
        val video = _uiState.value.video ?: return
        val claimId = video.claimId.trim().lowercase()
        if (claimId.isBlank()) {
            return
        }
        if (viewLogSentForClaimId == claimId || viewLogSendingForClaimId == claimId) {
            return
        }
        viewLogSendingForClaimId = claimId
        viewModelScope.launch {
            runCatching {
                discoverRepository.logFileView(video)
            }.onSuccess {
                if (_uiState.value.video?.claimId?.trim()?.lowercase() == claimId) {
                    viewLogSentForClaimId = claimId
                }
                if (viewLogSendingForClaimId == claimId) {
                    viewLogSendingForClaimId = ""
                }
            }.onFailure {
                if (viewLogSendingForClaimId == claimId) {
                    viewLogSendingForClaimId = ""
                }
            }
        }
    }

    fun recoverPlayback(positionMs: Long) {
        val state = _uiState.value
        val video = state.video ?: return
        if (state.loading) {
            return
        }
        recoveryJob?.cancel()
        recoveryJob = viewModelScope.launch {
            val snapshot = _uiState.value
            if (snapshot.video?.claimId != video.claimId) {
                return@launch
            }
            val resumePositionMs = positionMs.coerceAtLeast(0L)
            val nextIndex = snapshot.currentStreamIndex + 1
            if (nextIndex < snapshot.streamCandidates.size) {
                val nextUrl = snapshot.streamCandidates[nextIndex]
                _uiState.update { current ->
                    if (current.video?.claimId != video.claimId) {
                        return@update current
                    }
                    current.copy(
                        streamUrl = nextUrl,
                        currentStreamIndex = nextIndex,
                        streamGeneration = current.streamGeneration + 1L,
                        resumePositionMs = resumePositionMs,
                        loading = false,
                        errorMessage = "",
                    )
                }
                return@launch
            }

            _uiState.update { current ->
                if (current.video?.claimId != video.claimId) {
                    return@update current
                }
                current.copy(
                    loading = true,
                    errorMessage = "",
                )
            }

            runCatching {
                discoverRepository.resolveStreamCandidates(
                    video = video,
                    forceAuthRefresh = true,
                )
            }.onSuccess { refreshedCandidates ->
                val refreshedUrl = refreshedCandidates.firstOrNull().orEmpty()
                if (refreshedUrl.isBlank()) {
                    throw IllegalStateException("No stream URL available")
                }
                _uiState.update { current ->
                    if (current.video?.claimId != video.claimId) {
                        return@update current
                    }
                    current.copy(
                        streamUrl = refreshedUrl,
                        streamCandidates = refreshedCandidates,
                        currentStreamIndex = 0,
                        streamGeneration = current.streamGeneration + 1L,
                        resumePositionMs = resumePositionMs,
                        loading = false,
                        errorMessage = "",
                    )
                }
            }.onFailure { error ->
                _uiState.update { current ->
                    if (current.video?.claimId != video.claimId) {
                        return@update current
                    }
                    current.copy(
                        loading = false,
                        errorMessage = error.message ?: "Playback stalled. Unable to recover stream",
                    )
                }
            }
        }
    }

    fun toggleReaction(target: String) {
        val state = _uiState.value
        val video = state.video ?: return
        if (video.claimId.isBlank() || state.reactionBusy) {
            return
        }
        val reactionType = when (target.lowercase()) {
            "fire" -> "like"
            "slime" -> "dislike"
            else -> return
        }
        val shouldRemove = state.reaction == reactionType
        val claimId = video.claimId
        val requestId = reactionRequestId + 1
        reactionRequestId = requestId

        _uiState.update { it.copy(reactionBusy = true) }
        viewModelScope.launch {
            runCatching {
                discoverRepository.reactToClaim(
                    claimIdRaw = claimId,
                    reactionTypeRaw = reactionType,
                    remove = shouldRemove,
                )
            }.onSuccess {
                if (requestId != reactionRequestId) {
                    return@onSuccess
                }
                _uiState.update {
                    it.copy(
                        reactionBusy = false,
                        reaction = if (shouldRemove) "" else reactionType,
                    )
                }
            }.onFailure {
                if (requestId != reactionRequestId) {
                    return@onFailure
                }
                _uiState.update { it.copy(reactionBusy = false) }
            }
        }
    }

    fun fetchReactionsForActiveVideo() {
        val state = _uiState.value
        val video = state.video ?: return
        if (video.claimId.isBlank()) {
            return
        }
        val claimId = video.claimId
        val requestId = reactionRequestId + 1
        reactionRequestId = requestId

        _uiState.update { it.copy(reactionBusy = true, reaction = "") }
        viewModelScope.launch {
            runCatching {
                discoverRepository.listClaimReactions(claimId)
            }.onSuccess { payload ->
                if (requestId != reactionRequestId) {
                    return@onSuccess
                }
                val reaction = discoverRepository.getMyClaimReaction(payload, claimId)
                _uiState.update {
                    it.copy(
                        reactionBusy = false,
                        reaction = reaction,
                    )
                }
            }.onFailure {
                if (requestId != reactionRequestId) {
                    return@onFailure
                }
                _uiState.update { it.copy(reactionBusy = false, reaction = "") }
            }
        }
    }

    fun closePlayer() {
        loadJob?.cancel()
        recoveryJob?.cancel()
        recommendationsJob?.cancel()
        reactionRequestId += 1
        viewLogSentForClaimId = ""
        viewLogSendingForClaimId = ""
        playbackSessionSeenClaimIds.clear()
        _uiState.value = PlayerUiState()
    }
}
