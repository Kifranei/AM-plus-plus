package dev.amenhancer.module.model

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TitleCorrectionMode

data class ModuleSettings(
    val dualPaneEnabled: Boolean = true,
    /** Hide only the status bar while the tablet dual-pane player is expanded. */
    val dualPaneHideStatusBarEnabled: Boolean = false,
    /** Legacy storage key; Editorial Video suppression now follows dualPaneEnabled. */
    val disableEditorialVideoOnTablet: Boolean = true,
    val phoneLiquidGlassEnabled: Boolean = false,
    /** Distance in dp between the bottom-bar capsule and the screen bottom; glass-gated. */
    val phoneLiquidGlassBottomGapDp: Int = EnhancementDefaults.GLASS_BOTTOM_DP,
    /** Backdrop blur radius in dp shared by the nav panel and the mini-player. */
    val phoneLiquidGlassPanelBlurDp: Int = EnhancementDefaults.GLASS_PANEL_BLUR_DP.toInt(),
    /**
     * Collapses the bottom bar and mini player while the page scrolls down, detaching the
     * search tab into its own control. Read live, so it needs no host restart.
     */
    val phoneLiquidGlassCollapseEnabled: Boolean = true,
    /**
     * Retained for configuration compatibility with earlier builds. Tablet forms do not use
     * scroll collapse, regardless of this stored value, and expose no tablet collapse switch.
     */
    val phoneLiquidGlassTabletCollapseEnabled: Boolean = false,
    /**
     * Rebound amplitude while **collapsing**, as a percentage of the spring's own overshoot.
     *
     * Applies to **both** collapsed side controls — the capsule and the detached search button —
     * which share this one value so they cannot animate differently. 35 is the tuned default;
     * 0 removes the rebound and leaves the plain narrowing. Read live.
     */
    val phoneLiquidGlassCollapseReboundPercent: Int = EnhancementDefaults.GLASS_COLLAPSE_REBOUND_PERCENT,
    /**
     * Rebound amplitude while **expanding**, as a percentage.
     *
     * This is the expand spring's own overshoot, which makes each side button grow back **towards
     * the mini player** and settle. 100 is the reference (the expand spring, unfiltered); 0
     * disables the expand rebound. Read live.
     */
    val phoneLiquidGlassExpandReboundPercent: Int = EnhancementDefaults.GLASS_EXPAND_REBOUND_PERCENT,
    /**
     * Rebound amplitude of the **mini player** while collapsing and expanding, as a percentage.
     *
     * Its own setting rather than a share of the side buttons', because the mini's travel is
     * `ROW_DP + GAP_DP = 51dp` against the buttons' ~317dp: one percentage cannot serve both. At a
     * shared 100% the buttons would move 8dp (already clamped by the gap) while the mini moved
     * 1.4dp, so tuning for one visibly ruins the other. Read live.
     */
    val phoneLiquidGlassMiniReboundPercent: Int = EnhancementDefaults.GLASS_MINI_REBOUND_PERCENT,
    /**
     * How far past its resting height the mini player dips on the collapse spring's overshoot
     * before springing back, in dp. 0 disables the dip. Read live.
     */
    val phoneLiquidGlassMiniSinkBounceDp: Int = EnhancementDefaults.GLASS_MINI_SINK_BOUNCE_DP,
    /**
     * Draws the same liquid glass as the bottom bar behind Apple Music's floating dropdown menus
     * (e.g. the Library "⋮" menu) instead of the host's Gaussian blur. Gated by
     * [phoneLiquidGlassEnabled] and the host profile's `glassPopupMenu` capability. Read live.
     */
    val phoneLiquidGlassPopupMenuEnabled: Boolean = true,
    val futureBlurEnabled: Boolean = true,
    /** Enables the native rush-gradient adaptation for CJK karaoke lyrics. */
    val cjkKaraokeAnimationEnabled: Boolean = true,
    val navigationCompensationEnabled: Boolean = false,
    /** Restores native Data settings and the app's cellular availability predicate. */
    val forceCellularDataEntryEnabled: Boolean = false,
    /** Prepare native bottom-tab roots on startup; restart required, exact host opt-in only. */
    val startupPagePrewarmEnabled: Boolean = EnhancementDefaults.STARTUP_PAGE_PREWARM_ENABLED,
    val lyricBlurRadiusOffsetPx: Int = 0,
    /** Fixed logical density for Apple Music; 0 follows the system density. */
    val appleMusicDpiOverrideDpi: Int = FOLLOW_SYSTEM_APPLE_MUSIC_DPI,
    val titleCorrectionEnabled: Boolean = false,
    /** Selected metadata profile; ignored while [titleCorrectionEnabled] is false. */
    val titleCorrectionMode: TitleCorrectionMode = TitleCorrectionMode.ORIGINAL_HYPER,
    val customLyricsEnabled: Boolean = false,
    /** Enables background AMLL/Lunabeat/user-repository lyric completion. */
    val automaticLyricsEnabled: Boolean = true,
    val fontManifest: LyricsFontManifest = LyricsFontManifest.disabled(),
    val customLyricsManifest: CustomLyricsManifest = CustomLyricsManifest.empty(),
    val schemaVersion: Int = ModuleConstants.CONFIG_SCHEMA_VERSION,
) {
    companion object {
        const val MIN_LYRIC_BLUR_RADIUS_OFFSET_PX = -10
        const val MAX_LYRIC_BLUR_RADIUS_OFFSET_PX = 10
        const val FOLLOW_SYSTEM_APPLE_MUSIC_DPI = 0
        const val MIN_APPLE_MUSIC_DPI = 160
        const val MAX_APPLE_MUSIC_DPI = 640
        const val MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP = 0
        const val MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP = 48
        const val MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP = 0
        const val MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP = 24
        const val MIN_PHONE_LIQUID_GLASS_COLLAPSE_REBOUND_PERCENT = 0
        const val MAX_PHONE_LIQUID_GLASS_COLLAPSE_REBOUND_PERCENT = 200
        const val MIN_PHONE_LIQUID_GLASS_EXPAND_REBOUND_PERCENT = 0
        const val MAX_PHONE_LIQUID_GLASS_EXPAND_REBOUND_PERCENT = 200
        const val MIN_PHONE_LIQUID_GLASS_MINI_REBOUND_PERCENT = 0
        const val MAX_PHONE_LIQUID_GLASS_MINI_REBOUND_PERCENT = 200
        const val MIN_PHONE_LIQUID_GLASS_MINI_SINK_BOUNCE_DP = 0
        const val MAX_PHONE_LIQUID_GLASS_MINI_SINK_BOUNCE_DP = 16

        fun isValidAppleMusicDpi(value: Int): Boolean =
            value == FOLLOW_SYSTEM_APPLE_MUSIC_DPI || value in MIN_APPLE_MUSIC_DPI..MAX_APPLE_MUSIC_DPI

        fun normalizeAppleMusicDpi(value: Int): Int =
            value.takeIf(::isValidAppleMusicDpi) ?: FOLLOW_SYSTEM_APPLE_MUSIC_DPI

        fun normalizePhoneLiquidGlassBottomGapDp(value: Int): Int =
            value.coerceIn(MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP, MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP)

        fun normalizePhoneLiquidGlassPanelBlurDp(value: Int): Int =
            value.coerceIn(MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP, MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP)

        fun normalizePhoneLiquidGlassCollapseReboundPercent(value: Int): Int =
            value.coerceIn(
                MIN_PHONE_LIQUID_GLASS_COLLAPSE_REBOUND_PERCENT,
                MAX_PHONE_LIQUID_GLASS_COLLAPSE_REBOUND_PERCENT,
            )

        fun normalizePhoneLiquidGlassExpandReboundPercent(value: Int): Int =
            value.coerceIn(
                MIN_PHONE_LIQUID_GLASS_EXPAND_REBOUND_PERCENT,
                MAX_PHONE_LIQUID_GLASS_EXPAND_REBOUND_PERCENT,
            )

        fun normalizePhoneLiquidGlassMiniReboundPercent(value: Int): Int =
            value.coerceIn(
                MIN_PHONE_LIQUID_GLASS_MINI_REBOUND_PERCENT,
                MAX_PHONE_LIQUID_GLASS_MINI_REBOUND_PERCENT,
            )

        fun normalizePhoneLiquidGlassMiniSinkBounceDp(value: Int): Int =
            value.coerceIn(
                MIN_PHONE_LIQUID_GLASS_MINI_SINK_BOUNCE_DP,
                MAX_PHONE_LIQUID_GLASS_MINI_SINK_BOUNCE_DP,
            )
    }
}

