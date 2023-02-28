package com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views

import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.carl.youtbeview.R
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.YouTubePlayerCallback
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.YouTubePlayerFullScreenListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.YouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.utils.FullScreenHelper
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.utils.NetworkListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.utils.PlaybackResumer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.utils.SimplexToast
import com.pierfrancescosoffritti.androidyoutubeplayer.core.ui.DefaultPlayerUiController
import com.pierfrancescosoffritti.androidyoutubeplayer.core.ui.PlayerUiController

internal class LegacyYouTubePlayerView(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0):
        SixteenByNineFrameLayout(context, attrs, defStyleAttr), LifecycleObserver {

    constructor(context: Context): this(context, null, 0)
    constructor(context: Context, attrs: AttributeSet? = null): this(context, attrs, 0)

    private val TAG = "LegacyYouTubePlayerView"

    internal  var youTubePlayer :WebViewYouTubePlayer? = null
    private lateinit var defaultPlayerUiController: DefaultPlayerUiController

    private val networkListener = NetworkListener()
    private val playbackResumer = PlaybackResumer()
    private val fullScreenHelper = FullScreenHelper(this)

    internal var isYouTubePlayerReady = false
    private var initialize = { }
    private val youTubePlayerCallbacks = HashSet<YouTubePlayerCallback>()

    internal var canPlay = true
        private set

    var isUsingCustomUi = false
        private set

    init {
        init()
    }
    var isException  = false
    private fun init(){
        try {
            youTubePlayer = WebViewYouTubePlayer(context)
            youTubePlayer?.let {
                addView(
                    youTubePlayer,
                    LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                defaultPlayerUiController = DefaultPlayerUiController(this, it)

                fullScreenHelper.addFullScreenListener(defaultPlayerUiController)

                it.addListener(defaultPlayerUiController)
                it.addListener(playbackResumer)

                // stop playing if the user loads a video but then leaves the app before the video starts playing.
                it.addListener(object : AbstractYouTubePlayerListener() {
                    override fun onStateChange(
                        youTubePlayer: YouTubePlayer,
                        state: PlayerConstants.PlayerState
                    ) {
                        if (state == PlayerConstants.PlayerState.PLAYING && !isEligibleForPlayback())
                            youTubePlayer.pause()
                    }
                })

                it.addListener(object : AbstractYouTubePlayerListener() {
                    override fun onReady(youTubePlayer: YouTubePlayer) {
                        isYouTubePlayerReady = true

                        youTubePlayerCallbacks.forEach { it.onYouTubePlayer(youTubePlayer) }
                        youTubePlayerCallbacks.clear()

                        youTubePlayer.removeListener(this)
                    }
                })

                networkListener.onNetworkAvailable = {
                    if (!isYouTubePlayerReady)
                        initialize()
                    else
                        playbackResumer.resume(it)
                }
            }
            isException = false
        } catch (e: Exception) {
            val messageCause = if (e.cause == null) e.toString() else e.cause.toString()
            val trace = Log.getStackTraceString(e)
            if (trace.contains("android.content.pm.PackageManager\$NameNotFoundException")
                || trace.contains("java.lang.RuntimeException: Cannot load WebView")
                || trace.contains("android.webkit.WebViewFactory\$MissingWebViewPackageException: Failed to load WebView provider: No WebView installed")
            ) {
                isException = true
                SimplexToast.show(context, "WebView widget not installed or broken.Please reinstall it before playing the video.")
            }
        }
    }

    /**
     * Initialize the player. You must call this method before using the player.
     * @param youTubePlayerListener listener for player events
     * @param handleNetworkEvents if set to true a broadcast receiver will be registered and network events will be handled automatically.
     * If set to false, you should handle network events with your own broadcast receiver.
     * @param playerOptions customizable options for the embedded video player, can be null.
     */
    fun initialize(youTubePlayerListener: YouTubePlayerListener, handleNetworkEvents: Boolean, playerOptions: IFramePlayerOptions?) {
        if(isException){
            return
        }
        if(isYouTubePlayerReady)
            throw IllegalStateException("This YouTubePlayerView has already been initialized.")

        if (handleNetworkEvents)
            context.registerReceiver(networkListener, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))

        initialize = {
            youTubePlayer?.initialize({it.addListener(youTubePlayerListener)}, playerOptions)
        }

        if(!handleNetworkEvents)
            initialize()
    }

    /**
     * Initialize the player.
     * @param handleNetworkEvents if set to true a broadcast receiver will be registered and network events will be handled automatically.
     * If set to false, you should handle network events with your own broadcast receiver.
     *
     * @see LegacyYouTubePlayerView.initialize
     */
    fun initialize(youTubePlayerListener: YouTubePlayerListener, handleNetworkEvents: Boolean) =
            initialize(youTubePlayerListener, handleNetworkEvents, null)

    /**
     * Initialize the player. Network events are automatically handled by the player.
     * @param youTubePlayerListener listener for player events
     *
     * @see LegacyYouTubePlayerView.initialize
     */
    fun initialize(youTubePlayerListener: YouTubePlayerListener) =
            initialize(youTubePlayerListener, true)

    /**
     * Initialize a player using the web-base Ui instead pf the native Ui.
     * The default PlayerUiController will be removed and [LegacyYouTubePlayerView.getPlayerUiController] will throw exception.
     *
     * @see LegacyYouTubePlayerView.initialize
     */
    fun initializeWithWebUi(youTubePlayerListener: YouTubePlayerListener, handleNetworkEvents: Boolean) {
        val iFramePlayerOptions = IFramePlayerOptions.Builder().controls(1).build()
        inflateCustomPlayerUi(R.layout.ayp_empty_layout)
        initialize(youTubePlayerListener, handleNetworkEvents, iFramePlayerOptions)
    }

    /**
     * @param youTubePlayerCallback A callback that will be called when the YouTubePlayer is ready.
     * If the player is ready when the function is called, the callback is called immediately.
     * This function is called only once.
     */
    fun getYouTubePlayerWhenReady(youTubePlayerCallback: YouTubePlayerCallback) {
        youTubePlayer?.let {
            if(isYouTubePlayerReady)
                youTubePlayerCallback.onYouTubePlayer(it)
            else
                youTubePlayerCallbacks.add(youTubePlayerCallback)
        }
    }

    /**
     * Use this method to replace the default Ui of the player with a custom Ui.
     *
     * You will be responsible to manage the custom Ui from your application,
     * the default controller obtained through [LegacyYouTubePlayerView.getPlayerUiController] won't be available anymore.
     * @param layoutId the ID of the layout defining the custom Ui.
     * @return The inflated View
     */
    fun inflateCustomPlayerUi(@LayoutRes layoutId: Int): View {
        removeViews(1, childCount - 1)

        if (!isUsingCustomUi) {
            youTubePlayer?.removeListener(defaultPlayerUiController)
            fullScreenHelper.removeFullScreenListener(defaultPlayerUiController)
        }

        isUsingCustomUi = true

        return View.inflate(context, layoutId, this)
    }

    /**
     * Call this method before destroying the host Fragment/Activity, or register this View as an observer of its host lifecycle
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun release() {
        removeView(youTubePlayer)
        youTubePlayer?.removeAllViews()
        youTubePlayer?.destroy()
        try {
            context.unregisterReceiver(networkListener)
        } catch (ignore: Exception) {
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    internal fun onResume() {
        playbackResumer.onLifecycleResume()
        canPlay = true
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    internal fun onStop() {
        youTubePlayer?.pause()
        playbackResumer.onLifecycleStop()
        canPlay = false
    }

    /**
     * Checks whether the player is in an eligible state for playback in
     * respect of the {@link WebViewYouTubePlayer#isBackgroundPlaybackEnabled}
     * property.
     */
    internal fun isEligibleForPlayback(): Boolean {
        youTubePlayer?.let {
            return canPlay || it.isBackgroundPlaybackEnabled
        }
        return false
    }

    /**
     * Don't use this method if you want to publish your app on the PlayStore. Background playback is against YouTube terms of service.
     */
    fun enableBackgroundPlayback(enable: Boolean) {
        youTubePlayer?.isBackgroundPlaybackEnabled = enable
    }

    fun getPlayerUiController(): PlayerUiController? {
        if(isException){
            return null
        }
        if (isUsingCustomUi)
            throw RuntimeException("You have inflated a custom player Ui. You must manage it with your own controller.")

        try {
            return defaultPlayerUiController
        } catch (e: Exception) {
            return null
        }
    }

    fun enterFullScreen() = fullScreenHelper.enterFullScreen()

    fun exitFullScreen() = fullScreenHelper.exitFullScreen()

    fun toggleFullScreen() = fullScreenHelper.toggleFullScreen()

    fun isFullScreen(): Boolean = fullScreenHelper.isFullScreen

    fun addFullScreenListener(fullScreenListener: YouTubePlayerFullScreenListener): Boolean =
            fullScreenHelper.addFullScreenListener(fullScreenListener)

    fun removeFullScreenListener(fullScreenListener: YouTubePlayerFullScreenListener): Boolean =
            fullScreenHelper.removeFullScreenListener(fullScreenListener)
}
