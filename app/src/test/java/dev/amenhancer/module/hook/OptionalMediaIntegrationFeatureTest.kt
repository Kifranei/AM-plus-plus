package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.config.ModuleSettingsSchema
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureState
import dev.amenhancer.module.model.ModuleSettings
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class OptionalMediaIntegrationFeatureTest {
    @Test
    fun `disabled integrations never install hooks or connect to the provider`() {
        val context = context(ModuleSettings(),
            PlayerAudioOutputTarget { error("Output hooks must not be installed") },
            LyriconTarget { error("Provider must not be registered") },
        )
        assertEquals(FeatureState.DISABLED, PlayerAudioOutputFeature().install(context).state)
        assertEquals(FeatureState.DISABLED, LyriconFeature().install(context).state)
    }

    @Test
    fun `all toggle combinations install only their selected capability`() {
        for (ios in listOf(false, true)) for (lyricon in listOf(false, true)) {
            var outputCalls = 0
            var lyriconCalls = 0
            val context = context(ModuleSettings(iosMediaControlsEnabled = ios, lyriconEnabled = lyricon),
                PlayerAudioOutputTarget { outputCalls++; TargetCapabilityInstall.Active("Output installed") },
                LyriconTarget { lyriconCalls++; TargetCapabilityInstall.Active("Provider connected") },
            )
            assertEquals(if (ios) FeatureState.ACTIVE else FeatureState.DISABLED,
                PlayerAudioOutputFeature().install(context).state)
            assertEquals(if (lyricon) FeatureState.ACTIVE else FeatureState.DISABLED,
                LyriconFeature().install(context).state)
            assertEquals(if (ios) 1 else 0, outputCalls)
            assertEquals(if (lyricon) 1 else 0, lyriconCalls)
        }
    }

    @Test
    fun `enabled integrations preserve unsupported capability diagnostics`() {
        val context = context(ModuleSettings(iosMediaControlsEnabled = true, lyriconEnabled = true),
            PlayerAudioOutputTarget { TargetCapabilityInstall.Unsupported("No output contract") },
            LyriconTarget { TargetCapabilityInstall.Unsupported("No lyric contract") },
        )
        val output = PlayerAudioOutputFeature().install(context)
        val lyricon = LyriconFeature().install(context)
        assertEquals(FeatureState.UNSUPPORTED, output.state)
        assertEquals("No output contract", output.message)
        assertEquals(FeatureState.UNSUPPORTED, lyricon.state)
        assertEquals("No lyric contract", lyricon.message)
    }

    private fun context(
        settings: ModuleSettings,
        output: PlayerAudioOutputTarget,
        lyricon: LyriconTarget,
    ): HookContext {
        val values = ModuleSettingsSchema.encodeOrdinarySettings(settings)
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAll" -> values
                else -> null
            }
        } as SharedPreferences
        return HookContext(TargetConfigClient(preferences), TargetAdaptation(
            identity = "test host",
            dualPane = DualPaneTarget { error("unused") },
            editorialVideo = EditorialVideoTarget { error("unused") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget { error("unused") },
            playerAudioOutput = output,
            lyricon = lyricon,
        ))
    }
}
