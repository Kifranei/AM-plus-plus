package dev.amenhancer.module.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.Html
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import dev.amenhancer.module.ModuleConstants
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.service.addConnectionListener

/** Modern Xposed adapter for LyricProvider's native Apple Music song/position protocol. */
internal class AppleMusicLyriconTarget(
    private val application: Application,
    private val loader: ClassLoader,
    private val build: TargetBuild,
    private val symbols: TargetSymbolResolver,
    private val currentSong: CurrentSongIdentityCache,
) : LyriconTarget {
    private var result: TargetCapabilityInstall? = null
    private var provider: LyriconProvider? = null
    private val main = Handler(Looper.getMainLooper())
    private var playback: PlaybackState? = null
    private var session: Any? = null
    private val state = LyriconSongState { song ->
        provider?.player?.setDisplayTranslation(song?.lyrics.orEmpty().any { !it.translation.isNullOrBlank() })
        provider?.player?.setDisplayRoma(false)
        provider?.player?.setSong(song)
        val lines = song?.lyrics.orEmpty()
        val mismatches = lines.count { line -> !line.words.isNullOrEmpty() &&
            line.words!!.joinToString("") { word -> word.text.orEmpty() } != line.text }
        ModernXposedRuntime.log("lyricon: song=${song?.id} lines=${lines.size} " +
            "translated=${lines.count { !it.translation.isNullOrBlank() }} " +
            "wordLines=${lines.count { !it.words.isNullOrEmpty() }} " +
            "multiWordLines=${lines.count { (it.words?.size ?: 0) > 1 }} " +
            "spacingMismatch=$mismatches")
    }
    private val tick = object : Runnable {
        override fun run() {
            if (playback?.state != PlaybackState.STATE_PLAYING || !screenOn()) return
            provider?.player?.setPosition(position())
            main.postDelayed(this, 50L)
        }
    }

    @Synchronized override fun install(): TargetCapabilityInstall {
        result?.let { return it }
        if (Build.VERSION.SDK_INT < 28) return TargetCapabilityInstall.Unsupported("Lyricon needs Android 9+")
        val names = AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?.document?.optJSONObject("lyricon")
            ?: return TargetCapabilityInstall.Unsupported("No verified Lyricon contract for ${build.displayName}")
        val scope = HookRegistrationScope()
        return try {
            val model = loader.loadClass(names.getString("viewModelClass"))
            val pointer = checkNotNull(symbols.resolve(AppleMusicSymbols.SongInfoPtr).valueOrNull())
            val item = loader.loadClass(names.getString("itemClass"))
            val buildLyrics = model.getDeclaredMethod(names.getString("buildMethod"), pointer).apply { isAccessible = true }
            val loadLyrics = model.getDeclaredMethod(names.getString("loadMethod"), item).apply { isAccessible = true }
            val installLyrics = checkNotNull(symbols.resolve(AppleMusicSymbols.LyricsInstallMethod).valueOrNull())
            val installedPointer = installLyrics.declaringClass.getDeclaredField(names.getString("installedPointerField"))
                .apply { check(type == pointer); isAccessible = true }
            val constructor = model.getConstructor(Application::class.java)
            val nativeType = checkNotNull(symbols.resolve(AppleMusicSymbols.SongInfoNative).valueOrNull())
            val unwrap = pointer.getMethod("get")
            val selectTranslation = nativeType.getMethod("setTranslation", String::class.java)
            val systemLanguage = model.getMethod("getCurrentSystemLyricsLanguage")
            val pronunciationLanguages = nativeType.getMethod("getPronunciationLanguages")
            val selectPronunciation = nativeType.getMethod("setPronunciation", String::class.java)
            val matchPronunciation = loader.loadClass(names.getString("localeUtilClass"))
                .getMethod("matchToSystemLyricsScript", pronunciationLanguages.returnType)
            val translationSelected = model.getMethod("getTranslationSelectedLiveResult")
            val pronunciationSelected = model.getMethod("getPronunciationSelectedLiveResult")
            val observerType = loader.loadClass(names.getString("observerClass"))
            val requestModel = constructor.newInstance(application)
            var requestedId: String? = null
            fun request() {
                val current = currentSong.current() ?: return
                val id = current.details.appleMusicId.toString()
                if (id != state.currentId || state.hasLyrics || requestedId == id || !item.isInstance(current.item)) return
                requestedId = id
                runCatching {
                    loadLyrics.invoke(requestModel, current.item)
                }.onFailure { requestedId = null; ModernXposedRuntime.log("lyricon lyric request failed", it) }
            }
            fun hook(method: java.lang.reflect.Method, callback: ModernMethodHook) {
                check(ModernXposedRuntime.hookMethod(method, callback, scope))
            }
            val parser = LyriconNativeSongParser { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
            fun capture(pointer: Any?, installed: Boolean = false) {
                if (pointer == null) return
                runCatching {
                    val song = parser.parse(pointer) ?: return@runCatching
                    main.post { if (scope.isActive) state.lyrics(song, installed) }
                }.onFailure { ModernXposedRuntime.log("lyricon native lyric snapshot failed", it) }
            }
            hook(buildLyrics, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // The dedicated request model does not run the UI fragment's F2 language
                    // setup. Select its translation language before snapshotting its lyrics.
                    if (param.throwable == null && param.thisObject === requestModel) runCatching {
                        val ptr = param.args.getOrNull(0) ?: return@runCatching
                        val language = systemLanguage.invoke(param.thisObject) as String
                        val native = unwrap.invoke(ptr) ?: return@runCatching
                        selectTranslation.invoke(native, language)
                        val pronunciation = matchPronunciation.invoke(null, pronunciationLanguages.invoke(native)) as? String
                        if (pronunciation != null) selectPronunciation.invoke(native, pronunciation)
                    }.onFailure { ModernXposedRuntime.log("lyricon translation selection failed", it) }
                    if (param.throwable == null) capture(param.args.getOrNull(0))
                }
            })
            // This also receives the final manual/automatic replacement supplied by AM++.
            hook(installLyrics, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) runCatching {
                        capture(installedPointer.get(param.thisObject), installed = true)
                    }.onFailure { ModernXposedRuntime.log("lyricon installed lyric snapshot failed", it) }
                }
            })
            hook(MediaSession::class.java.getDeclaredMethod("setMetadata", MediaMetadata::class.java), object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val metadata = param.args.getOrNull(0) as? MediaMetadata
                    val owner = param.thisObject
                    main.post {
                        if (!scope.isActive) return@post
                        session = owner
                        val id = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }
                        val song = id?.let { Song(id = it, name = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                            duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)) }
                        if (state.metadata(song)) {
                            requestedId = null
                            if (song == null) { playback = null; main.removeCallbacks(tick); provider?.player?.setPlaybackState(false) }
                        }
                        request()
                    }
                }
            })
            hook(MediaSession::class.java.getDeclaredMethod("setPlaybackState", PlaybackState::class.java), object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val value = param.args.getOrNull(0) as? PlaybackState
                    val owner = param.thisObject
                    main.post {
                        if (!scope.isActive || (session != null && session !== owner)) return@post
                        playback = value
                        provider?.player?.setPlaybackState(value?.state == PlaybackState.STATE_PLAYING)
                        provider?.player?.seekTo(position())
                        main.removeCallbacks(tick)
                        if (value?.state == PlaybackState.STATE_PLAYING && screenOn()) main.post(tick)
                    }
                }
            })
            provider = LyriconFactory.createProvider(application,
                providerPackageName = ModuleConstants.MODULE_PACKAGE, playerPackageName = application.packageName)
            scope.onClose { provider?.destroy(); provider = null; main.removeCallbacks(tick) }
            var selection = LyriconAuxiliarySelection()
            fun updateSelection(next: LyriconAuxiliarySelection) {
                selection = next
                main.post {
                    if (!scope.isActive) return@post
                    state.auxiliary(next)
                    ModernXposedRuntime.log("lyricon: auxiliary translation=${next.translation} pronunciation=${next.pronunciation}")
                }
            }
            scope.onClose(observeLyriconSelection(requestModel, translationSelected, observerType) {
                updateSelection(selection.copy(translation = it))
            })
            scope.onClose(observeLyriconSelection(requestModel, pronunciationSelected, observerType) {
                updateSelection(selection.copy(pronunciation = it))
            })
            provider?.service?.addConnectionListener {
                onConnected { ModernXposedRuntime.log("lyricon: connected to system central service") }
                onReconnected { ModernXposedRuntime.log("lyricon: reconnected to system central service") }
                onConnectTimeout { ModernXposedRuntime.log("lyricon: central service unavailable; cached song retained") }
            }
            val screen = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    main.removeCallbacks(tick)
                    if (screenOn() && playback?.state == PlaybackState.STATE_PLAYING) main.post(tick)
                }
            }
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            if (Build.VERSION.SDK_INT >= 33) application.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
            else application.registerReceiver(screen, filter)
            scope.onClose { application.unregisterReceiver(screen) }
            val subscription = currentSong.addListener { main.post { if (scope.isActive) request() } }
            scope.onClose(subscription::close)
            scope.activate()
            main.post {
                provider?.player?.setDisplayTranslation(false)
                provider?.player?.setDisplayRoma(false)
                provider?.register()
            }
            TargetCapabilityInstall.Active("词幕提供器已接入：原生/替换歌词、逐字、翻译、背景人声与系统播放进度")
                .also { result = it }
        } catch (error: Throwable) { scope.close(); throw error }
    }

    private fun screenOn(): Boolean = (application.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    private fun position(): Long {
        val value = playback ?: return 0L
        val elapsed = if (value.state == PlaybackState.STATE_PLAYING && value.lastPositionUpdateTime > 0)
            (SystemClock.elapsedRealtime() - value.lastPositionUpdateTime).coerceAtLeast(0) else 0L
        return (value.position + elapsed * value.playbackSpeed).toLong().coerceAtLeast(0)
    }
}
