package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

internal class LyriconFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_LYRICON
    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().lyriconEnabled) {
            return FeatureInstallResult.disabled("词幕集成已关闭")
        }
        return context.target.lyricon.install().toFeatureInstallResult()
    }
}
