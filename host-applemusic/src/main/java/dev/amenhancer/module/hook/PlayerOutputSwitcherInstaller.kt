package dev.amenhancer.module.hook

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import java.lang.reflect.Method

/** Keep Apple's typed DataBinding view; replace its indicator and picker contracts. */
internal class PlayerOutputSwitcherInstaller(
    private val application: Application,
    private val loader: ClassLoader,
    private val build: TargetBuild,
) : PlayerAudioOutputTarget {
    private var result: TargetCapabilityInstall? = null

    @Synchronized override fun install(): TargetCapabilityInstall {
        result?.let { return it }
        val names = AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?.document?.optJSONObject("audioOutput")
            ?: return TargetCapabilityInstall.Unsupported("No verified player audio-output contract for ${build.displayName}")
        val scope = HookRegistrationScope()
        return try {
            val type = loader.loadClass(names.getString("buttonClass"))
            val indicator = method(type, "setRemoteIndicatorDrawableInternal", Drawable::class.java)
            val attached = method(type, "onAttachedToWindow")
            val dialog = method(type, "showDialog")
            val click = method(type, "performClick")
            val description = method(type, "updateContentDescription")
            val id = application.resources.getIdentifier(names.getString("buttonId"), "id", build.packageName)
            val lyricsId = application.resources.getIdentifier(names.getString("lyricsId"), "id", build.packageName)
            check(id != 0 && lyricsId != 0) { "Player route control resources missing" }
            val moduleInfo = checkNotNull(ModernXposedRuntime.activeModule()).moduleApplicationInfo
            val apkResources = application.packageManager.getResourcesForApplication(moduleInfo)
            @Suppress("DEPRECATION")
            val resources = Resources(apkResources.assets, application.resources.displayMetrics,
                Configuration(application.resources.configuration))
            val iconId = resources.getIdentifier("ic_media_output", "drawable", moduleInfo.packageName)
            val icon = checkNotNull(resources.getDrawable(iconId, resources.newTheme())?.constantState) { "Player output icon missing" }
            fun owns(value: Any?): Boolean {
                val view = value as? View ?: return false
                return type.isInstance(view) && view.id == id &&
                    (view.parent as? ViewGroup)?.findViewById<View>(lyricsId) != null
            }
            fun hook(member: Method, callback: ModernMethodHook) {
                check(ModernXposedRuntime.hookMethod(member, callback, scope))
            }
            hook(indicator, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (owns(param.thisObject)) param.args[0] = icon.newDrawable(resources).mutate()
                }
            })
            hook(attached, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !owns(param.thisObject)) return
                    runCatching {
                        indicator.invoke(param.thisObject, icon.newDrawable(resources).mutate())
                        (param.thisObject as View).contentDescription = "投放 · 音频输出"
                        ModernXposedRuntime.log("player_audio_output: native route indicator replaced")
                    }.onFailure { ModernXposedRuntime.log("player audio-output indicator failed", it) }
                }
            })
            val open = object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!owns(param.thisObject)) return
                    runCatching { PlatformAudioOutputSwitcher.open((param.thisObject as View).context) }
                        .onFailure { ModernXposedRuntime.log("player audio-output picker failed", it) }
                    param.result = true
                }
            }
            hook(dialog, open)
            hook(click, open)
            hook(description, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (owns(param.thisObject)) (param.thisObject as View).contentDescription = "投放 · 音频输出"
                }
            })
            scope.activate()
            TargetCapabilityInstall.Active("Native route indicator and system output picker hooks installed")
                .also { result = it }
        } catch (error: Throwable) {
            scope.close()
            throw error
        }
    }

    private fun method(type: Class<*>, name: String, vararg args: Class<*>): Method =
        generateSequence(type as Class<*>?) { it.superclass }.firstNotNullOfOrNull {
            runCatching { it.getDeclaredMethod(name, *args).apply { isAccessible = true } }.getOrNull()
        } ?: throw NoSuchMethodException("${type.name}#$name")
}
