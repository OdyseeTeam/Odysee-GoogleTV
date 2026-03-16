package com.odysee.androidtv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.focusable
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odysee.androidtv.core.auth.AuthChannel
import com.odysee.androidtv.feature.auth.AuthViewModel
import com.odysee.androidtv.feature.discover.DiscoverViewModel
import com.odysee.androidtv.feature.discover.model.Category
import com.odysee.androidtv.feature.discover.model.VideoClaim
import com.odysee.androidtv.feature.player.PlayerViewModel
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun OdyseeTvApp() {
    val authViewModel: AuthViewModel = viewModel()
    val discoverViewModel: DiscoverViewModel = viewModel()
    val playerViewModel: PlayerViewModel = viewModel()

    val authUi by authViewModel.uiState.collectAsState()
    val discoverUi by discoverViewModel.uiState.collectAsState()
    val playerUi by playerViewModel.uiState.collectAsState()
    var searchDialogOpen by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var lastFocusedVideoClaimId by remember { mutableStateOf("") }
    var konamiProgress by remember { mutableIntStateOf(0) }
    var confettiBurstNonce by remember { mutableIntStateOf(0) }
    var confettiVisible by remember { mutableStateOf(false) }
    val splashStartedAtMs = remember { SystemClock.elapsedRealtime() }
    var splashVisible by remember { mutableStateOf(true) }
    val startupReady = (
        !discoverUi.loadingCategories &&
            !discoverUi.loadingVideos &&
            (
                discoverUi.categories.isNotEmpty() ||
                    discoverUi.errorMessage.isNotBlank()
                )
        )

    LaunchedEffect(confettiBurstNonce) {
        if (confettiBurstNonce <= 0) {
            return@LaunchedEffect
        }
        confettiVisible = true
        delay(KONAMI_CONFETTI_VISIBLE_MS)
        confettiVisible = false
    }

    LaunchedEffect(authUi.signedIn) {
        discoverViewModel.onAuthStateChanged(authUi.signedIn)
    }

    LaunchedEffect(startupReady, splashVisible) {
        if (!splashVisible || !startupReady) {
            return@LaunchedEffect
        }
        val elapsedMs = SystemClock.elapsedRealtime() - splashStartedAtMs
        val remainingMs = (STARTUP_SPLASH_MIN_VISIBLE_MS - elapsedMs).coerceAtLeast(0L)
        if (remainingMs > 0L) {
            delay(remainingMs)
        }
        splashVisible = false
    }

    LaunchedEffect(splashVisible) {
        if (!splashVisible) {
            return@LaunchedEffect
        }
        delay(STARTUP_SPLASH_MAX_VISIBLE_MS)
        splashVisible = false
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0B10)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        val input = mapKonamiInput(keyEvent.key) ?: return@onPreviewKeyEvent false
                        val expectedInputs = KONAMI_STEPS[konamiProgress]
                        konamiProgress = when {
                            input in expectedInputs -> konamiProgress + 1
                            input in KONAMI_STEPS.first() -> 1
                            else -> 0
                        }
                        if (konamiProgress >= KONAMI_STEPS.size) {
                            konamiProgress = 0
                            confettiBurstNonce += 1
                        }
                        false
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF09090C), Color(0xFF121226))
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Header(
                        signedIn = authUi.signedIn,
                        signedInChannelName = authUi.signedInChannelName,
                        signedInAvatarUrl = authUi.signedInAvatarUrl,
                        onSearchClick = { searchDialogOpen = true },
                        onAccountClick = {
                            if (playerUi.video == null) {
                                authViewModel.openDialog()
                            }
                        },
                    )

                    Content(
                        categories = discoverUi.categories,
                        selectedCategoryId = discoverUi.selectedCategoryId,
                        contentTitle = discoverUi.contentTitle,
                        videos = discoverUi.videos,
                        signedIn = authUi.signedIn,
                        inChannelView = discoverUi.inChannelView,
                        activeChannelId = discoverUi.activeChannelId,
                        channelFollowed = discoverUi.channelFollowed,
                        channelFollowBusy = discoverUi.channelFollowBusy,
                        loadingCategories = discoverUi.loadingCategories,
                        loadingVideos = discoverUi.loadingVideos,
                        errorMessage = discoverUi.errorMessage,
                        onRetry = discoverViewModel::retryCurrentFeed,
                        onCategorySelected = discoverViewModel::selectCategory,
                        onVideoSelected = { claim ->
                            lastFocusedVideoClaimId = claim.claimId
                            playerViewModel.openVideo(claim)
                        },
                        onLoadMore = discoverViewModel::loadMoreVideos,
                        playerVisible = playerUi.video != null,
                        lastFocusedVideoClaimId = lastFocusedVideoClaimId,
                        onVideoFocused = { lastFocusedVideoClaimId = it },
                        onChannelSelected = { claim ->
                            discoverViewModel.openChannelFeed(
                                channelIdRaw = claim.channelClaimId,
                                channelNameRaw = claim.channelName,
                                channelAvatarUrl = claim.channelAvatarUrl,
                            )
                        },
                        onToggleChannelFollow = discoverViewModel::toggleActiveChannelFollow,
                        onRefreshCategory = discoverViewModel::refreshCategory,
                    )
                }

                if (searchDialogOpen) {
                    SearchDialog(
                        query = searchInput,
                        onQueryChanged = { searchInput = it },
                        onDismiss = { searchDialogOpen = false },
                        onSubmit = {
                            val query = searchInput.trim()
                            if (query.isNotBlank()) {
                                discoverViewModel.search(query)
                                searchDialogOpen = false
                            }
                        },
                    )
                }

                if (authUi.dialogOpen) {
                    AuthDialog(
                        email = authUi.emailInput,
                        password = authUi.passwordInput,
                        isBusy = authUi.isBusy,
                        awaitingVerification = authUi.awaitingVerification,
                        signedIn = authUi.signedIn,
                        signedInEmail = authUi.signedInEmail,
                        signedInChannelName = authUi.signedInChannelName,
                        signedInAvatarUrl = authUi.signedInAvatarUrl,
                        switchChannels = authUi.switchChannels,
                        switchChannelLoading = authUi.switchChannelLoading,
                        selectedSwitchChannelId = authUi.selectedSwitchChannelId,
                        message = authUi.message,
                        onEmailChanged = authViewModel::onEmailInputChanged,
                        onPasswordChanged = authViewModel::onPasswordInputChanged,
                        onSignInWithPassword = authViewModel::signInWithPassword,
                        onSendMagicLink = authViewModel::sendMagicLink,
                        onSwitchChannel = authViewModel::switchChannel,
                        onOpenMyChannel = {
                            discoverViewModel.openSignedInDefaultChannel(
                                channelId = authUi.defaultChannelClaimId,
                                channelName = authUi.signedInChannelName,
                                avatarUrl = authUi.signedInAvatarUrl,
                            )
                            authViewModel.closeDialog()
                        },
                        onSignOut = authViewModel::signOut,
                        onDismiss = authViewModel::closeDialog,
                    )
                }

                if (playerUi.video != null) {
                    Dialog(
                        onDismissRequest = {},
                        properties = DialogProperties(
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                            usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = false,
                        ),
                    ) {
                        PlayerScreen(
                            video = playerUi.video,
                            streamUrl = playerUi.streamUrl,
                            streamGeneration = playerUi.streamGeneration,
                            resumePositionMs = playerUi.resumePositionMs,
                            loading = playerUi.loading,
                            recommendationsLoading = playerUi.recommendationsLoading,
                            recommendedVideos = playerUi.recommendedVideos,
                            nextRecommendedVideo = playerUi.nextRecommendedVideo,
                            signedIn = authUi.signedIn,
                            reaction = playerUi.reaction,
                            reactionBusy = playerUi.reactionBusy,
                            errorMessage = playerUi.errorMessage,
                            onFireReaction = { playerViewModel.toggleReaction("fire") },
                            onSlimeReaction = { playerViewModel.toggleReaction("slime") },
                            onAutoplayNext = {
                                if (!playerViewModel.autoplayNextRecommended()) {
                                    playerViewModel.closePlayer()
                                }
                            },
                            onPlayRecommendation = playerViewModel::playRecommendedVideo,
                            onOpenChannel = {
                                playerUi.video?.let { video ->
                                    discoverViewModel.openChannelFeed(
                                        channelIdRaw = video.channelClaimId,
                                        channelNameRaw = video.channelName,
                                        channelAvatarUrl = video.channelAvatarUrl,
                                    )
                                    playerViewModel.closePlayer()
                                }
                            },
                            onPlaybackStarted = playerViewModel::recordViewIfNeeded,
                            onRecoverPlayback = playerViewModel::recoverPlayback,
                            onRetry = { playerViewModel.recoverPlayback(playerUi.resumePositionMs) },
                            onClose = playerViewModel::closePlayer,
                        )
                    }
                }

                if (confettiVisible) {
                    ConfettiOverlay(
                        burstNonce = confettiBurstNonce,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                AnimatedVisibility(
                    visible = splashVisible,
                    enter = fadeIn(animationSpec = tween(durationMillis = 220)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 420)),
                ) {
                    StartupSplashOverlay()
                }
            }
        }
    }
}

