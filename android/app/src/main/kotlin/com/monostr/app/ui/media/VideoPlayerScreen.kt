package com.monostr.app.ui.media

import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.annotation.OptIn
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.monostr.app.R
import com.monostr.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import okhttp3.OkHttpClient
import javax.inject.Inject

/** Hands the screen the app's gated client. */
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(val http: OkHttpClient) : ViewModel()

/**
 * Spec 3.3: fullscreen ExoPlayer with media3 controls; the player is released when the screen leaves. All its HTTP goes through
 * [http] (Tor spec 7.2), and no other source exists: the default one also opens udp:// and the platform's own HTTP stack. Redirects
 * stay on their scheme, as they did with that stack; `newBuilder()` keeps the gate.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(url: String, onBack: () -> Unit, http: OkHttpClient = hiltViewModel<VideoPlayerViewModel>().http) {
    AllowRotation()
    val context = LocalContext.current
    val player = remember(url) {
        val source = OkHttpDataSource.Factory(http.newBuilder().followSslRedirects(false).build())
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
            playWhenReady = true
            // owner 2026-11-19: the mute choice is remembered on the device
            volume = if (VideoControls.muted(context)) 0f else 1f
        }
    }
    var muted by remember { mutableStateOf(VideoControls.muted(context)) }
    // owner 2026-11-19: close and mute fade with media3's controls (a tap shows them all), not stay over the picture
    var controlsShown by remember { mutableStateOf(true) }
    // a double tap's ±10 s shows briefly as an icon in its half; the stamp restarts the hide timer
    var seekShown by remember { mutableStateOf<Boolean?>(null) }
    var seekStamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(seekStamp) { if (seekStamp != 0L) { kotlinx.coroutines.delay(600); seekShown = null } }
    DisposableEffect(player) {
        // clips up to a minute loop like on X; longer ones stop at the end
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                player.repeatMode = if (VideoControls.loops(player.duration)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // owner 2026-11-19: media3's fullscreen button turns to landscape (also with auto-rotate off) and hides the system
    // bars; tapping it again, back, or leaving the screen undoes both (AllowRotation, declared first, restores last)
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // true when the button asked for fullscreen (landscape held fixed); false when turning the phone did (turning back ends it)
    var locked by rememberSaveable { mutableStateOf(false) }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(landscape) {
        // owner 2026-11-19: turning the phone sideways is fullscreen too, and turning it back ends it
        if (landscape && !fullscreen) { locked = false; fullscreen = true } else if (!landscape && fullscreen && !locked) fullscreen = false
    }
    // set when the player screen goes: a pending «upright again» must then not free the rotation of the screens behind it
    val closed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { closed.set(true) } }
    val activity = context.findActivity()
    DisposableEffect(activity, fullscreen) {
        // the value this effect was set up for: `fullscreen` read in onDispose would already be the new one
        val on = fullscreen
        val fixed = locked
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (on) {
            if (fixed) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (on) {
                bars?.show(WindowInsetsCompat.Type.systemBars())
                // owner 2026-11-19: leaving fullscreen goes back to portrait at once, even with the phone held sideways;
                // free rotation (AllowRotation) returns once the phone is upright again
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity?.let { a ->
                    object : android.view.OrientationEventListener(a) {
                        override fun onOrientationChanged(degrees: Int) {
                            if (degrees == ORIENTATION_UNKNOWN || (degrees in 31..329)) return
                            disable()
                            if (!closed.get() && a.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                        }
                    }.takeIf { it.canDetectOrientation() }?.enable()
                        ?: run { if (!closed.get()) a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER }
                }
            }
        }
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        var wasPlaying = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasPlaying = player.isPlaying
                    player.pause()
                }
                Lifecycle.Event.ON_RESUME -> if (wasPlaying) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("video-player")) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    // media3's animated hide leaves a progress bar behind in landscape and counts as still visible; without the
                    // animation the controls go at once, and close/mute go with them (owner 2026-11-19)
                    setControllerAnimationEnabled(false)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controlsShown = v == View.VISIBLE })
                    post { controlsShown = isControllerFullyVisible }
                    // owner 2026-11-19: the screen dimmed after a minute; it stays on while the video plays, not when paused
                    val view = this
                    player.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) { view.keepScreenOn = isPlaying }
                    })
                    keepScreenOn = player.isPlaying
                    // double tap left/right: ±10 s; single taps still reach PlayerView (controls show/hide), buttons are children
                    val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            val forward = e.x > view.width / 2f
                            player.seekTo(VideoControls.seekTarget(player.currentPosition, player.duration, forward))
                            seekShown = forward
                            seekStamp = System.nanoTime()
                            return true
                        }
                    })
                    @Suppress("ClickableViewAccessibility") // a gesture on top of PlayerView's own click handling
                    setOnTouchListener { _, event -> taps.onTouchEvent(event); false }
                }
            },
            update = { it.player = player },
            // owner 2026-11-19: the controls sat behind the navigation bar; the bars' insets are 0 while hidden in fullscreen
            modifier = Modifier.fillMaxSize().navigationBarsPadding().displayCutoutPadding(),
        )
        seekShown?.let { forward ->
            Icon(
                if (forward) Icons.Filled.Forward10 else Icons.Filled.Replay10, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart).padding(Spacing.xxl).size(48.dp)
                    .background(Color.Black.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape).padding(Spacing.s),
            )
        }
        AnimatedVisibility(controlsShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Spacing.s).testTag("video-close")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.media_viewer_close), tint = Color.White)
                }
                // owner 2026-11-19: our own fullscreen button (media3's lost its state after the turn and did not go back)
                Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(Spacing.s)) {
                    IconButton(onClick = { if (!fullscreen) locked = true; fullscreen = !fullscreen }, modifier = Modifier.testTag("video-fullscreen")) {
                        Icon(
                            if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = stringResource(if (fullscreen) R.string.media_video_fullscreen_exit else R.string.media_video_fullscreen), tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = { muted = !muted; player.volume = if (muted) 0f else 1f; VideoControls.setMuted(context, muted) },
                        modifier = Modifier.testTag("video-mute"),
                    ) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(if (muted) R.string.media_video_unmute else R.string.media_video_mute), tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
