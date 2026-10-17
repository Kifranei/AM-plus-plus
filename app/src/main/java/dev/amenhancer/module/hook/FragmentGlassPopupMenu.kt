package dev.amenhancer.module.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.ViewBackdrop
import dev.amenhancer.glass.GlassHostView
import dev.amenhancer.glass.GlassPopupMenuSurface
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import dev.amenhancer.module.config.TargetConfigClient
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Liquid glass behind Apple Music's Compose dropdown menus (Library "⋮" → Edit Categories /
 * Settings / Account).
 *
 * Host shape (1609 / 1611, static analysis): a Material3 `DropdownMenu` whose transparent container
 * hosts an `AndroidView` factory that inflates layout `option_button_blur` — an
 * `eightbitlab.com.blurview.BlurView` — and wires it to the activity's `BlurTarget`. That BlurView is
 * the menu's Gaussian-blur background; the native items are Compose siblings drawn on top.
 *
 * We keep the host's menu, items, touch handling and shape clip untouched and only change the
 * background material of a BlurView that (a) has id `option_button_blur` and (b) lives in a popup
 * window rather than the activity window:
 *  1. Before attach, swap the BlurView's blur controller (its single interface-typed field) for a
 *     no-op proxy so no Gaussian blur is recorded or drawn. `draw()` still runs `super.draw`.
 *  2. Add a module-owned [GlassHostView] child that draws the shared bottom-bar glass recipe over a
 *     [ViewBackdrop] of the activity's `blur_target` content (cross-window offset handled).
 * Everything is fail-open: any error leaves/puts back a plain translucent fill, never throws into
 * the host, and the toggle is read live at each menu open.
 */
@RequiresApi(33)
internal object FragmentGlassPopupMenu {
    private const val TAG = "popup-menu glass"
    private const val READY_TIMEOUT_MS = 1500L
    private const val MAX_FAILURES = 3
    private val mounts = Collections.synchronizedMap(IdentityHashMap<View, Mount>())
    private var installed = false
    private var failures = 0

    fun supports(build: TargetBuild): Boolean = AppleMusicHostProfiles.find(
        build.packageName, build.versionName, build.versionCode,
    )?.let {
        it.productionEnabled && it.capability("glass") && it.capability("glassPopupMenu") &&
            it.family == "fragment-content" && it.document.has("fragmentChrome") && it.document.has("glassPopupMenu")
    } == true

