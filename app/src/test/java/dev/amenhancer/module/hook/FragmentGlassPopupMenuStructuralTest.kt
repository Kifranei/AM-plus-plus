package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the popup-menu glass seam: fail-open, live toggle, diagnostics and the capability gate. */
class FragmentGlassPopupMenuStructuralTest {
    private fun read(vararg paths: String): String =
        paths.map(::File).first { it.exists() }.readText()

    private val menu = read(
        "src/main/java/dev/amenhancer/module/hook/FragmentGlassPopupMenu.kt",
        "app/src/main/java/dev/amenhancer/module/hook/FragmentGlassPopupMenu.kt",
    )

    @Test fun gatedByProfileCapabilityAndLiveToggle() {
        assertTrue(menu.contains("capability(\"glassPopupMenu\")"))
        assertTrue(menu.contains("phoneLiquidGlassPopupMenuEnabled"))
        assertTrue(menu.contains("phoneLiquidGlassEnabled"))
    }

    @Test fun everyEntryPointIsFailOpenAndLogged() {
        assertTrue(menu.contains("catch (error: Throwable) { fail(view, error) }"))
        assertTrue(menu.contains("GlassDiagLog.e(\"\$TAG: installation failed"))
        assertTrue(menu.contains("GlassDiagLog.i(\"\$TAG: glass mounted"))
        assertTrue(menu.contains("fallbackFill"))
    }

    @Test fun onlyPopupWindowBlurViewsAreTouched() {
        assertTrue(menu.contains("root === activity.window.decorView"))
        assertTrue(menu.contains("view.id != optionBlurId"))
    }

    @Test fun installedFromGlassRuntimeInsideItsOwnFailureDomain() {
        val runtime = read(
            "src/main/java/dev/amenhancer/module/hook/FragmentGlassRuntime.kt",
            "app/src/main/java/dev/amenhancer/module/hook/FragmentGlassRuntime.kt",
        )
        assertTrue(runtime.contains("runCatching { FragmentGlassPopupMenu.install("))
    }

    @Test fun libraryBlurTargetSupplyIsScopedToMenuContentAndFailOpen() {
        assertTrue(menu.contains("installBlurTargetSupply"))
        assertTrue(menu.contains("inMenuContent.get() != true"))
        assertTrue(menu.contains("param.thisObject !== holder"))
        assertTrue(menu.contains("supply once per menu composition"))
        assertTrue(menu.contains("BlurTarget supply failed; menu stays native"))
    }

    @Test fun notMountedReasonsAreDiagnosed() {
        listOf("not mounted - ", "toolbar button), not a popup", "not found in activity", "popup not laid out yet",
            "popup bounds changed", "failure cap reached", "popup-menu glass disabled").forEach { assertTrue(it, menu.contains(it)) }
    }

    @Test fun profile1611MapsTheBlurTargetSupply() {
        val json = read("../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1611.json",
            "host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1611.json")
        listOf("menuContentClass", "blurTargetLocalClass", "blurTargetLocalField", "compositionLocalClass",
            "defaultHolderField", "defaultHolderReadMethod").forEach { assertTrue(it, json.contains("\"$it\"")) }
    }
}
