package dev.amenhancer.module.ui

import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSettingsDraftTest {
    @Test
    fun `integration toggles retain pending edits and each other across failed saves`() {
        var stored = ModuleSettings()
        var writable = false
        val draft = EmbeddedSettingsDraft(stored) { next ->
            if (writable) stored = next
            writable
        }
        val pending = stored.copy(dualPaneEnabled = false, customLyricsEnabled = true)
        assertFalse(draft.update(pending))
        assertFalse(draft.updateIosMediaControls(true))
        assertFalse(draft.updateLyricon(true))
        assertFalse(draft.updateUnrestrictedLyricsSharing(true))
        assertEquals(ModuleSettings(), stored)
        writable = true
        assertTrue(draft.save())
        assertEquals(pending.copy(iosMediaControlsEnabled = true, lyriconEnabled = true, unrestrictedLyricsSharingEnabled = true), stored)
        assertTrue(draft.updateIosMediaControls(false))
        assertEquals(pending.copy(lyriconEnabled = true, unrestrictedLyricsSharingEnabled = true), stored)
        assertTrue(draft.updateLyricon(false))
        assertEquals(pending.copy(unrestrictedLyricsSharingEnabled = true), stored)
        assertTrue(draft.updateUnrestrictedLyricsSharing(false))
        assertEquals(pending, stored)
    }

    @Test
    fun `cellular toggle preserves pending changes after another row fails to save`() {
        var stored = ModuleSettings()
        var writable = false
        val draft = EmbeddedSettingsDraft(stored) { next ->
            if (writable) stored = next
            writable
        }
        val pending = stored.copy(dualPaneEnabled = false, lyricBlurRadiusOffsetPx = 4)

        assertFalse(draft.update(pending))
        assertEquals(ModuleSettings(), stored)

        writable = true
        assertTrue(draft.updateCellularDataEntry(true))
        assertEquals(pending.copy(forceCellularDataEntryEnabled = true), stored)
        assertEquals(stored, draft.settings)
    }

    @Test
    fun `save button retries all changes when the cellular toggle also fails to save`() {
        var stored = ModuleSettings()
        var writable = false
        val draft = EmbeddedSettingsDraft(stored) { next ->
            if (writable) stored = next
            writable
        }
        val pending = stored.copy(futureBlurEnabled = false, titleCorrectionEnabled = true)

        assertFalse(draft.update(pending))
        assertFalse(draft.updateCellularDataEntry(true))
        assertEquals(ModuleSettings(), stored)
        assertEquals(pending.copy(forceCellularDataEntryEnabled = true), draft.settings)

        writable = true
        assertTrue(draft.save())
        assertEquals(pending.copy(forceCellularDataEntryEnabled = true), stored)
    }

    @Test
    fun `cellular toggle uses the latest draft after successful changes on the same page`() {
        var stored = ModuleSettings()
        val draft = EmbeddedSettingsDraft(stored) { next ->
            stored = next
            true
        }
        val updated = stored.copy(dualPaneEnabled = false, lyricBlurRadiusOffsetPx = 3)

        assertTrue(draft.update(updated))
        assertTrue(draft.updateCellularDataEntry(true))
        assertEquals(updated.copy(forceCellularDataEntryEnabled = true), stored)

        val latest = draft.settings.copy(lyricBlurRadiusOffsetPx = 5)
        assertTrue(draft.update(latest))
        assertTrue(draft.updateCellularDataEntry(false))
        assertEquals(updated.copy(lyricBlurRadiusOffsetPx = 5), stored)
    }
}