@Composable
private fun Header(
    signedIn: Boolean,
    signedInChannelName: String,
    signedInAvatarUrl: String,
    onSearchClick: () -> Unit,
    onAccountClick: () -> Unit,
) {
    val channelLabel = normalizeHeaderChannelLabel(signedInChannelName)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Box(modifier = Modifier.align(Alignment.CenterStart)) {
            OdyseeLogo()
        }

        Box(modifier = Modifier.align(Alignment.Center)) {
            HeaderButton(
                label = "Search",
                onClick = onSearchClick,
                maxWidth = 220.dp,
            )
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (signedIn) {
                HeaderAvatarButton(
                    avatarUrl = signedInAvatarUrl,
                    fallbackLabel = channelLabel,
                    onClick = onAccountClick,
                )
            } else {
                HeaderButton(
                    label = "Sign In",
                    onClick = onAccountClick,
                    maxWidth = 140.dp,
                )
            }
        }
    }
}

@Composable
private fun HeaderAvatarButton(
    avatarUrl: String,
    fallbackLabel: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .background(
                color = if (focused) Color(0x4D2A2A4E) else Color.Transparent,
                shape = CircleShape,
            )
            .border(
                width = if (focused) 3.dp else 2.dp,
                color = if (focused) Color(0xFFA79BFF) else Color(0xFF4F46E5),
                shape = CircleShape,
            )
            .focusable()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUrl.isNotBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = "Account",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = fallbackLabel.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun OdyseeLogo() {
    val context = LocalContext.current
    val request = remember(context) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/logo.svg")
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = "Odysee",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .width(136.dp)
            .height(32.dp),
    )
}