    fun install(loader: ClassLoader, build: TargetBuild, config: TargetConfigClient) {
        if (installed) return
        if (!supports(build)) {
            GlassDiagLog.i("$TAG: host profile for ${build.versionName}/${build.versionCode} has no glassPopupMenu capability; menus stay native")
            return
        }
        try {
            val profile = checkNotNull(AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode))
            val blurClassName = profile.document.getJSONObject("fragmentChrome").getString("blurClass")
            val spec = profile.document.getJSONObject("glassPopupMenu")
            val ids = Ids(spec.getString("blurViewId"), spec.getString("backdropSource"), spec.getString("cornerRadiusDimen"))
            val blurClass = Class.forName(blurClassName, false, loader)
            val controllerField = findControllerField(blurClass)
            val attach = blurClass.getDeclaredMethod("onAttachedToWindow")
            val detach = blurClass.getDeclaredMethod("onDetachedFromWindow")
            ModernXposedRuntime.hookMethod(attach, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? ViewGroup ?: return
                    try { mount(view, controllerField, config, ids) }
                    catch (error: Throwable) { fail(view, error) }
                }
            })
            ModernXposedRuntime.hookMethod(detach, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    try { unmount(view) } catch (error: Throwable) { GlassDiagLog.e("$TAG: unmount failed", error) }
                }
            })
            installed = true
            GlassDiagLog.i("$TAG: hooks installed on $blurClassName (controller field ${controllerField.name}:${controllerField.type.name})")
        } catch (error: Throwable) {
            GlassDiagLog.e("$TAG: installation failed; dropdown menus stay native", error)
        }
    }

    private fun findControllerField(blurClass: Class<*>): Field {
        val candidates = generateSequence(blurClass) { it.superclass }
            .takeWhile { it != ViewGroup::class.java && it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.type.isInterface && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .toList()
        check(candidates.size == 1) { "BlurView controller field ambiguous/missing: ${candidates.map { it.name }}" }
        return candidates.single().also { it.isAccessible = true }
    }

    private fun mount(view: ViewGroup, controllerField: Field, config: TargetConfigClient, ids: Ids) {
        if (failures >= MAX_FAILURES || mounts.containsKey(view)) return
        val settings = config.settings()
        if (!settings.phoneLiquidGlassEnabled || !settings.phoneLiquidGlassPopupMenuEnabled) return
        val context = view.context
        val optionBlurId = context.resources.getIdentifier(ids.blurView, "id", context.packageName)
        if (optionBlurId == 0 || view.id != optionBlurId) return
        val activity = activityOf(context) ?: return
        val root = view.rootView
        if (root == null || root === activity.window.decorView) return // toolbar buttons etc. stay native
        val targetId = context.resources.getIdentifier(ids.backdropSource, "id", context.packageName)
        val source = activity.findViewById<View>(targetId)
        if (targetId == 0 || source == null) {
            GlassDiagLog.i("$TAG: blur_target not found; menu stays native")
            return
        }
        val original = controllerField.get(view)
        val noop = Proxy.newProxyInstance(controllerField.type.classLoader, arrayOf(controllerField.type)) { proxy, method, _ ->
            when {
                method.returnType == java.lang.Boolean.TYPE -> true // BlurView.draw(): keep drawing children
                method.returnType == Void.TYPE -> null
                method.returnType.isInstance(proxy) -> proxy
                method.returnType.isPrimitive -> 0
                else -> null
            }
        }
        controllerField.set(view, noop)
        val mount = Mount(view, controllerField, original)
        mounts[view] = mount
        GlassDiagLog.i("$TAG: Gaussian blur suppressed for popup (root=${root.javaClass.name}); mounting glass")
        view.post {
            if (mounts[view] !== mount) return@post
            try { attachGlass(mount, activity, source, settings.phoneLiquidGlassPanelBlurDp, ids) }
            catch (error: Throwable) { fail(view, error) }
        }
    }

    private fun attachGlass(mount: Mount, activity: Activity, source: View, blurDp: Int, ids: Ids) {
        val view = mount.view
        if (!view.isAttachedToWindow) return
        val resources = view.resources
        val radiusId = resources.getIdentifier(ids.radiusDimen, "dimen", view.context.packageName)
        val radiusDp = if (radiusId != 0) resources.getDimension(radiusId) / resources.displayMetrics.density else 24f
        val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val sourceOrigin = IntArray(2)
        val popupOrigin = IntArray(2)
        val backdrop = ViewBackdrop(source) { error -> view.post { fail(view, error) } }
        backdrop.consumerWindowDelta = { out ->
            source.rootView.getLocationOnScreen(sourceOrigin)
            view.rootView.getLocationOnScreen(popupOrigin)
            out[0] = (sourceOrigin[0] - popupOrigin[0]).toFloat()
            out[1] = (sourceOrigin[1] - popupOrigin[1]).toFloat()
        }
        val glass = GlassHostView(moduleContext(activity), bleedDp = 0)
        glass.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        glass.isClickable = false
        glass.content { GlassPopupMenuSurface(backdrop, radiusDp.dp, dark, blurDp.dp) }
        mount.backdrop = backdrop
        mount.glass = glass
        view.addView(glass, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        backdrop.start()
        GlassDiagLog.i("$TAG: glass mounted (radius=${radiusDp}dp blur=${blurDp}dp dark=$dark popup=${view.width}x${view.height})")
        view.postDelayed({
            if (mounts[view] === mount && !backdrop.ready) {
                GlassDiagLog.i("$TAG: backdrop not ready after ${READY_TIMEOUT_MS}ms; using translucent fallback fill")
                fallbackFill(view, dark)
            }
        }, READY_TIMEOUT_MS)
    }

    private fun fallbackFill(view: View, dark: Boolean) {
        // Compose already clips the BlurView to the menu shape.
        view.setBackgroundColor(if (dark) AndroidColor.argb(0xB0, 0x24, 0x24, 0x26) else AndroidColor.argb(0xC8, 0xF2, 0xF2, 0xF7))
    }

    private fun fail(view: View, error: Throwable) {
        failures++
        GlassDiagLog.e("$TAG: failed ($failures/$MAX_FAILURES); falling back to translucent fill", error)
        val dark = runCatching {
            view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }.getOrDefault(true)
        runCatching { teardown(mounts[view]) }
        runCatching { fallbackFill(view, dark) }
    }

    private fun unmount(view: View) {
        val mount = mounts.remove(view) ?: return
        teardown(mount)
        GlassDiagLog.i("$TAG: popup closed; glass released")
    }

    private fun teardown(mount: Mount?) {
        mount ?: return
        runCatching { mount.backdrop?.close() }
        runCatching { mount.glass?.let { (it.parent as? ViewGroup)?.removeView(it) } }
        mount.glass = null
        mount.backdrop = null
    }

    private class Ids(val blurView: String, val backdropSource: String, val radiusDimen: String)

    private class Mount(val view: ViewGroup, val field: Field, val original: Any?) {
        var glass: GlassHostView? = null
        var backdrop: ViewBackdrop? = null
    }

    private fun activityOf(context: Context): Activity? {
        var current = context
        val seen = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
        while (seen.add(current)) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    private fun moduleContext(base: Context): Context {
        val info = checkNotNull(ModernXposedRuntime.activeModule()).moduleApplicationInfo
        val resources = base.packageManager.getResourcesForApplication(info)
        @Suppress("DEPRECATION") val isolated = Resources(resources.assets, base.resources.displayMetrics, Configuration(base.resources.configuration))
        val theme = isolated.newTheme().apply { applyStyle(info.theme.takeIf { it != 0 } ?: android.R.style.Theme_Material_Light_NoActionBar, true) }
        return object : ContextWrapper(base) {
            override fun getResources() = isolated
            override fun getAssets() = isolated.assets
            override fun getClassLoader() = GlassHostView::class.java.classLoader!!
            override fun getTheme() = theme
        }
    }
}
