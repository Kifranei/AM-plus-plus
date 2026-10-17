package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
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
    private const val MAX_SKIP_LOGS = 3
    private val mounts = Collections.synchronizedMap(IdentityHashMap<View, Mount>())
    private var installed = false
    private var failures = 0
    private val skipLogCounts = Collections.synchronizedMap(HashMap<String, Int>())
    /** True only while the host composes its dropdown-menu content lambda on this thread. */
    private val inMenuContent = object : ThreadLocal<Boolean>() { override fun initialValue() = false }
    @Volatile private var resumedActivity: WeakReference<Activity>? = null
    @Volatile private var supplyCount = 0

    /** Logs a not-mounted reason at most [MAX_SKIP_LOGS] times so a busy host cannot flood the diag log. */
    private fun skip(reason: String) {
        val n = skipLogCounts.merge(reason, 1) { a, b -> a + b } ?: 1
        if (n <= MAX_SKIP_LOGS) GlassDiagLog.i("$TAG: not mounted - $reason (#$n)")
    }

    fun supports(build: TargetBuild): Boolean = AppleMusicHostProfiles.find(
        build.packageName, build.versionName, build.versionCode,
    )?.let {
        it.productionEnabled && it.capability("glass") && it.capability("glassPopupMenu") &&
            it.family == "fragment-content" && it.document.has("fragmentChrome") && it.document.has("glassPopupMenu")
    } == true

    fun install(loader: ClassLoader, build: TargetBuild, config: TargetConfigClient, activity: Activity? = null) {
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
            trackResumedActivity(activity)
            runCatching { installBlurTargetSupply(loader, spec, config) }
                .onFailure { GlassDiagLog.e("$TAG: library blur-target supply install failed; Library menu stays native", it) }
            GlassDiagLog.i("$TAG: hooks installed on $blurClassName (controller field ${controllerField.name}:${controllerField.type.name})")
        } catch (error: Throwable) {
            GlassDiagLog.e("$TAG: installation failed; dropdown menus stay native", error)
        }
    }


    private fun trackResumedActivity(activity: Activity?) {
        if (activity == null) return
        resumedActivity = WeakReference(activity)
        runCatching {
            activity.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) { resumedActivity = WeakReference(activity) }
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) { if (resumedActivity?.get() === activity) resumedActivity = null }
            })
        }.onFailure { GlassDiagLog.e("$TAG: activity tracking unavailable", it) }
    }

    /**
     * Library root cause (static, 1611): the Library page is a Compose fragment (Xb.f.PageContent) that never provides
     * the `BlurTarget` CompositionLocal (Qb.o.a, default null). The menu content (Aa.G) therefore takes its
     * `activity == null || blurTarget == null` branch: a Compose Haze blur (nh.h.b, HazeState Qb.o.b) - NO
     * `option_button_blur` BlurView is ever inflated, so the BlurView hook above has nothing to attach to. Home-style
     * pages provide the BlurTarget (Aa.H / bb.w / room / search) and get the BlurView we glassify.
     *
     * Fix without touching host menu code: while Aa.G composes its menu content, answer the default-value read of that
     * one CompositionLocal with the activity's `blur_target` view. The host then inflates the same BlurView it uses
     * everywhere else and the existing mount path glassifies it. Reads outside menu content are untouched.
     */
    private fun installBlurTargetSupply(loader: ClassLoader, spec: org.json.JSONObject, config: TargetConfigClient) {
        val keys = listOf("menuContentClass", "blurTargetLocalClass", "blurTargetLocalField",
            "compositionLocalClass", "defaultHolderField", "defaultHolderReadMethod")
        val missing = keys.filter { !spec.has(it) }
        if (missing.isNotEmpty()) {
            GlassDiagLog.i("$TAG: profile has no blur-target supply mapping ($missing); pages that do not provide a BlurTarget (Library) stay native")
            return
        }
        val menuContent = Class.forName(spec.getString("menuContentClass"), false, loader)
        val invoke = menuContent.declaredMethods.singleOrNull { m ->
            m.name == "invoke" && m.parameterTypes.size == 3 && !m.isBridge && !m.isSynthetic
        } ?: error("menu content invoke(menu, composer, int) not unique in ${menuContent.name}")
        val localOwner = Class.forName(spec.getString("blurTargetLocalClass"), false, loader)
        val local = localOwner.getDeclaredField(spec.getString("blurTargetLocalField")).apply { isAccessible = true }.get(null)
            ?: error("BlurTarget CompositionLocal is null")
        val baseClass = Class.forName(spec.getString("compositionLocalClass"), false, loader)
        val holderField = baseClass.getDeclaredField(spec.getString("defaultHolderField")).apply { isAccessible = true }
        val holder = holderField.get(local) ?: error("CompositionLocal default holder is null")
        val read: Method = holder.javaClass.declaredMethods.singleOrNull { m ->
            m.name == spec.getString("defaultHolderReadMethod") && m.parameterTypes.size == 1 && m.returnType == Any::class.java
        } ?: error("default holder read method not unique in ${holder.javaClass.name}")

        ModernXposedRuntime.hookMethod(invoke, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { inMenuContent.set(true) }
            override fun afterHookedMethod(param: MethodHookParam) { inMenuContent.set(false) }
        })
        ModernXposedRuntime.hookMethod(read, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== holder || inMenuContent.get() != true) return
                inMenuContent.set(false) // supply once per menu composition
                try {
                    val settings = config.settings()
                    if (!settings.phoneLiquidGlassEnabled || !settings.phoneLiquidGlassPopupMenuEnabled) {
                        skip("popup-menu glass setting off; BlurTarget not supplied"); return
                    }
                    if (failures >= MAX_FAILURES) { skip("failure cap reached; BlurTarget not supplied"); return }
                    val activity = resumedActivity?.get()
                    if (activity == null) { skip("no resumed activity; BlurTarget not supplied"); return }
                    val id = activity.resources.getIdentifier("blur_target", "id", activity.packageName)
                    val target = if (id == 0) null else activity.findViewById<View>(id)
                    if (target == null) { skip("activity has no blur_target view; BlurTarget not supplied"); return }
                    param.result = target
                    supplyCount++
                    if (supplyCount <= 5) GlassDiagLog.i("$TAG: page provides no BlurTarget (Library); supplied activity blur_target for menu content (#$supplyCount)")
                } catch (error: Throwable) {
                    GlassDiagLog.e("$TAG: BlurTarget supply failed; menu stays native", error)
                }
            }
        })
        GlassDiagLog.i("$TAG: blur-target supply hooks installed (${menuContent.name}.invoke, ${holder.javaClass.name}.${read.name})")
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
        if (failures >= MAX_FAILURES) { skip("failure cap reached ($failures/$MAX_FAILURES)"); return }
        if (mounts.containsKey(view)) { skip("BlurView already mounted (re-attach of the same view)"); return }
        val settings = config.settings()
        if (!settings.phoneLiquidGlassEnabled) { skip("phone liquid glass disabled"); return }
        if (!settings.phoneLiquidGlassPopupMenuEnabled) { skip("popup-menu glass disabled"); return }
        val context = view.context
        val optionBlurId = context.resources.getIdentifier(ids.blurView, "id", context.packageName)
        if (optionBlurId == 0 || view.id != optionBlurId) return // unrelated BlurView (not ours): stay silent
        val activity = activityOf(context)
        if (activity == null) { skip("option_button_blur has no Activity context (${context.javaClass.name})"); return }
        val root = view.rootView
        if (root == null) { skip("option_button_blur has no root view"); return }
        if (root === activity.window.decorView) { skip("option_button_blur lives in the activity window (toolbar button), not a popup"); return }
        val targetId = context.resources.getIdentifier(ids.backdropSource, "id", context.packageName)
        val source = if (targetId == 0) null else activity.findViewById<View>(targetId)
        if (source == null) {
            skip("${ids.backdropSource} not found in activity; menu stays native")
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
        GlassDiagLog.i("$TAG: Gaussian blur suppressed for popup (root=${root.javaClass.name} size=${view.width}x${view.height}); mounting glass")
        view.post {
            if (mounts[view] !== mount) return@post
            try { attachWhenSized(mount, activity, source, settings.phoneLiquidGlassPanelBlurDp, ids) }
            catch (error: Throwable) { fail(view, error) }
        }
    }

    /** The popup may still be 0x0 when attached (items are Compose siblings measured later): wait for a real size. */
    private fun attachWhenSized(mount: Mount, activity: Activity, source: View, blurDp: Int, ids: Ids) {
        val view = mount.view
        if (!view.isAttachedToWindow) { skip("popup detached before glass could mount"); return }
        if (view.width > 0 && view.height > 0) { attachGlass(mount, activity, source, blurDp, ids); return }
        GlassDiagLog.i("$TAG: popup not laid out yet (${view.width}x${view.height}); waiting for size")
        val waiter = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, oR: Int, ob: Int) {
                if (r - l <= 0 || b - t <= 0) return
                v.removeOnLayoutChangeListener(this)
                if (mounts[view] !== mount) return
                try { attachGlass(mount, activity, source, blurDp, ids) } catch (error: Throwable) { fail(view, error) }
            }
        }
        view.addOnLayoutChangeListener(waiter)
        view.postDelayed({
            if (mounts[view] === mount && mount.glass == null) {
                view.removeOnLayoutChangeListener(waiter)
                GlassDiagLog.i("$TAG: popup still has no size after ${READY_TIMEOUT_MS}ms; using translucent fallback fill")
                fallbackFill(view, activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            }
        }, READY_TIMEOUT_MS)
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
        // The menu can grow/shrink after the first frame (e.g. "Edit categories" appearing on recomposition, or the
        // popup window being re-positioned): re-sample and redraw the glass whenever the popup's bounds change.
        val resize = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, oR, ob ->
            if (r - l != oR - ol || b - t != ob - ot || l != ol || t != ot) {
                glass.invalidate()
                view.postInvalidateOnAnimation()
                GlassDiagLog.i("$TAG: popup bounds changed ${oR - ol}x${ob - ot} -> ${r - l}x${b - t}; glass re-sampled")
            }
        }
        view.addOnLayoutChangeListener(resize)
        mount.resizeListener = resize
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
        mount.resizeListener?.let { l -> runCatching { mount.view.removeOnLayoutChangeListener(l) } }
        mount.resizeListener = null
        mount.glass = null
        mount.backdrop = null
    }

    private class Ids(val blurView: String, val backdropSource: String, val radiusDimen: String)

    private class Mount(val view: ViewGroup, val field: Field, val original: Any?) {
        var glass: GlassHostView? = null
        var backdrop: ViewBackdrop? = null
        var resizeListener: View.OnLayoutChangeListener? = null
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