@Composable
private fun StartupSplashOverlay() {
    val infinite = rememberInfiniteTransition(label = "startup-splash")
    val drift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 6400,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "startup-drift",
    )
    val wave by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1350,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "startup-wave",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF07070A),
                        Color(0xFF111120),
                        Color(0xFF1A1830),
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0x227F5CFF),
                radius = size.minDimension * 0.42f,
                center = Offset(
                    x = size.width * (0.18f + (0.05f * drift)),
                    y = size.height * 0.26f,
                ),
            )
            drawCircle(
                color = Color(0x1838BDF8),
                radius = size.minDimension * 0.34f,
                center = Offset(
                    x = size.width * (0.82f - (0.04f * drift)),
                    y = size.height * 0.78f,
                ),
            )
            drawCircle(
                color = Color(0x12FFFFFF),
                radius = size.minDimension * 0.22f,
                center = Offset(
                    x = size.width * 0.52f,
                    y = size.height * (0.46f + (0.03f * sin(drift * 6.283185f))),
                ),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0x44111120), shape = MaterialTheme.shapes.large)
                    .border(
                        width = 1.dp,
                        color = Color(0x554F46E5),
                        shape = MaterialTheme.shapes.large,
                    )
                    .padding(horizontal = 34.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val context = LocalContext.current
                    val request = remember(context) {
                        ImageRequest.Builder(context)
                            .data("file:///android_asset/logo.svg")
                            .decoderFactory(SvgDecoder.Factory())
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = "Odysee",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(232.dp)
                            .height(56.dp),
                    )
                    Text(
                        text = "Loading your home feed",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(3) { index ->
                            val pulse = ((sin((wave * 6.283185f) + (index * 0.95f)) + 1f) / 2f)
                            Box(
                                modifier = Modifier
                                    .width(46.dp)
                                    .height(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Color(0xFF9D8CFF).copy(alpha = 0.22f + (0.68f * pulse))
                                    )
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun HeaderButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    maxWidth: Dp = 168.dp,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(maxWidth)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else 2.dp,
                color = if (focused) Color(0xFFA79BFF) else Color(0xFF4F46E5),
                shape = MaterialTheme.shapes.medium,
            )
            .background(
                color = if (focused) Color(0x4D2A2A4E) else Color.Transparent,
                shape = MaterialTheme.shapes.medium,
            )
            .focusable(enabled = enabled)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color(0x99FFFFFF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Content(
    categories: List<Category>,
    selectedCategoryId: String,
    contentTitle: String,
    videos: List<VideoClaim>,
    signedIn: Boolean,
    inChannelView: Boolean,
    activeChannelId: String,
    channelFollowed: Boolean,
    channelFollowBusy: Boolean,
    loadingCategories: Boolean,
    loadingVideos: Boolean,
    errorMessage: String,
    onRetry: () -> Unit,
    onCategorySelected: (String) -> Unit,
    onVideoSelected: (VideoClaim) -> Unit,
    onLoadMore: () -> Unit,
    playerVisible: Boolean,
    lastFocusedVideoClaimId: String,
    onVideoFocused: (String) -> Unit,
    onChannelSelected: (VideoClaim) -> Unit,
    onToggleChannelFollow: () -> Unit,
    onRefreshCategory: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sidebarState = rememberLazyListState()
    val sidebarFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val videoFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val gridState = rememberLazyGridState()
    var focusedGridIndex by remember { mutableIntStateOf(-1) }
    var previousPlayerVisible by remember { mutableStateOf(playerVisible) }
    var openedFromCategoryId by remember { mutableStateOf("") }
    var openedFromClaimId by remember { mutableStateOf("") }
    var openedFromFocusedIndex by remember { mutableIntStateOf(-1) }
    var openedFromFirstVisibleIndex by remember { mutableIntStateOf(0) }
    var openedFromFirstVisibleOffset by remember { mutableIntStateOf(0) }
    var pendingRestoreAfterClose by remember { mutableStateOf(false) }
    var pendingRestoreCategoryRequestId by remember { mutableStateOf("") }
    var channelAutoFocusHandledForId by remember { mutableStateOf("") }
    var sidebarSelectionJob by remember { mutableStateOf<Job?>(null) }
    var pendingSidebarRightCategoryId by remember { mutableStateOf("") }
    var sidebarLongPressRefreshJob by remember { mutableStateOf<Job?>(null) }
    var sidebarLongPressConsumedCategoryId by remember { mutableStateOf("") }

    fun requesterForCategory(categoryId: String): FocusRequester {
        return sidebarFocusRequesters.getOrPut(categoryId) { FocusRequester() }
    }

    fun requesterForVideo(claimId: String): FocusRequester {
        return videoFocusRequesters.getOrPut(claimId) { FocusRequester() }
    }

    fun requestFocusSafely(requester: FocusRequester): Boolean =
        runCatching {
            requester.requestFocus()
            true
        }.getOrDefault(false)

    suspend fun requestFocusWhenReady(requester: FocusRequester, attempts: Int = 6): Boolean {
        repeat(attempts.coerceAtLeast(1)) {
            if (requestFocusSafely(requester)) {
                return true
            }
            withFrameNanos { }
        }
        return false
    }

    fun focusFirstVideoCard(): Boolean {
        val firstClaimId = videos.firstOrNull()?.claimId.orEmpty()
        if (firstClaimId.isBlank()) {
            return false
        }
        if (!requestFocusSafely(requesterForVideo(firstClaimId))) {
            return false
        }
        focusedGridIndex = 0
        onVideoFocused(firstClaimId)
        return true
    }

    LaunchedEffect(categories, selectedCategoryId) {
        val selectedIndex = categories.indexOfFirst { it.id == selectedCategoryId }
        if (selectedIndex < 0) {
            return@LaunchedEffect
        }
        val visible = sidebarState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex }
        if (!visible) {
            sidebarState.scrollToItem(selectedIndex)
        }
    }

    LaunchedEffect(gridState, videos.size, loadingVideos) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (
                    !loadingVideos &&
                    videos.isNotEmpty() &&
                    lastVisibleIndex >= (videos.lastIndex - LOAD_MORE_PREFETCH_ITEMS)
                ) {
                    onLoadMore()
                }
            }
    }

    LaunchedEffect(playerVisible, videos, lastFocusedVideoClaimId, inChannelView) {
        if (!previousPlayerVisible && playerVisible) {
            openedFromCategoryId = selectedCategoryId
            openedFromClaimId = when {
                lastFocusedVideoClaimId.isNotBlank() -> lastFocusedVideoClaimId
                focusedGridIndex in videos.indices -> videos[focusedGridIndex].claimId
                else -> ""
            }
            openedFromFocusedIndex = focusedGridIndex
            openedFromFirstVisibleIndex = gridState.firstVisibleItemIndex
            openedFromFirstVisibleOffset = gridState.firstVisibleItemScrollOffset
            pendingRestoreAfterClose = false
            pendingRestoreCategoryRequestId = ""
        }

        if (previousPlayerVisible && !playerVisible) {
            // If we're entering a channel feed from player actions, do not snap back to the prior grid.
            pendingRestoreAfterClose = !inChannelView
            pendingRestoreCategoryRequestId = ""
        }
        previousPlayerVisible = playerVisible
    }

    LaunchedEffect(
        pendingRestoreAfterClose,
        playerVisible,
        selectedCategoryId,
        categories,
        videos,
        loadingVideos,
        lastFocusedVideoClaimId,
    ) {
        if (!pendingRestoreAfterClose || playerVisible) {
            return@LaunchedEffect
        }
        if (
            openedFromCategoryId.isNotBlank() &&
            openedFromCategoryId != selectedCategoryId &&
            categories.any { it.id == openedFromCategoryId }
        ) {
            if (pendingRestoreCategoryRequestId != openedFromCategoryId) {
                pendingRestoreCategoryRequestId = openedFromCategoryId
                onCategorySelected(openedFromCategoryId)
            }
            return@LaunchedEffect
        }
        if (loadingVideos || videos.isEmpty()) {
            return@LaunchedEffect
        }

        runCatching {
            val claimIndex = if (openedFromClaimId.isNotBlank()) {
                videos.indexOfFirst { it.claimId == openedFromClaimId }
            } else if (lastFocusedVideoClaimId.isNotBlank()) {
                videos.indexOfFirst { it.claimId == lastFocusedVideoClaimId }
            } else {
                -1
            }
            val restoreIndex = when {
                claimIndex in videos.indices -> claimIndex
                openedFromFocusedIndex in videos.indices -> openedFromFocusedIndex
                focusedGridIndex in videos.indices -> focusedGridIndex
                openedFromFirstVisibleIndex in videos.indices -> openedFromFirstVisibleIndex
                else -> 0
            }
            if (restoreIndex in videos.indices) {
                val restoreViewportIndex = openedFromFirstVisibleIndex
                    .coerceIn(0, videos.lastIndex)
                val restoreViewportOffset = openedFromFirstVisibleOffset.coerceAtLeast(0)
                gridState.scrollToItem(restoreViewportIndex, restoreViewportOffset)

                val visible = gridState.layoutInfo.visibleItemsInfo.any { it.index == restoreIndex }
                if (!visible) {
                    gridState.scrollToItem(restoreIndex)
                }
                val restoreClaimId = videos[restoreIndex].claimId
                // Focus requests can race with grid item composition right after player close.
                repeat(5) {
                    requesterForVideo(restoreClaimId).requestFocus()
                    withFrameNanos { }
                }
                focusedGridIndex = restoreIndex
                onVideoFocused(restoreClaimId)
            }
        }
        pendingRestoreAfterClose = false
        pendingRestoreCategoryRequestId = ""
    }

    LaunchedEffect(inChannelView, activeChannelId, videos, loadingVideos) {
        if (!inChannelView || activeChannelId.isBlank()) {
            channelAutoFocusHandledForId = ""
            return@LaunchedEffect
        }
        if (loadingVideos || videos.isEmpty()) {
            return@LaunchedEffect
        }
        if (channelAutoFocusHandledForId == activeChannelId) {
            return@LaunchedEffect
        }

        gridState.scrollToItem(0)
        val firstClaimId = videos.first().claimId
        if (!requestFocusWhenReady(requesterForVideo(firstClaimId))) {
            return@LaunchedEffect
        }
        focusedGridIndex = 0
        onVideoFocused(firstClaimId)
        channelAutoFocusHandledForId = activeChannelId
    }

    LaunchedEffect(
        pendingSidebarRightCategoryId,
        selectedCategoryId,
        categories,
        videos,
        loadingVideos,
    ) {
        val pendingCategoryId = pendingSidebarRightCategoryId
        if (pendingCategoryId.isBlank()) {
            return@LaunchedEffect
        }
        if (categories.none { it.id == pendingCategoryId }) {
            pendingSidebarRightCategoryId = ""
            return@LaunchedEffect
        }
        if (pendingCategoryId != selectedCategoryId || loadingVideos) {
            return@LaunchedEffect
        }
        if (!focusFirstVideoCard()) {
            pendingSidebarRightCategoryId = ""
            return@LaunchedEffect
        }
        val firstClaimId = videos.firstOrNull()?.claimId.orEmpty()
        if (firstClaimId.isBlank()) {
            pendingSidebarRightCategoryId = ""
            return@LaunchedEffect
        }
        repeat(2) {
            withFrameNanos { }
            requestFocusWhenReady(requesterForVideo(firstClaimId), attempts = 2)
        }
        pendingSidebarRightCategoryId = ""
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LazyColumn(
            modifier = Modifier.width(168.dp),
            state = sidebarState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories, key = { it.id }) { category ->
                val active = category.id == selectedCategoryId
                var focused by remember(category.id) { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = when {
                                focused && active -> Color(0xFF3A2E70)
                                focused -> Color(0x552A2A4E)
                                active -> Color(0xFF2A2A4E)
                                else -> Color(0x33181824)
                            },
                            shape = MaterialTheme.shapes.medium
                        )
                        .border(
                            width = if (focused) 2.dp else if (active) 1.dp else 0.dp,
                            color = when {
                                focused -> Color(0xFF9D8CFF)
                                active -> Color(0x664F46E5)
                                else -> Color.Transparent
                            },
                            shape = MaterialTheme.shapes.medium,
                        )
                        .onFocusChanged { focusState ->
                            if (pendingRestoreAfterClose) {
                                focused = false
                                sidebarLongPressRefreshJob?.cancel()
                                sidebarLongPressRefreshJob = null
                                return@onFocusChanged
                            }
                            focused = focusState.isFocused
                            if (!focusState.isFocused) {
                                sidebarLongPressRefreshJob?.cancel()
                                sidebarLongPressRefreshJob = null
                                if (sidebarLongPressConsumedCategoryId == category.id) {
                                    sidebarLongPressConsumedCategoryId = ""
                                }
                            }
                            if (inChannelView) {
                                return@onFocusChanged
                            }
                            if (focusState.isFocused && !active) {
                                sidebarSelectionJob?.cancel()
                                sidebarSelectionJob = scope.launch {
                                    delay(SIDEBAR_FOCUS_DEBOUNCE_MS)
                                    onCategorySelected(category.id)
                                }
                            }
                        }
                        .onPreviewKeyEvent { keyEvent ->
                            val isCenterKey = keyEvent.key == Key.DirectionCenter ||
                                keyEvent.key == Key.Enter ||
                                keyEvent.key == Key.NumPadEnter
                            if (isCenterKey) {
                                when (keyEvent.type) {
                                    KeyEventType.KeyDown -> {
                                        if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                            sidebarLongPressConsumedCategoryId = ""
                                            sidebarLongPressRefreshJob?.cancel()
                                            sidebarLongPressRefreshJob = scope.launch {
                                                delay(SIDEBAR_REFRESH_LONG_PRESS_MS)
                                                if (!pendingRestoreAfterClose && focused) {
                                                    sidebarLongPressConsumedCategoryId = category.id
                                                    onRefreshCategory(category.id)
                                                }
                                            }
                                        }
                                        return@onPreviewKeyEvent false
                                    }
                                    KeyEventType.KeyUp -> {
                                        sidebarLongPressRefreshJob?.cancel()
                                        sidebarLongPressRefreshJob = null
                                        if (sidebarLongPressConsumedCategoryId == category.id) {
                                            sidebarLongPressConsumedCategoryId = ""
                                            return@onPreviewKeyEvent true
                                        }
                                        return@onPreviewKeyEvent false
                                    }
                                    else -> return@onPreviewKeyEvent false
                                }
                            }
                            if (
                                keyEvent.type == KeyEventType.KeyDown &&
                                keyEvent.key == Key.DirectionRight
                            ) {
                                if (pendingRestoreAfterClose) {
                                    return@onPreviewKeyEvent true
                                }
                                sidebarSelectionJob?.cancel()
                                pendingSidebarRightCategoryId = category.id
                                if (!active) {
                                    onCategorySelected(category.id)
                                    true
                                } else {
                                    focusFirstVideoCard()
                                    true
                                }
                            } else {
                                false
                            }
                        }
                        .focusRequester(requesterForCategory(category.id))
                        .focusable(enabled = !pendingRestoreAfterClose)
                        .clickable {
                            if (pendingRestoreAfterClose) {
                                return@clickable
                            }
                            if (sidebarLongPressConsumedCategoryId == category.id) {
                                sidebarLongPressConsumedCategoryId = ""
                                return@clickable
                            }
                            sidebarSelectionJob?.cancel()
                            onCategorySelected(category.id)
                        }
                        .padding(horizontal = 10.dp, vertical = 9.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryIcon(
                            categoryId = category.id,
                            categoryTitle = category.title,
                            active = active,
                        )
                        Text(
                            text = category.title,
                            color = Color.White,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (contentTitle.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = contentTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (signedIn && inChannelView && activeChannelId.isNotBlank()) {
                        HeaderButton(
                            label = when {
                                channelFollowBusy && channelFollowed -> "Unfollowing..."
                                channelFollowBusy -> "Following..."
                                channelFollowed -> "Unfollow"
                                else -> "Follow"
                            },
                            onClick = onToggleChannelFollow,
                            enabled = !channelFollowBusy,
                            maxWidth = 152.dp,
                        )
                    }
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val gridColumns = when {
                    maxWidth >= 1120.dp -> 4
                    else -> 3
                }
                when {
                    loadingCategories && categories.isEmpty() -> {
                        LoadingState("Loading categories...")
                    }

                    errorMessage.isNotBlank() && !loadingVideos && videos.isEmpty() -> {
                        ErrorState(message = errorMessage, onRetry = onRetry)
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridColumns),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            state = gridState,
                            modifier = Modifier
                                .fillMaxSize()
                                .onPreviewKeyEvent { keyEvent ->
                                    if (
                                        keyEvent.type == KeyEventType.KeyDown &&
                                        keyEvent.key == Key.DirectionLeft
                                    ) {
                                        if (focusedGridIndex < 0 || (focusedGridIndex % gridColumns) != 0) {
                                            return@onPreviewKeyEvent false
                                        }
                                        val selectedIndex = categories.indexOfFirst { it.id == selectedCategoryId }
                                        if (selectedIndex < 0) {
                                            return@onPreviewKeyEvent false
                                        }
                                        val selectedId = categories[selectedIndex].id
                                        scope.launch {
                                            val isSelectedVisible = sidebarState.layoutInfo.visibleItemsInfo
                                                .any { it.index == selectedIndex }
                                            if (!isSelectedVisible) {
                                                sidebarState.animateScrollToItem(selectedIndex)
                                            }
                                            requestFocusWhenReady(requesterForCategory(selectedId))
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            itemsIndexed(videos, key = { _, claim -> claim.claimId }) { index, claim ->
                                VideoCard(
                                    claim = claim,
                                    onClick = { onVideoSelected(claim) },
                                    onChannelClick = { onChannelSelected(claim) },
                                    onFocused = {
                                        focusedGridIndex = index
                                        onVideoFocused(claim.claimId)
                                    },
                                    modifier = Modifier.focusRequester(requesterForVideo(claim.claimId)),
                                )
                            }
                        }

                        if (loadingVideos && videos.isEmpty()) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(56.dp),
                                strokeWidth = 4.dp,
                                color = Color(0xFFB6ADFF),
                            )
                        }
                    }
                }
            }
        }
        }
    }

@Composable
private fun CategoryIcon(
    categoryId: String,
    categoryTitle: String,
    active: Boolean,
) {
    val context = LocalContext.current
    val iconColor = if (active) "#7F5CFF" else "#9CA3D2"
    val iconSvg = remember(categoryId, categoryTitle, iconColor) {
        CategoryIcons.iconSvg(categoryId, categoryTitle, iconColor)
    }
    val iconBytes = remember(iconSvg) {
        iconSvg.toByteArray(Charsets.UTF_8)
    }
    val request = remember(iconBytes, context) {
        ImageRequest.Builder(context)
            .data(iconBytes)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun LoadingState(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(strokeWidth = 2.dp)
            Text(label, color = Color.White)
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
) {
    val retryFocusRequester = remember { FocusRequester() }
    LaunchedEffect(onRetry) {
        if (onRetry != null) {
            repeat(4) {
                if (runCatching { retryFocusRequester.requestFocus() }.isSuccess) {
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text = message,
                color = Color(0xFFFF8A80),
                maxLines = 3,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                HeaderButton(
                    label = "Try Again",
                    onClick = onRetry,
                    maxWidth = 200.dp,
                    modifier = Modifier.focusRequester(retryFocusRequester),
                )
            }
        }
    }
}

@Composable
private fun VideoCard(
    claim: VideoClaim,
    onClick: () -> Unit,
    onChannelClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocused()
                }
            }
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFF7F5CFF) else Color(0x221E1E2A),
                shape = MaterialTheme.shapes.medium,
            )
            .background(
                color = if (focused) Color(0xFF21213A) else Color(0xFF171727),
                shape = MaterialTheme.shapes.medium,
            )
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF272742)),
        ) {
            if (claim.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = claim.thumbnailUrl,
                    contentDescription = claim.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (claim.durationSec > 0) {
                Text(
                    text = formatDuration(claim.durationSec),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color(0xAA000000), shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = claim.title.ifBlank { "Untitled" },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = claim.channelName.ifBlank { "Unknown channel" },
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = claim.channelClaimId.isNotBlank(), onClick = onChannelClick),
                )
                Text(
                    text = formatRelativeTime(claim.releaseTime),
                    color = Color(0xAAFFFFFF),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerScreen(
    video: VideoClaim?,
    streamUrl: String,
    streamGeneration: Long,
    resumePositionMs: Long,
    loading: Boolean,
    recommendationsLoading: Boolean,
    recommendedVideos: List<VideoClaim>,
    nextRecommendedVideo: VideoClaim?,
    signedIn: Boolean,
    reaction: String,
    reactionBusy: Boolean,
    errorMessage: String,
    onFireReaction: () -> Unit,
    onSlimeReaction: () -> Unit,
    onAutoplayNext: () -> Unit,
    onPlayRecommendation: (VideoClaim) -> Unit,
    onOpenChannel: () -> Unit,
    onPlaybackStarted: () -> Unit,
    onRecoverPlayback: (Long) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val latestOnClose by rememberUpdatedState(onClose)
    val latestOnPlaybackStarted by rememberUpdatedState(onPlaybackStarted)
    val latestOnRecoverPlayback by rememberUpdatedState(onRecoverPlayback)
    val latestOnAutoplayNext by rememberUpdatedState(onAutoplayNext)
    val latestOnPlayRecommendation by rememberUpdatedState(onPlayRecommendation)
    val channelFocusRequester = remember { FocusRequester() }
    val fireFocusRequester = remember { FocusRequester() }
    val slimeFocusRequester = remember { FocusRequester() }
    val autoplayPlayNowRequester = remember { FocusRequester() }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var headerFocused by remember { mutableStateOf(false) }
    var overlayVisible by remember(video?.claimId) { mutableStateOf(true) }
    var overlayActivityNonce by remember(video?.claimId) { mutableIntStateOf(0) }
    var closeTriggeredByEnd by remember(video?.claimId) { mutableStateOf(false) }
    var autoplayOverlayVisible by remember(video?.claimId) { mutableStateOf(false) }
    var autoplayCountdownSec by remember(video?.claimId) { mutableIntStateOf(PLAYER_AUTOPLAY_COUNTDOWN_SECONDS) }
    var autoplayHandledForVideo by remember(video?.claimId) { mutableStateOf(false) }
    var autoplayPauseUntilMs by remember(video?.claimId) { mutableLongStateOf(0L) }
    var timelinePositionMs by remember(video?.claimId) { mutableLongStateOf(0L) }
    var timelineDurationMs by remember(video?.claimId) { mutableLongStateOf(0L) }
    var timelineIsPlaying by remember(video?.claimId) { mutableStateOf(false) }
    var pendingHeaderFocusRequest by remember(video?.claimId) { mutableIntStateOf(0) }
    var bufferingSinceMs by remember(video?.claimId) { mutableLongStateOf(0L) }
    var bufferingActive by remember(video?.claimId) { mutableStateOf(false) }
    var bufferingIndicatorVisible by remember(video?.claimId) { mutableStateOf(false) }
    var lastRecoveryRequestAtMs by remember(video?.claimId) { mutableLongStateOf(0L) }

    DisposableEffect(activity) {
        val window = activity?.window
        val keepScreenOnFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val hadKeepScreenOn = window?.attributes?.flags?.and(keepScreenOnFlag) != 0
        if (!hadKeepScreenOn) {
            window?.addFlags(keepScreenOnFlag)
        }
        onDispose {
            if (!hadKeepScreenOn) {
                window?.clearFlags(keepScreenOnFlag)
            }
        }
    }

    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    DisposableEffect(player, video?.claimId) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !closeTriggeredByEnd) {
                    bufferingActive = false
                    bufferingIndicatorVisible = false
                    closeTriggeredByEnd = true
                    autoplayOverlayVisible = true
                    overlayVisible = true
                    headerFocused = false
                    return
                }
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (player.playWhenReady) {
                            bufferingActive = true
                            bufferingSinceMs = System.currentTimeMillis()
                        }
                    }
                    Player.STATE_READY,
                    Player.STATE_IDLE -> {
                        bufferingActive = false
                        bufferingIndicatorVisible = false
                        val durationMs = player.duration
                        timelineDurationMs = if (durationMs > 0L) durationMs else 0L
                        timelinePositionMs = player.currentPosition.coerceAtLeast(0L)
                        if (playbackState == Player.STATE_READY) {
                            closeTriggeredByEnd = false
                        }
                    }
                    Player.STATE_ENDED -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                bufferingActive = false
                bufferingIndicatorVisible = false
                val now = System.currentTimeMillis()
                if (now - lastRecoveryRequestAtMs < PLAYER_RECOVERY_COOLDOWN_MS) {
                    return
                }
                lastRecoveryRequestAtMs = now
                latestOnRecoverPlayback(player.currentPosition.coerceAtLeast(0L))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                timelineIsPlaying = isPlaying
                if (isPlaying) {
                    latestOnPlaybackStarted()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(streamUrl, streamGeneration) {
        if (streamUrl.isBlank()) {
            player.stop()
            player.clearMediaItems()
            timelinePositionMs = 0L
            timelineDurationMs = 0L
            timelineIsPlaying = false
        } else {
            val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl)
            inferMimeType(streamUrl)?.let(mediaItemBuilder::setMimeType)
            player.setMediaItem(mediaItemBuilder.build())
            player.prepare()
            if (resumePositionMs > 0L) {
                player.seekTo(resumePositionMs)
            }
            player.playWhenReady = true
            timelinePositionMs = resumePositionMs.coerceAtLeast(0L)
            timelineIsPlaying = true
        }
    }

    LaunchedEffect(video?.claimId) {
        headerFocused = false
        overlayVisible = true
        overlayActivityNonce += 1
        closeTriggeredByEnd = false
        autoplayOverlayVisible = false
        autoplayCountdownSec = PLAYER_AUTOPLAY_COUNTDOWN_SECONDS
        autoplayHandledForVideo = false
        autoplayPauseUntilMs = 0L
        timelinePositionMs = 0L
        timelineDurationMs = 0L
        timelineIsPlaying = false
        pendingHeaderFocusRequest = 0
    }

    LaunchedEffect(video?.claimId, playerViewRef, headerFocused) {
        if (!headerFocused) {
            playerViewRef?.requestFocus()
        }
    }

    LaunchedEffect(
        overlayVisible,
        overlayActivityNonce,
        headerFocused,
        loading,
        streamUrl,
        errorMessage,
    ) {
        val canAutoHide =
            overlayVisible &&
            !headerFocused &&
            !loading &&
            streamUrl.isNotBlank() &&
            errorMessage.isBlank()
        if (!canAutoHide) {
            return@LaunchedEffect
        }
        delay(PLAYER_OVERLAY_AUTO_HIDE_MS)
        if (!headerFocused) {
            overlayVisible = false
        }
    }

    LaunchedEffect(bufferingActive, loading, streamUrl, errorMessage) {
        val shouldConsiderShowing =
            bufferingActive &&
            !loading &&
            streamUrl.isNotBlank() &&
            errorMessage.isBlank()
        if (!shouldConsiderShowing) {
            bufferingIndicatorVisible = false
            return@LaunchedEffect
        }
        delay(PLAYER_BUFFERING_INDICATOR_DELAY_MS)
        if (bufferingActive && !loading && streamUrl.isNotBlank() && errorMessage.isBlank()) {
            bufferingIndicatorVisible = true
        }
    }

    LaunchedEffect(
        bufferingActive,
        bufferingSinceMs,
        streamGeneration,
        loading,
        streamUrl,
    ) {
        if (!bufferingActive || loading || streamUrl.isBlank()) {
            return@LaunchedEffect
        }
        delay(PLAYER_BUFFER_STALL_RECOVERY_MS)
        val now = System.currentTimeMillis()
        val shouldRecover =
            bufferingActive &&
            player.playWhenReady &&
            player.playbackState == Player.STATE_BUFFERING &&
            player.currentPosition >= PLAYER_RECOVERY_MIN_POSITION_MS &&
            (now - lastRecoveryRequestAtMs) >= PLAYER_RECOVERY_COOLDOWN_MS
        if (shouldRecover) {
            lastRecoveryRequestAtMs = now
            latestOnRecoverPlayback(player.currentPosition.coerceAtLeast(0L))
        }
    }

    LaunchedEffect(autoplayOverlayVisible, nextRecommendedVideo?.claimId) {
        if (!autoplayOverlayVisible || nextRecommendedVideo == null) {
            return@LaunchedEffect
        }
        delay(120)
        autoplayPlayNowRequester.requestFocus()
    }

    LaunchedEffect(
        autoplayOverlayVisible,
        nextRecommendedVideo?.claimId,
        recommendationsLoading,
        autoplayHandledForVideo,
        autoplayPauseUntilMs,
        headerFocused,
    ) {
        if (!autoplayOverlayVisible || autoplayHandledForVideo) {
            return@LaunchedEffect
        }
        if (recommendationsLoading) {
            return@LaunchedEffect
        }
        if (nextRecommendedVideo == null) {
            delay(1200)
            if (autoplayOverlayVisible && !recommendationsLoading && !autoplayHandledForVideo) {
                autoplayHandledForVideo = true
                latestOnClose()
            }
            return@LaunchedEffect
        }

        var remaining = PLAYER_AUTOPLAY_COUNTDOWN_SECONDS
        while (remaining >= 1) {
            if (!autoplayOverlayVisible || autoplayHandledForVideo) {
                return@LaunchedEffect
            }
            val paused = headerFocused || SystemClock.elapsedRealtime() < autoplayPauseUntilMs
            if (paused) {
                delay(200L)
                continue
            }
            autoplayCountdownSec = remaining
            delay(1000)
            remaining -= 1
        }
        if (autoplayOverlayVisible && !autoplayHandledForVideo) {
            autoplayHandledForVideo = true
            autoplayOverlayVisible = false
            latestOnAutoplayNext()
        }
    }

    LaunchedEffect(streamGeneration, streamUrl, autoplayOverlayVisible, headerFocused) {
        if (streamUrl.isBlank()) {
            return@LaunchedEffect
        }
        while (isActive && streamUrl.isNotBlank()) {
            val durationMs = player.duration
            timelineDurationMs = if (durationMs > 0L) durationMs else timelineDurationMs
            timelinePositionMs = player.currentPosition.coerceAtLeast(0L)
            timelineIsPlaying = player.isPlaying
            delay(250L)
        }
    }

    val hasChannelAction = !video?.channelName.isNullOrBlank() && !video?.channelClaimId.isNullOrBlank()
    val hasReactionActions = signedIn && video != null && video.claimId.isNotBlank()

    LaunchedEffect(
        pendingHeaderFocusRequest,
        overlayVisible,
        hasChannelAction,
        hasReactionActions,
    ) {
        if (pendingHeaderFocusRequest <= 0 || !overlayVisible || headerFocused) {
            return@LaunchedEffect
        }
        val targetRequester = when {
            hasChannelAction -> channelFocusRequester
            hasReactionActions -> fireFocusRequester
            else -> return@LaunchedEffect
        }
        repeat(2) {
            withFrameNanos { }
            if (runCatching { targetRequester.requestFocus() }.isSuccess) {
                headerFocused = true
                return@LaunchedEffect
            }
        }
    }

    fun focusHeaderActionsFromPlayback(): Boolean {
        if (headerFocused) {
            return true
        }
        if (!hasChannelAction && !hasReactionActions) {
            return true
        }
        pendingHeaderFocusRequest += 1
        return true
    }

    fun scrubBy(deltaMs: Long): Boolean {
        if (headerFocused || autoplayOverlayVisible || streamUrl.isBlank()) {
            return false
        }
        val current = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it > 0L } ?: timelineDurationMs
        val target = if (duration > 0L) {
            (current + deltaMs).coerceIn(0L, duration)
        } else {
            (current + deltaMs).coerceAtLeast(0L)
        }
        player.seekTo(target)
        timelinePositionMs = target
        overlayVisible = true
        overlayActivityNonce += 1
        return true
    }

    fun togglePlaybackFromTimeline(): Boolean {
        if (headerFocused || autoplayOverlayVisible || streamUrl.isBlank()) {
            return false
        }
        if (player.isPlaying) {
            player.pause()
            timelineIsPlaying = false
        } else {
            player.play()
            timelineIsPlaying = true
        }
        overlayVisible = true
        overlayActivityNonce += 1
        return true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                overlayVisible = true
                overlayActivityNonce += 1
                if (autoplayOverlayVisible) {
                    when (keyEvent.key) {
                        Key.DirectionUp,
                        Key.DirectionDown,
                        Key.DirectionLeft,
                        Key.DirectionRight,
                        Key.DirectionCenter,
                        Key.Enter,
                        Key.NumPadEnter -> {
                            autoplayPauseUntilMs = SystemClock.elapsedRealtime() + PLAYER_AUTOPLAY_INTERACTION_PAUSE_MS
                        }
                        else -> Unit
                    }
                }
                if (!headerFocused && !autoplayOverlayVisible) {
                    when (keyEvent.key) {
                        Key.DirectionLeft -> return@onPreviewKeyEvent scrubBy(-PLAYER_TIMELINE_SCRUB_MS)
                        Key.DirectionRight -> return@onPreviewKeyEvent scrubBy(PLAYER_TIMELINE_SCRUB_MS)
                        Key.DirectionCenter,
                        Key.Enter,
                        Key.NumPadEnter,
                        Key.Spacebar -> return@onPreviewKeyEvent togglePlaybackFromTimeline()
                        else -> Unit
                    }
                }
                when (keyEvent.key) {
                    Key.DirectionUp -> {
                        if (headerFocused) {
                            false
                        } else {
                            focusHeaderActionsFromPlayback()
                        }
                    }
                    Key.DirectionDown -> {
                        if (headerFocused) {
                            headerFocused = false
                            playerViewRef?.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
    ) {
        if (streamUrl.isNotBlank()) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = false
                        keepScreenOn = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    }
                },
                update = { playerView ->
                    playerView.player = player
                    playerView.keepScreenOn = true
                    playerViewRef = playerView
                    playerView.setOnKeyListener { _, keyCode, event ->
                        if (event.action != AndroidKeyEvent.ACTION_DOWN) {
                            return@setOnKeyListener false
                        }
                        overlayVisible = true
                        overlayActivityNonce += 1
                        if (!headerFocused && !autoplayOverlayVisible) {
                            when (keyCode) {
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> return@setOnKeyListener scrubBy(-PLAYER_TIMELINE_SCRUB_MS)
                                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> return@setOnKeyListener scrubBy(PLAYER_TIMELINE_SCRUB_MS)
                                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                                AndroidKeyEvent.KEYCODE_ENTER,
                                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                                AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                AndroidKeyEvent.KEYCODE_MEDIA_PLAY,
                                AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> return@setOnKeyListener togglePlaybackFromTimeline()
                            }
                        }
                        if (keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && !headerFocused) {
                            focusHeaderActionsFromPlayback()
                        } else {
                            false
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    loading -> LoadingState("Loading stream...")
                    errorMessage.isNotBlank() -> ErrorState(message = errorMessage, onRetry = onRetry)
                    else -> LoadingState("Preparing player...")
                }
            }
        }

        if (bufferingIndicatorVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC101018), shape = MaterialTheme.shapes.medium)
                    .border(1.dp, Color(0x664F46E5), shape = MaterialTheme.shapes.medium)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFB6ADFF),
                )
                Text(
                    text = "Buffering...",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (autoplayOverlayVisible) {
            val recommendationTiles = remember(recommendedVideos, nextRecommendedVideo?.claimId) {
                val output = mutableListOf<VideoClaim>()
                nextRecommendedVideo?.let(output::add)
                recommendedVideos.forEach { recommendation ->
                    if (output.size >= PLAYER_ENDSCREEN_RECOMMENDATION_COUNT) {
                        return@forEach
                    }
                    if (nextRecommendedVideo != null && recommendation.claimId == nextRecommendedVideo.claimId) {
                        return@forEach
                    }
                    output += recommendation
                }
                output
            }
            val autoplayCountdownPaused = headerFocused || SystemClock.elapsedRealtime() < autoplayPauseUntilMs
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .background(Color(0xE6171727), shape = MaterialTheme.shapes.large)
                    .border(1.dp, Color(0x664F46E5), shape = MaterialTheme.shapes.large)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .width(920.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Up Next",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                when {
                    recommendationsLoading -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFB6ADFF),
                            )
                            Text(
                                text = "Loading recommendations...",
                                color = Color(0xFFD9D5FF),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    recommendationTiles.isNotEmpty() -> {
                        Text(
                            text = if (autoplayCountdownPaused) {
                                "Autoplay paused while browsing recommendations"
                            } else {
                                "First recommendation autoplays in ${autoplayCountdownSec}s"
                            },
                            color = Color(0xFFD9D5FF),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            recommendationTiles.forEachIndexed { index, recommendation ->
                                val isAutoplayTarget =
                                    nextRecommendedVideo != null &&
                                        recommendation.claimId == nextRecommendedVideo.claimId
                                EndscreenRecommendationCard(
                                    claim = recommendation,
                                    autoplayTarget = isAutoplayTarget,
                                    countdownSec = if (isAutoplayTarget) autoplayCountdownSec else null,
                                    modifier = if (index == 0) {
                                        Modifier
                                            .weight(1f)
                                            .focusRequester(autoplayPlayNowRequester)
                                    } else {
                                        Modifier.weight(1f)
                                    },
                                    onClick = {
                                        if (autoplayHandledForVideo) {
                                            return@EndscreenRecommendationCard
                                        }
                                        autoplayHandledForVideo = true
                                        autoplayOverlayVisible = false
                                        if (isAutoplayTarget) {
                                            latestOnAutoplayNext()
                                        } else {
                                            latestOnPlayRecommendation(recommendation)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = "No recommendations found.",
                            color = Color(0xFFD9D5FF),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        if (streamUrl.isNotBlank() && !loading && !autoplayOverlayVisible && overlayVisible) {
            PlayerTimelineBar(
                positionMs = timelinePositionMs,
                durationMs = timelineDurationMs,
                isPlaying = timelineIsPlaying,
                focused = !headerFocused,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }

        if (overlayVisible || headerFocused || loading || streamUrl.isBlank() || errorMessage.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video?.title?.ifBlank { "Odysee" } ?: "Odysee",
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasChannelAction) {
                            var focused by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier
                                    .focusRequester(channelFocusRequester)
                                    .focusProperties {
                                        right = fireFocusRequester
                                    }
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            headerFocused = true
                                            overlayVisible = true
                                            overlayActivityNonce += 1
                                        }
                                        focused = it.isFocused
                                    }
                                    .border(
                                        width = if (focused) 2.dp else 1.dp,
                                        color = if (focused) Color(0xFF9D8CFF) else Color(0x662A2A4E),
                                        shape = MaterialTheme.shapes.small,
                                    )
                                    .background(
                                        if (focused) Color(0x992A2A4E) else Color(0x552A2A4E),
                                        shape = MaterialTheme.shapes.small,
                                    )
                                    .focusable()
                                    .clickable(onClick = onOpenChannel)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (!video?.channelAvatarUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = video?.channelAvatarUrl,
                                        contentDescription = video?.channelName,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                Text(
                                    text = video?.channelName.orEmpty(),
                                    color = Color(0xFFE1DEFF),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (hasReactionActions) {
                            ReactionPill(
                                label = "Fire",
                                active = reaction == "like",
                                busy = reactionBusy,
                                iconAssetPath = "reactions/fire.svg",
                                activeIconColor = Color(0xFFF97316),
                                modifier = Modifier
                                    .focusRequester(fireFocusRequester)
                                    .focusProperties {
                                        if (hasChannelAction) {
                                            left = channelFocusRequester
                                        }
                                        right = slimeFocusRequester
                                    },
                                onFocused = {
                                    headerFocused = true
                                    overlayVisible = true
                                    overlayActivityNonce += 1
                                },
                                onClick = onFireReaction,
                            )
                            ReactionPill(
                                label = "Slime",
                                active = reaction == "dislike",
                                busy = reactionBusy,
                                iconAssetPath = "reactions/slime.svg",
                                activeIconColor = Color(0xFF84CC16),
                                modifier = Modifier
                                    .focusRequester(slimeFocusRequester)
                                    .focusProperties {
                                        left = fireFocusRequester
                                    },
                                onFocused = {
                                    headerFocused = true
                                    overlayVisible = true
                                    overlayActivityNonce += 1
                                },
                                onClick = onSlimeReaction,
                            )
                        }
                    }
                }
                Text(
                    text = "Back to close",
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun EndscreenRecommendationCard(
    claim: VideoClaim,
    autoplayTarget: Boolean,
    countdownSec: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember(claim.claimId) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> Color(0xFF9D8CFF)
                    autoplayTarget -> Color(0xB37F5CFF)
                    else -> Color(0x332A2A4E)
                },
                shape = MaterialTheme.shapes.medium,
            )
            .background(Color(0xFF151526), shape = MaterialTheme.shapes.medium)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF252542)),
        ) {
            if (claim.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = claim.thumbnailUrl,
                    contentDescription = claim.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (autoplayTarget) {
                Text(
                    text = if (countdownSec != null) "Autoplay ${countdownSec}s" else "Autoplay",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xCC7F5CFF), shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                text = claim.title.ifBlank { "Untitled" },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = claim.channelName.ifBlank { "Unknown channel" },
                color = Color(0xCCFFFFFF),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PlayerTimelineBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val safeDurationMs = durationMs.coerceAtLeast(0L)
    val safePositionMs = if (safeDurationMs > 0L) {
        positionMs.coerceIn(0L, safeDurationMs)
    } else {
        positionMs.coerceAtLeast(0L)
    }
    val progress = if (safeDurationMs > 0L) {
        safePositionMs.toFloat() / safeDurationMs.toFloat()
    } else {
        0f
    }.coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xB3151525), shape = MaterialTheme.shapes.medium)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFF9D8CFF) else Color(0x553A3A64),
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isPlaying) "Playing" else "Paused",
            color = Color(0xFFDAD6FF),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
        Text(
            text = formatDuration((safePositionMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(Color(0x553A3A64), shape = MaterialTheme.shapes.small),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(8.dp)
                    .background(Color(0xFF7F5CFF), shape = MaterialTheme.shapes.small),
            )
        }
        Text(
            text = if (safeDurationMs > 0L) {
                formatDuration((safeDurationMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } else {
                "--:--"
            },
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReactionPill(
    label: String,
    active: Boolean,
    busy: Boolean,
    iconAssetPath: String,
    activeIconColor: Color,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val iconRequest = remember(iconAssetPath, context) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/$iconAssetPath")
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    var focused by remember { mutableStateOf(false) }
    val background = when {
        active -> Color(0xFF7F5CFF)
        focused -> Color(0x992A2A4E)
        else -> Color(0x552A2A4E)
    }
    val iconColor = if (active) activeIconColor else Color(0xFFE6EDF8)
    Row(
        modifier = modifier
            .onFocusChanged {
                if (it.isFocused) {
                    onFocused()
                }
                focused = it.isFocused
            }
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFF9D8CFF) else Color(0x662A2A4E),
                shape = MaterialTheme.shapes.small,
            )
            .background(background, shape = MaterialTheme.shapes.small)
            .focusable()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AsyncImage(
            model = iconRequest,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(iconColor),
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = Color.White,
            )
        }
    }
}

private data class ConfettiParticle(
    val startXFraction: Float,
    val startDelayMs: Float,
    val travelDurationMs: Float,
    val sizePx: Float,
    val driftAmplitudePx: Float,
    val swayPhase: Float,
    val color: Color,
)

@Composable
private fun ConfettiOverlay(
    burstNonce: Int,
    modifier: Modifier = Modifier,
) {
    val particles = remember(burstNonce) {
        val random = Random(SystemClock.elapsedRealtime() + burstNonce)
        List(CONFETTI_PARTICLE_COUNT) {
            ConfettiParticle(
                startXFraction = random.nextFloat(),
                startDelayMs = random.nextFloat() * KONAMI_CONFETTI_STAGGER_MS,
                travelDurationMs = KONAMI_CONFETTI_FALL_MIN_MS + (random.nextFloat() * (KONAMI_CONFETTI_FALL_MAX_MS - KONAMI_CONFETTI_FALL_MIN_MS)),
                sizePx = 5f + random.nextFloat() * 8f,
                driftAmplitudePx = 10f + random.nextFloat() * 28f,
                swayPhase = random.nextFloat() * 6.283185f,
                color = KONAMI_CONFETTI_COLORS[random.nextInt(KONAMI_CONFETTI_COLORS.size)],
            )
        }
    }
    var elapsedMs by remember(burstNonce) { mutableFloatStateOf(0f) }
    LaunchedEffect(burstNonce) {
        if (burstNonce <= 0) {
            return@LaunchedEffect
        }
        val startMs = SystemClock.elapsedRealtime()
        while (true) {
            val nowMs = SystemClock.elapsedRealtime()
            val elapsed = (nowMs - startMs).toFloat()
            elapsedMs = elapsed
            if (elapsed >= KONAMI_CONFETTI_VISIBLE_MS) {
                break
            }
            withFrameNanos { }
        }
    }

    Canvas(modifier = modifier) {
        val maxY = size.height * 1.15f
        particles.forEach { particle ->
            val localElapsed = elapsedMs - particle.startDelayMs
            if (localElapsed <= 0f) {
                return@forEach
            }
            val p = (localElapsed / particle.travelDurationMs).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) {
                return@forEach
            }
            val baseX = particle.startXFraction * size.width
            val swayX = sin((p * 8f) + particle.swayPhase) * particle.driftAmplitudePx
            val x = (baseX + swayX).coerceIn(0f, size.width)
            val y = (-18f + (p * maxY))
            val alpha = when {
                p < 0.12f -> (p / 0.12f)
                p > 0.88f -> ((1f - p) / 0.12f)
                else -> 1f
            }.coerceIn(0f, 1f)
            drawCircle(
                color = particle.color.copy(alpha = alpha),
                radius = particle.sizePx,
                center = Offset(x, y),
            )
        }
    }
}

@Composable
private fun SearchDialog(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val modalSurface = Color(0xFF171727)
    val modalOutline = Color(0xFF2A2A4E)
    val accent = Color(0xFF7F5CFF)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = modalSurface,
        title = {
            Text(
                text = "Search",
                color = Color.White,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search query", color = Color(0xFF9CA3D2)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = modalOutline,
                    focusedLabelColor = Color(0xFFC7C3FF),
                    unfocusedLabelColor = Color(0xFF9CA3D2),
                    cursorColor = Color.White,
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = query.trim().isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Color.White,
                ),
            ) {
                Text("Search")
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2A2A4E),
                    contentColor = Color.White,
                ),
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AuthDialog(
    email: String,
    password: String,
    isBusy: Boolean,
    awaitingVerification: Boolean,
    signedIn: Boolean,
    signedInEmail: String,
    signedInChannelName: String,
    signedInAvatarUrl: String,
    switchChannels: List<AuthChannel>,
    switchChannelLoading: Boolean,
    selectedSwitchChannelId: String,
    message: String,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSignInWithPassword: () -> Unit,
    onSendMagicLink: () -> Unit,
    onSwitchChannel: (String) -> Unit,
    onOpenMyChannel: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val modalSurface = Color(0xFF171727)
    val modalOutline = Color(0xFF2A2A4E)
    val accent = Color(0xFF7F5CFF)
    val mutedText = Color(0xCCFFFFFF)
    val channelLabel = normalizeHeaderChannelLabel(signedInChannelName)
    var activeInput by remember { mutableStateOf(AuthDialogInput.NONE) }

    LaunchedEffect(isBusy, awaitingVerification, signedIn) {
        if (isBusy || awaitingVerification || signedIn) {
            activeInput = AuthDialogInput.NONE
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = modalSurface,
        titleContentColor = Color.White,
        textContentColor = mutedText,
        title = {
            Text(
                text = if (signedIn) "Account" else "Sign In",
                color = Color.White,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!signedIn) {
                    TvAuthTextField(
                        value = email,
                        onValueChange = onEmailChanged,
                        label = "Email",
                        enabled = !isBusy,
                        isEditing = activeInput == AuthDialogInput.EMAIL,
                        onEditingChange = { editing ->
                            activeInput = if (editing) AuthDialogInput.EMAIL else AuthDialogInput.NONE
                        },
                        mutedLabel = Color(0xFFB8B8D9),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            disabledTextColor = Color(0x88FFFFFF),
                            focusedBorderColor = accent,
                            unfocusedBorderColor = modalOutline,
                            disabledBorderColor = Color(0x662A2A4E),
                            focusedLabelColor = Color(0xFFC7C3FF),
                            unfocusedLabelColor = Color(0xFF9CA3D2),
                            disabledLabelColor = Color(0x779CA3D2),
                            cursorColor = Color.White,
                        ),
                    )
                    TvAuthTextField(
                        value = password,
                        onValueChange = onPasswordChanged,
                        label = "Password",
                        enabled = !isBusy,
                        isEditing = activeInput == AuthDialogInput.PASSWORD,
                        onEditingChange = { editing ->
                            activeInput = if (editing) AuthDialogInput.PASSWORD else AuthDialogInput.NONE
                        },
                        mutedLabel = Color(0xFFB8B8D9),
                        visualTransformation = PasswordVisualTransformation(mask = '*'),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            disabledTextColor = Color(0x88FFFFFF),
                            focusedBorderColor = accent,
                            unfocusedBorderColor = modalOutline,
                            disabledBorderColor = Color(0x662A2A4E),
                            focusedLabelColor = Color(0xFFC7C3FF),
                            unfocusedLabelColor = Color(0xFF9CA3D2),
                            disabledLabelColor = Color(0x779CA3D2),
                            cursorColor = Color.White,
                        ),
                    )
                    Text(
                        text = "Press Select on a field to edit. You can also request a magic link.",
                        color = mutedText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (signedIn) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x332A2A4E), shape = MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (signedInAvatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = signedInAvatarUrl,
                                contentDescription = channelLabel,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF3A3A55)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = channelLabel.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = channelLabel,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (signedInEmail.isNotBlank()) {
                                Text(
                                    text = signedInEmail,
                                    color = Color(0xCCFFFFFF),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Text(
                        text = "Switch default channel",
                        color = Color(0xFFB8B8D9),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    when {
                        switchChannelLoading -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(16.dp),
                                    strokeWidth = 2.dp,
                                    color = accent,
                                )
                                Text("Loading channels...", color = mutedText)
                            }
                        }
                        switchChannels.isNotEmpty() -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(184.dp)
                                    .border(1.dp, Color(0x442A2A4E), MaterialTheme.shapes.small)
                                    .padding(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(switchChannels, key = { it.channelId }) { channel ->
                                    val selected = channel.channelId == selectedSwitchChannelId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (selected) Color(0x552A2A4E) else Color(0x22171727),
                                                shape = MaterialTheme.shapes.small,
                                            )
                                            .border(
                                                width = if (selected) 1.dp else 0.dp,
                                                color = if (selected) Color(0xFF7F5CFF) else Color.Transparent,
                                                shape = MaterialTheme.shapes.small,
                                            )
                                            .clickable(
                                                enabled = !isBusy && !selected,
                                                onClick = { onSwitchChannel(channel.channelId) }
                                            )
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (channel.channelAvatarUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = channel.channelAvatarUrl,
                                                contentDescription = channel.channelName,
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape),
                                                contentScale = ContentScale.Crop,
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF3A3A55)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = channel.channelName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
                                        Text(
                                            text = channel.channelName,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (selected) {
                                            Text(
                                                text = "Default",
                                                color = Color(0xFFD5CCFF),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            Text(
                                text = "No channels found for this account.",
                                color = mutedText,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        color = mutedText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (isBusy || awaitingVerification) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(22.dp),
                                strokeWidth = 2.dp,
                                color = accent,
                            )
                            Text(
                                text = if (awaitingVerification) "Waiting for email confirmation..." else "Working...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = mutedText,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!signedIn) {
                Button(
                    onClick = {
                        activeInput = AuthDialogInput.NONE
                        onSignInWithPassword()
                    },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF3A3A55),
                        disabledContentColor = Color(0x99FFFFFF),
                    ),
                ) {
                    Text("Sign In")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!signedIn) {
                    Button(
                        onClick = {
                            activeInput = AuthDialogInput.NONE
                            onSendMagicLink()
                        },
                        enabled = !isBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2A2A4E),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF23233D),
                            disabledContentColor = Color(0x99FFFFFF),
                        ),
                    ) {
                        Text("Send Link")
                    }
                } else {
                    Button(
                        onClick = onOpenMyChannel,
                        enabled = !isBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2A2A4E),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF23233D),
                            disabledContentColor = Color(0x99FFFFFF),
                        ),
                    ) {
                        Text("View Channel")
                    }
                    Button(
                        onClick = onSignOut,
                        enabled = !isBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFB91C1C),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF5A2B2B),
                            disabledContentColor = Color(0x99FFFFFF),
                        ),
                    ) {
                        Text("Sign Out")
                    }
                }
                Button(
                    onClick = onDismiss,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2A2A4E),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF23233D),
                        disabledContentColor = Color(0x99FFFFFF),
                    ),
                ) {
                    Text("Close")
                }
            }
        },
    )
}

