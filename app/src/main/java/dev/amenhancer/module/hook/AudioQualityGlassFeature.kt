package dev.amenhancer.module.hook

import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.amenhancer.glass.DialogWindowBackdrop
import dev.amenhancer.glass.GlassAudioQualityCard
import dev.amenhancer.glass.GlassHostView
import dev.amenhancer.module.ModuleConstants
import kotlin.math.roundToInt

internal class AudioQualityGlassFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_AUDIO_QUALITY_GLASS

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().phoneLiquidGlassEnabled) return FeatureInstallResult.disabled()
        if (Build.VERSION.SDK_INT < 33) return FeatureInstallResult.unsupported("完整玻璃折射需要 Android 13+")
        return context.target.audioQualityDialog.install { surface ->
            if (!context.config.settings().phoneLiquidGlassEnabled) false else present(surface)
        }.toFeatureInstallResult()
    }

    private fun present(surface: AudioQualityDialogSurface): Boolean {
        val activity = surface.activity
        val dialog = surface.dialog
        val window = dialog.window ?: return false
        if (activity.isFinishing || activity.isDestroyed || dialog.isShowing) return false
        val info = checkNotNull(ModernXposedRuntime.activeModule()).moduleApplicationInfo
        val resources = activity.packageManager.getResourcesForApplication(info)
        @Suppress("DEPRECATION")
        val isolated = Resources(resources.assets, activity.resources.displayMetrics, Configuration(activity.resources.configuration))
        val theme = isolated.newTheme().apply {
            applyStyle(info.theme.takeIf { it != 0 } ?: android.R.style.Theme_Material_Light_NoActionBar, true)
        }
        val moduleContext = object : ContextWrapper(activity) {
            override fun getResources() = isolated
            override fun getAssets() = isolated.assets
            override fun getTheme() = theme
            override fun getClassLoader() = GlassHostView::class.java.classLoader!!
        }
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val width = minOf(dp(360), activity.window.decorView.width - dp(32))
        val height = minOf(dp(if (surface.source.isBlank()) 285 else 355), activity.window.decorView.height - dp(96))
        if (width <= 0 || height <= 0) return false
        val frame = FrameLayout(moduleContext).apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val glass = GlassHostView(moduleContext, bleedDp = 0)
        frame.addView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val backdrop = DialogWindowBackdrop(activity, glass)
        glass.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { backdrop.close() }
        })
        val nativeContent = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val nativeChildren = (0 until nativeContent.childCount).map(nativeContent::getChildAt)
        val attributes = android.view.WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
        val background = window.decorView.background
        val settingsLabel = surface.settingsLabel.toString()
        glass.content { GlassAudioQualityCard(backdrop, surface.badge, surface.title.toString(), surface.encoding.toString(),
            surface.source.toString(), settingsLabel, surface.doneLabel.toString(), surface.openSettings, surface.done) }
        fun restore() {
            nativeContent.removeAllViews()
            nativeChildren.forEach { child -> (child.parent as? ViewGroup)?.removeView(child); nativeContent.addView(child) }
            window.setBackgroundDrawable(background)
            window.attributes = attributes
            backdrop.close()
            if (!activity.isFinishing && !activity.isDestroyed && !dialog.isShowing) surface.show()
        }
        try {
            backdrop.capture { ready ->
                if (activity.isFinishing || activity.isDestroyed) { backdrop.close(); return@capture }
                if (!ready) { restore(); return@capture }
                runCatching {
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setContentView(frame)
                    window.setLayout(width, height)
                    window.setDimAmount(.18f)
                    window.setWindowAnimations(0)
                    surface.show()
                    ModernXposedRuntime.log("audio_quality_glass: first window shown with glass content")
                }.onFailure { restore(); ModernXposedRuntime.log("audio quality glass popup failed", it) }
            }
        } catch (error: Throwable) { restore(); throw error }
        return true
    }
}
