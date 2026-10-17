package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import org.junit.Assert.assertTrue
import org.junit.Test

/** A glass-enabled profile must declare every role the code requires, or the whole glass install fails. */
class FragmentChromeProfileInventoryTest {
    @Test fun everyGlassProfileDeclaresTheCompleteChromeInventory() {
        val checked = AppleMusicHostProfiles.all.filter { it.capability("glass") && it.document.has("fragmentChrome") }
        assertTrue(checked.isNotEmpty())
        checked.forEach { profile ->
            val members = profile.document.getJSONObject("fragmentChrome").getJSONObject("members")
            FragmentChromeInventory.required.methods.forEach {
                assertTrue("${profile.id}: fragmentChrome.methods.$it missing", members.getJSONObject("methods").has(it))
            }
            FragmentChromeInventory.required.fields.forEach {
                assertTrue("${profile.id}: fragmentChrome.fields.$it missing", members.getJSONObject("fields").has(it))
            }
        }
    }

    @Test fun collapseCapabilityRequiresSheetSettleSeams() {
        AppleMusicHostProfiles.all.filter { it.capability("glassScrollCollapse") }.forEach { profile ->
            val methods = profile.document.getJSONObject("fragmentChrome").getJSONObject("members").getJSONObject("methods")
            assertTrue(profile.id, methods.has("settleSheet") && methods.has("refreshCollapsedOffset"))
        }
    }

    @Test fun popupMenuCapabilityRequiresItsContractAndBlurClass() {
        val checked = AppleMusicHostProfiles.all.filter { it.capability("glassPopupMenu") }
        assertTrue("1609 and 1611 must declare glassPopupMenu",
            checked.map { it.versionCode }.containsAll(listOf(1609, 1611)))
        checked.forEach { profile ->
            assertTrue("${profile.id}: needs glass + fragmentChrome", profile.capability("glass") && profile.document.has("fragmentChrome"))
            val spec = profile.document.getJSONObject("glassPopupMenu")
            listOf("blurViewId", "backdropSource", "cornerRadiusDimen").forEach {
                assertTrue("${profile.id}: glassPopupMenu.$it missing", spec.optString(it).isNotEmpty())
            }
            assertTrue(profile.id, profile.document.getJSONObject("fragmentChrome").optString("blurClass").isNotEmpty())
        }
    }
}