private enum class AuthDialogInput {
    NONE,
    EMAIL,
    PASSWORD,
}

@Composable
private fun TvAuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    isEditing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    mutedLabel: Color,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    colors: androidx.compose.material3.TextFieldColors,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(isEditing, hasFocus, enabled) {
        if (!enabled || !isEditing) {
            keyboardController?.hide()
            return@LaunchedEffect
        }
        if (!hasFocus) {
            return@LaunchedEffect
        }
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged {
                hasFocus = it.isFocused
                if (!it.isFocused && isEditing) {
                    onEditingChange(false)
                }
            }
            .onPreviewKeyEvent { keyEvent ->
                if (
                    enabled &&
                    !isEditing &&
                    keyEvent.type == KeyEventType.KeyDown &&
                    isSelectKey(keyEvent.key)
                ) {
                    onEditingChange(true)
                    true
                } else {
                    false
                }
            },
        enabled = enabled,
        readOnly = !isEditing,
        singleLine = true,
        label = { Text(label, color = mutedLabel) },
        visualTransformation = visualTransformation,
        colors = colors,
    )
}

private fun isSelectKey(key: Key): Boolean {
    return key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
}

private fun normalizeHeaderChannelLabel(rawName: String): String {
    val trimmed = rawName.trim()
    if (trimmed.isBlank() || looksLikeEmail(trimmed)) {
        return "My Channel"
    }
    return trimmed
}

