/*
 * Copyright (C) 2023-2024 The risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.util

import android.app.WallpaperColors
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionLegacyHelper
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View

import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.Dependency
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.monet.ColorScheme
import com.android.systemui.media.dialog.MediaOutputDialogManager

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

class MediaSessionManagerHelper private constructor(private val context: Context) {

    interface MediaMetadataListener {
        fun onMediaMetadataChanged() {}
        fun onPlaybackStateChanged() {}
        fun onMediaColorsChanged() {}
    }

    private var lastSavedPackageName: String? = null
    private val mediaSessionManager: MediaSessionManager = context.getSystemService(MediaSessionManager::class.java)!!
    private var activeController: MediaController? = null
    private val listeners = mutableSetOf<MediaMetadataListener>()
    private var mediaMetadata: MediaMetadata? = null
    private var currMediaArtColor: Int = 0
    private var mWallpaperColors: WallpaperColors? = null
    private var mCurrentColorScheme: ColorScheme? = null

    private val mediaStateFlow = MutableStateFlow<MediaState?>(null)
    private var updateJob: Job? = null

    private data class MediaState(
        val metadata: MediaMetadata?,
        val playbackState: PlaybackState?,
        val colors: WallpaperColors?
    )

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (mediaMetadata != metadata) {
                mediaMetadata = metadata
                notifyListeners { it.onMediaMetadataChanged() }
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            notifyListeners { it.onPlaybackStateChanged() }
        }
    }

    init {
        lastSavedPackageName = Settings.System.getString(
            context.contentResolver,
            "media_session_last_package_name"
        )
    }

    private fun startPeriodicUpdate() {
        updateJob = CoroutineScope(Dispatchers.IO).launch {
            mediaStateFlow.collectLatest { state ->
                withContext(Dispatchers.Main) {
                    state?.let {
                        if (mediaMetadata != it.metadata) {
                            mediaMetadata = it.metadata
                            notifyListeners { it.onMediaMetadataChanged() }
                        }
                        notifyListeners { it.onPlaybackStateChanged() }
                        updateMediaColors(it.colors)
                    }
                }
            }
        }
    }

    private suspend fun updateMediaState() {
        val controller = getActiveLocalMediaController()
        val metadata = controller?.metadata
        val state = controller?.playbackState
        val colors = metadata?.let { extractWallpaperColors(it) }

        mediaStateFlow.emit(MediaState(metadata, state, colors))
    }

    private fun extractWallpaperColors(metadata: MediaMetadata): WallpaperColors? {
        return (metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART))
            ?.let { WallpaperColors.fromBitmap(it) }
    }

    private fun updateMediaColors(wallpaperColors: WallpaperColors?) {
        if (wallpaperColors == null || wallpaperColors == mWallpaperColors) return

        val config = context.resources.configuration
        val isDarkThemeOn = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val colorScheme = ColorScheme(wallpaperColors, isDarkThemeOn)
        val newMediaArtColor = if (isDarkThemeOn) colorScheme.accent1.s100 else colorScheme.accent1.s800

        if (currMediaArtColor != newMediaArtColor) {
            currMediaArtColor = newMediaArtColor
            mCurrentColorScheme = colorScheme
            mWallpaperColors = wallpaperColors
            notifyListeners { it.onMediaColorsChanged() }
        }
    }

    fun getColorScheme(): ColorScheme? = mCurrentColorScheme

    fun addMediaMetadataListener(listener: MediaMetadataListener?) {
        listener?.let {
            val wasEmpty = listeners.isEmpty()
            listeners.add(it)
            if (wasEmpty) {
                startPeriodicUpdate()
                CoroutineScope(Dispatchers.IO).launch {
                    updateMediaState()
                }
            }
        }
    }

    fun removeMediaMetadataListener(listener: MediaMetadataListener?) {
        listener?.let {
            listeners.remove(it)
            if (listeners.isEmpty()) {
                updateJob?.cancel()
                activeController?.unregisterCallback(mediaControllerCallback)
                activeController = null
                mediaMetadata = null
            }
        }
    }

    private fun notifyListeners(action: (MediaMetadataListener) -> Unit = {}) {
        listeners.forEach { action(it) }
    }

    fun seekTo(time: Long) {
        getActiveLocalMediaController()?.transportControls?.seekTo(time)
    }

    fun getTotalDuration(): Long = getMediaMetadata()?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    private fun saveLastNonNullPackageName() {
        getActiveLocalMediaController()?.packageName?.takeIf { it.isNotEmpty() }?.let { pkg ->
            if (pkg != lastSavedPackageName) {
                Settings.System.putString(
                    context.contentResolver,
                    "media_session_last_package_name",
                    pkg
                )
                lastSavedPackageName = pkg
            }
        }
    }

    fun updateMediaController() {
        val localController = getActiveLocalMediaController()
        if (localController != null && !sameSessions(activeController, localController)) {
            activeController?.unregisterCallback(mediaControllerCallback)
            activeController = localController
            activeController?.registerCallback(mediaControllerCallback)
            saveLastNonNullPackageName()
            CoroutineScope(Dispatchers.IO).launch {
                updateMediaState()
            }
        }
    }

    private fun getActiveLocalMediaController(): MediaController? {
        return mediaSessionManager.getActiveSessions(null)
            .firstOrNull { controller ->
                controller.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL &&
                    controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }
    }

    fun getMediaBitmap(): Bitmap? {
        return getMediaMetadata()?.let { metadata ->
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }
    }

    fun getMediaMetadata(): MediaMetadata? = mediaMetadata
    
    fun getMediaColor(): Int = currMediaArtColor

    fun isMediaControllerAvailable(): Boolean {
        return getActiveLocalMediaController()?.packageName?.isNotEmpty() ?: false
    }

    fun isMediaPlaying(): Boolean {
        return isMediaControllerAvailable() &&
            getMediaControllerPlaybackState(getActiveLocalMediaController()) == PlaybackState.STATE_PLAYING
    }

    fun getMediaControllerPlaybackState(controller: MediaController?): Int {
        return controller?.playbackState?.state ?: PlaybackState.STATE_NONE
    }

    fun getMediaControllerPlaybackState(): PlaybackState? {
        return getActiveLocalMediaController()?.playbackState
    }

    private fun sameSessions(a: MediaController?, b: MediaController?): Boolean {
        return a == b || a?.controlsSameSession(b) == true
    }

    private fun dispatchMediaKeyWithWakeLockToMediaSession(keycode: Int) {
        MediaSessionLegacyHelper.getHelper(context)?.let { helper ->
            val event = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                keycode,
                0
            )
            helper.sendMediaButtonEvent(event, true)
            helper.sendMediaButtonEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_UP), true)
        }
    }

    fun prevSong() = dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun nextSong() = dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT)

    fun toggleMediaPlaybackState() {
        dispatchMediaKeyWithWakeLockToMediaSession(
            if (isMediaPlaying()) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY
        )
    }
    
    fun showMediaDialog(view: View) {
        lastSavedPackageName?.takeIf { it.isNotEmpty() }?.let { packageName ->
            Dependency.get(MediaOutputDialogManager::class.java)
                .createAndShowWithController(
                    packageName,
                    true,
                    Expandable.fromView(view).dialogController()
                )
        }
    }
    
    private fun Expandable.dialogController(): DialogTransitionAnimator.Controller? {
        return dialogTransitionController(
            cuj = DialogCuj(
                InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                MediaOutputDialogManager.INTERACTION_JANK_TAG
            )
        )
    }

    companion object {
        @Volatile
        private var instance: MediaSessionManagerHelper? = null
        
        fun getInstance(context: Context): MediaSessionManagerHelper {
            return instance ?: synchronized(this) {
                instance ?: MediaSessionManagerHelper(context).also { instance = it }
            }
        }
    }
}
