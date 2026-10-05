package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

internal class PlayerAudioOutputFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_PLAYER_AUDIO_OUTPUT

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().iosMediaControlsEnabled) {
            return FeatureInstallResult.disabled("使用 iOS 媒体控制按钮已关闭")
        }
        return context.target.playerAudioOutput.install().toFeatureInstallResult()
    }
}