private fun looksLikeEmail(value: String): Boolean {
    return EMAIL_REGEX.matches(value.trim())
}

private fun formatDuration(totalSeconds: Int): String {
    if (totalSeconds <= 0) return ""
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun formatRelativeTime(releaseTimeEpochSeconds: Long): String {
    if (releaseTimeEpochSeconds <= 0) return ""
    val nowSec = System.currentTimeMillis() / 1000
    val diff = abs(nowSec - releaseTimeEpochSeconds)

    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86_400 -> "${diff / 3600}h ago"
        diff < 2_592_000 -> "${diff / 86_400}d ago"
        diff < 31_104_000 -> "${diff / 2_592_000}mo ago"
        else -> "${diff / 31_104_000}y ago"
    }
}

private fun inferMimeType(urlRaw: String): String? {
    val url = urlRaw.lowercase()
    return when {
        url.contains(".m3u8") || url.contains("master.m3u8") || url.contains("playlist.m3u8") ->
            MimeTypes.APPLICATION_M3U8
        url.contains(".mpd") -> MimeTypes.APPLICATION_MPD
        url.contains(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }
}

private fun mapKonamiInput(key: Key): KonamiInput? {
    return when (key) {
        Key.DirectionUp -> KonamiInput.Up
        Key.DirectionDown -> KonamiInput.Down
        Key.DirectionLeft -> KonamiInput.Left
        Key.DirectionRight -> KonamiInput.Right
        else -> null
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current
        }
        current = current.baseContext
    }
    return null
}