/** Shared, Android-free description of the font file selected by the user. */
data class LyricsFontManifest(
    val enabled: Boolean = false,
    val fileId: String = "",
    val displayName: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
) {
    companion object {
        fun disabled(): LyricsFontManifest = LyricsFontManifest()
    }
}

/** One user-managed Apple Music ID -> TTML file mapping. */
data class CustomLyricsEntry(
    val appleMusicId: Long,
    val displayName: String,
    val fileId: String,
    val sizeBytes: Long,
    val sha256: String,
    val source: String,
    val enabled: Boolean = true,
)
/** Small index shared through remote preferences; TTML bodies stay in remote files. */
data class CustomLyricsManifest(
    val entries: List<CustomLyricsEntry> = emptyList(),
) {
    companion object {
        fun empty(): CustomLyricsManifest = CustomLyricsManifest()
    }
}

object CustomLyricsSources {
    const val MANUAL = "manual"
    const val AUTO_CACHE = "auto-cache"
    const val AMLL = "amll-ttml-db"
    const val AM_LYRICS = "am-lyrics"
    const val LUNABEAT = "lunabeat-ttml-hub"
}

enum class FeatureState {
    ACTIVE,
    DISABLED,
    UNSUPPORTED,
    DEGRADED,
    FAILED,
}

data class FeatureHealth(
    val feature: String,
    val state: FeatureState,
    val message: String,
    val targetVersion: String = "",
)
