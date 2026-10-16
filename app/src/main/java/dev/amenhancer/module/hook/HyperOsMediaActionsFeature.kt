package dev.amenhancer.module.hook

import android.annotation.SuppressLint
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import dev.amenhancer.module.ModuleConstants

/**
 * Restores Apple Music favorite / shuffle / repeat buttons on Xiaomi HyperOS media notifications.
 *
 * App side (this class, Apple Music process): on every [MediaSession.setPlaybackState] rebuild a
 * well-formed [PlaybackState] that keeps Apple Music's own CustomAction ids (taps still reach the
 * host callback), guarantees non-empty labels / valid icons, adds the standard shuffle / repeat /
 * rating action bits and the Wear SHOW_ON_WEAR visibility hint.
 *
 * SystemUI side: see [SystemUiMediaActionsHook] (needs `com.android.systemui` in the LSPosed scope).
 *
 * The hook is installed dormant and consults the toggle on every call, so flipping the setting
 * does not need a new process for the app-side rewrite.
 */
internal class HyperOsMediaActionsFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_HYPEROS_MEDIA_ACTIONS

    override fun install(context: HookContext): FeatureInstallResult {
        if (!isXiaomiFamily()) {
            return FeatureInstallResult.unsupported("Not a Xiaomi/HyperOS device; media actions left untouched")
        }
        val method = MediaSession::class.java.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!context.config.settings().hyperOsMediaActionsEnabled) return
                val state = param.args.getOrNull(0) as? PlaybackState ?: return
                runCatching { param.args[0] = enhance(state) }
                    .onFailure { ModernXposedRuntime.log("HyperOS media actions enhance failed", it) }
            }
        })
        return if (context.config.settings().hyperOsMediaActionsEnabled) {
            FeatureInstallResult.active("MediaSession.setPlaybackState CustomAction rewrite installed")
        } else {
            FeatureInstallResult.disabled("Hook installed; toggle is off")
        }
    }

    companion object {
        const val ACTION_FAVORITE = "com.apple.android.music.playback.action.FAVORITE"
        const val ACTION_SHUFFLE = "com.apple.android.music.playback.action.SHUFFLE"
        const val ACTION_REPEAT = "com.apple.android.music.playback.action.REPEAT"
        const val ACTION_ADD_TO_LIBRARY = "com.apple.android.music.playback.action.ADD_TO_LIBRARY"
        const val ACTION_REMOVE_FROM_LIBRARY = "com.apple.android.music.playback.action.REMOVE_FROM_LIBRARY"

        // android.media.session.PlaybackState bits (API 24 / 31), not on every stub
        private const val ACTION_SET_REPEAT_MODE = 1L shl 18
        private const val ACTION_SET_SHUFFLE_MODE = 1L shl 21
        private const val EXTRA_SHOW_ON_WEAR =
            "android.support.wearable.media.extra.CUSTOM_ACTION_SHOW_ON_WEAR"

        fun isXiaomiFamily(
            manufacturer: String? = Build.MANUFACTURER,
            brand: String? = Build.BRAND,
        ): Boolean = listOfNotNull(manufacturer, brand).any {
            it.equals("xiaomi", true) || it.equals("redmi", true) || it.equals("poco", true)
        }

        fun labelFor(action: String?): String = when (action) {
            ACTION_FAVORITE -> "收藏"
            ACTION_SHUFFLE -> "随机播放"
            ACTION_REPEAT -> "循环播放"
            ACTION_ADD_TO_LIBRARY -> "添加到资料库"
            ACTION_REMOVE_FROM_LIBRARY -> "从资料库删除"
            else -> action ?: "action"
        }

        @SuppressLint("WrongConstant")
        fun enhance(state: PlaybackState): PlaybackState {
            val existing = state.customActions.orEmpty()
            val ids = existing.mapNotNull { it.action }.toSet()
            val updateTime =
                if (state.lastPositionUpdateTime > 0L) state.lastPositionUpdateTime
                else SystemClock.elapsedRealtime()

            val fresh = PlaybackState.Builder()
                .setState(state.state, state.position, state.playbackSpeed, updateTime)
                .setActions(
                    state.actions or
                        ACTION_SET_SHUFFLE_MODE or
                        ACTION_SET_REPEAT_MODE or
                        PlaybackState.ACTION_SET_RATING or
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO,
                )
                .setActiveQueueItemId(state.activeQueueItemId)
                .setBufferedPosition(state.bufferedPosition)
            state.extras?.let { fresh.setExtras(Bundle(it)) }
            state.errorMessage?.let { fresh.setErrorMessage(it) }

            val seen = mutableSetOf<String>()
            for (ca in existing) {
                val action = ca.action ?: continue
                if (!seen.add(action)) continue
                fresh.addCustomAction(normalize(ca))
            }
            // Only synthesize when Apple Music published none of the library/favorite variants.
            if (ACTION_FAVORITE !in ids && ACTION_ADD_TO_LIBRARY !in ids && ACTION_REMOVE_FROM_LIBRARY !in ids) {
                fresh.addCustomAction(synthetic(ACTION_FAVORITE, android.R.drawable.btn_star_big_off))
            }
            if (ACTION_SHUFFLE !in ids) {
                fresh.addCustomAction(synthetic(ACTION_SHUFFLE, android.R.drawable.ic_menu_sort_by_size))
            }
            if (ACTION_REPEAT !in ids) {
                fresh.addCustomAction(synthetic(ACTION_REPEAT, android.R.drawable.ic_menu_rotate))
            }
            return fresh.build()
        }

        private fun synthetic(action: String, icon: Int): PlaybackState.CustomAction =
            normalize(PlaybackState.CustomAction.Builder(action, labelFor(action), icon).build())

        private fun normalize(ca: PlaybackState.CustomAction): PlaybackState.CustomAction {
            val name = ca.name?.takeIf { it.isNotBlank() } ?: labelFor(ca.action)
            val icon = if (ca.icon != 0) ca.icon else android.R.drawable.ic_media_play
            val extras = Bundle(ca.extras ?: Bundle.EMPTY).apply { putBoolean(EXTRA_SHOW_ON_WEAR, true) }
            return PlaybackState.CustomAction.Builder(ca.action, name, icon).setExtras(extras).build()
        }
    }
}