private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
private const val STARTUP_SPLASH_MIN_VISIBLE_MS = 1400L
private const val STARTUP_SPLASH_MAX_VISIBLE_MS = 4200L
private const val LOAD_MORE_PREFETCH_ITEMS = 6
private const val SIDEBAR_FOCUS_DEBOUNCE_MS = 150L
private const val SIDEBAR_REFRESH_LONG_PRESS_MS = 2000L
private const val PLAYER_OVERLAY_AUTO_HIDE_MS = 3500L
private const val PLAYER_BUFFER_STALL_RECOVERY_MS = 12000L
private const val PLAYER_RECOVERY_COOLDOWN_MS = 10000L
private const val PLAYER_RECOVERY_MIN_POSITION_MS = 30_000L
private const val PLAYER_BUFFERING_INDICATOR_DELAY_MS = 700L
private const val PLAYER_TIMELINE_SCRUB_MS = 10_000L
private const val PLAYER_AUTOPLAY_INTERACTION_PAUSE_MS = 3_000L
private const val PLAYER_AUTOPLAY_COUNTDOWN_SECONDS = 7
private const val PLAYER_ENDSCREEN_RECOMMENDATION_COUNT = 4
private const val KONAMI_CONFETTI_VISIBLE_MS = 1800L
private const val KONAMI_CONFETTI_STAGGER_MS = 320f
private const val KONAMI_CONFETTI_FALL_MIN_MS = 1000f
private const val KONAMI_CONFETTI_FALL_MAX_MS = 1500f
private const val CONFETTI_PARTICLE_COUNT = 120
private enum class KonamiInput { Up, Down, Left, Right }
private val KONAMI_STEPS = listOf(
    setOf(KonamiInput.Up),
    setOf(KonamiInput.Up),
    setOf(KonamiInput.Down),
    setOf(KonamiInput.Down),
    setOf(KonamiInput.Left),
    setOf(KonamiInput.Right),
    setOf(KonamiInput.Left),
    setOf(KonamiInput.Right),
)
private val KONAMI_CONFETTI_COLORS = listOf(
    Color(0xFFF97316),
    Color(0xFF84CC16),
    Color(0xFF38BDF8),
    Color(0xFFFDE047),
    Color(0xFFF472B6),
    Color(0xFF7F5CFF),
)
