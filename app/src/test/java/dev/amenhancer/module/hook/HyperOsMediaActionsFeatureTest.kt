package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsMediaActionsFeatureTest {
    @Test
    fun `xiaomi family detection covers xiaomi redmi poco only`() {
        assertTrue(HyperOsMediaActionsFeature.isXiaomiFamily("Xiaomi", "Xiaomi"))
        assertTrue(HyperOsMediaActionsFeature.isXiaomiFamily("Xiaomi", "Redmi"))
        assertTrue(HyperOsMediaActionsFeature.isXiaomiFamily("xiaomi", "POCO"))
        assertFalse(HyperOsMediaActionsFeature.isXiaomiFamily("samsung", "samsung"))
        assertFalse(HyperOsMediaActionsFeature.isXiaomiFamily(null, null))
    }

    @Test
    fun `labels anchor on Apple Music action ids`() {
        assertEquals("收藏", HyperOsMediaActionsFeature.labelFor(HyperOsMediaActionsFeature.ACTION_FAVORITE))
        assertEquals("随机播放", HyperOsMediaActionsFeature.labelFor(HyperOsMediaActionsFeature.ACTION_SHUFFLE))
        assertEquals("循环播放", HyperOsMediaActionsFeature.labelFor(HyperOsMediaActionsFeature.ACTION_REPEAT))
        assertEquals("custom", HyperOsMediaActionsFeature.labelFor("custom"))
    }
}
