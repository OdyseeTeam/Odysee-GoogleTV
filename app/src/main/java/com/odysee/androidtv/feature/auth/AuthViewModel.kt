package com.odysee.androidtv.feature.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.odysee.androidtv.core.auth.AuthChannel
import com.odysee.androidtv.core.auth.AuthRepository
import com.odysee.androidtv.core.auth.AuthSessionStore
import com.odysee.androidtv.core.network.OdyseeApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val signedIn: Boolean = false,
    val signedInEmail: String = "",
    val signedInChannelName: String = "",
    val signedInAvatarUrl: String = "",
    val defaultChannelClaimId: String = "",
    val emailInput: String = "",
    val passwordInput: String = "",
    val dialogOpen: Boolean = false,
    val isBusy: Boolean = false,
    val awaitingVerification: Boolean = false,
    val switchChannels: List<AuthChannel> = emptyList(),
    val switchChannelLoading: Boolean = false,
    val selectedSwitchChannelId: String = "",
    val message: String = "",
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AuthRepository(
        api = OdyseeApiClient(),
        store = AuthSessionStore(application.applicationContext),
    )

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        refreshUser()
    }

    fun onEmailInputChanged(value: String) {
        _uiState.update { it.copy(emailInput = value) }
    }

    fun onPasswordInputChanged(value: String) {
        _uiState.update { it.copy(passwordInput = value) }
    }

    fun openDialog() {
        _uiState.update {
            it.copy(
                dialogOpen = true,
                message = "",
                passwordInput = if (it.signedIn) it.passwordInput else "",
            )
        }
        if (uiState.value.signedIn) {
            loadSwitchChannels(silent = false)
        }
    }

    fun closeDialog() {
        _uiState.update {
            it.copy(
                dialogOpen = false,
                isBusy = false,
                awaitingVerification = false,
                switchChannelLoading = false,
                passwordInput = "",
            )
        }
    }

    fun sendMagicLink() {
        val email = uiState.value.emailInput
        if (email.isBlank()) {
            _uiState.update { it.copy(message = "Enter your email.") }
            return
        }

        pollingJob?.cancel()

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    message = "Sending sign-in link...",
                )
            }

            runCatching {
                repository.requestMagicLink(email)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        awaitingVerification = true,
                        message = "Check your email and click the sign-in link.",
                    )
                }
                startPollingForVerification()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        awaitingVerification = false,
                        message = err.message ?: "Could not send sign-in link.",
                    )
                }
            }
        }
    }

    fun signInWithPassword() {
        val state = uiState.value
        if (state.emailInput.isBlank()) {
            _uiState.update { it.copy(message = "Enter your email.") }
            return
        }
        if (state.passwordInput.isBlank()) {
            _uiState.update { it.copy(message = "Enter your password.") }
            return
        }

        pollingJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    awaitingVerification = false,
                    message = "Signing in...",
                )
            }

            runCatching {
                repository.signInWithPassword(
                    emailRaw = state.emailInput,
                    passwordRaw = state.passwordInput,
                )
            }.onSuccess { user ->
                applySignedInUser(user)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = err.message ?: "Could not sign in.",
                    )
                }
            }
        }
    }

    fun refreshUser() {
        viewModelScope.launch {
            runCatching { repository.checkSignedInUser() }
                .onSuccess { user ->
                    if (user != null) {
                        applySignedInUser(user)
                    } else {
                        _uiState.update {
                            it.copy(
                                signedIn = false,
                                signedInEmail = "",
                                signedInChannelName = "",
                                signedInAvatarUrl = "",
                                defaultChannelClaimId = "",
                                switchChannels = emptyList(),
                                selectedSwitchChannelId = "",
                                passwordInput = "",
                            )
                        }
                    }
                }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { repository.signOut() }
            _uiState.update {
                it.copy(
                    signedIn = false,
                    signedInEmail = "",
                    signedInChannelName = "",
                    signedInAvatarUrl = "",
                    defaultChannelClaimId = "",
                    switchChannels = emptyList(),
                    selectedSwitchChannelId = "",
                    passwordInput = "",
                    message = "Signed out.",
                )
            }
        }
    }

    fun selectSwitchChannel(channelId: String) {
        _uiState.update { it.copy(selectedSwitchChannelId = channelId) }
    }

    fun switchChannel(channelIdRaw: String) {
        val channelId = channelIdRaw.trim()
        if (channelId.isBlank()) {
            return
        }
        val state = uiState.value
        if (!state.signedIn || state.isBusy) {
            return
        }
        val selectedChannel = state.switchChannels.firstOrNull { channelIdsMatch(it.channelId, channelId) }
        if (channelIdsMatch(channelId, state.defaultChannelClaimId)) {
            _uiState.update {
                it.copy(
                    selectedSwitchChannelId = channelId,
                    message = "Already using this channel.",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                selectedSwitchChannelId = channelId,
                isBusy = true,
                message = "",
            )
        }

        viewModelScope.launch {
            runCatching {
                repository.setDefaultChannel(channelId)
                repository.checkSignedInUser()
            }.onSuccess { refreshedUser ->
                _uiState.update { current ->
                    val updated = refreshedUser
                    val resolvedDefaultChannelId = channelId
                    val resolvedChannelName = selectedChannel?.channelName
                        .orEmpty()
                        .ifBlank { updated?.defaultChannelName.orEmpty() }
                        .ifBlank { current.signedInChannelName }
                    val resolvedAvatarUrl = selectedChannel?.channelAvatarUrl
                        .orEmpty()
                        .ifBlank { updated?.avatarUrl.orEmpty() }
                        .ifBlank { current.signedInAvatarUrl }
                    current.copy(
                        isBusy = false,
                        message = "Default channel updated.",
                        selectedSwitchChannelId = resolvedDefaultChannelId,
                        signedInChannelName = resolvedChannelName,
                        signedInAvatarUrl = resolvedAvatarUrl,
                        defaultChannelClaimId = resolvedDefaultChannelId,
                    )
                }
                loadSwitchChannels(silent = true)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "Could not switch channel.",
                    )
                }
            }
        }
    }

    fun applySelectedChannel() {
        val state = uiState.value
        if (state.selectedSwitchChannelId.isBlank()) {
            return
        }
        switchChannel(state.selectedSwitchChannelId)
    }

    private fun loadSwitchChannels(silent: Boolean) {
        if (!uiState.value.signedIn) {
            return
        }
        viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(switchChannelLoading = true) }
            }
            val channels = runCatching { repository.listMyChannels() }.getOrDefault(emptyList())
            _uiState.update { state ->
                val selected = when {
                    channels.isEmpty() -> ""
                    state.selectedSwitchChannelId.isNotBlank() &&
                        channels.any { channelIdsMatch(it.channelId, state.selectedSwitchChannelId) } -> state.selectedSwitchChannelId
                    state.defaultChannelClaimId.isNotBlank() &&
                        channels.any { channelIdsMatch(it.channelId, state.defaultChannelClaimId) } -> state.defaultChannelClaimId
                    else -> channels.first().channelId
                }
                val selectedChannel = channels.firstOrNull { channelIdsMatch(it.channelId, selected) }
                val fallbackAvatar = selectedChannel?.channelAvatarUrl
                    .orEmpty()
                    .ifBlank { channels.firstOrNull { it.channelAvatarUrl.isNotBlank() }?.channelAvatarUrl.orEmpty() }
                val resolvedAvatar = fallbackAvatar.ifBlank { state.signedInAvatarUrl }
                val resolvedName = selectedChannel?.channelName.orEmpty().ifBlank { state.signedInChannelName }
                val resolvedDefaultChannelClaimId = selected.ifBlank { state.defaultChannelClaimId }
                state.copy(
                    switchChannels = channels,
                    switchChannelLoading = false,
                    selectedSwitchChannelId = selected,
                    signedInAvatarUrl = resolvedAvatar,
                    signedInChannelName = resolvedName,
                    defaultChannelClaimId = resolvedDefaultChannelClaimId,
                )
            }
        }
    }

    private fun startPollingForVerification() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(5_000)
                val user = runCatching { repository.checkSignedInUser() }.getOrNull()
                if (user != null) {
                    applySignedInUser(user)
                    break
                }
            }
        }
    }

    private fun applySignedInUser(user: com.odysee.androidtv.core.auth.AuthUser) {
        _uiState.update {
            it.copy(
                signedIn = true,
                signedInEmail = user.email,
                signedInChannelName = user.defaultChannelName,
                signedInAvatarUrl = user.avatarUrl,
                defaultChannelClaimId = user.defaultChannelClaimId,
                awaitingVerification = false,
                isBusy = false,
                dialogOpen = false,
                passwordInput = "",
                message = "",
            )
        }
        if (user.avatarUrl.isBlank()) {
            loadSwitchChannels(silent = true)
        }
        pollingJob?.cancel()
    }

    private fun channelIdsMatch(left: String, right: String): Boolean {
        val normalizedLeft = normalizeClaimId(left)
        val normalizedRight = normalizeClaimId(right)
        return normalizedLeft.isNotBlank() && normalizedLeft == normalizedRight
    }

    private fun normalizeClaimId(value: String): String {
        val candidate = value.trim().lowercase()
        if (candidate.isBlank()) {
            return ""
        }
        val match = CLAIM_ID_REGEX.find(candidate) ?: return ""
        return match.value
    }

    private companion object {
        val CLAIM_ID_REGEX = Regex("[0-9a-f]{40}")
    }
}
