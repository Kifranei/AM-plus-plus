package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CollectionPageMetadataPerformanceTest {
    class SongEntity
    class RowModel(val entity: SongEntity = SongEntity())

    @Before
    fun clearCache() {
        CollectionPageRowEntityFieldCache.clearForTests()
    }

    @Test
    fun settleDelayIsLongEnoughToMissTheEnterAnimationWindow() {
        assertTrue(COLLECTION_PAGE_METADATA_SETTLE_MS >= 300L)
        assertTrue(COLLECTION_PAGE_METADATA_SETTLE_MS <= 1_000L)
    }

    @Test
    fun pageResolutionFilterDropsAlreadyCorrectedIds() {
        val resolved = setOf("101", "102")
        val filtered = collectionPageIdsNeedingResolution(
            mediaIds = listOf("101", " 102 ", "103", "103", "bad"),
            shouldRequestOverride = { it !in resolved },
        )
        assertEquals(listOf("103"), filtered)
    }

    @Test
    fun mediaApiAliasAlreadyAppliedSkipsMatchingSongTexts() {
        val alias = AppleInternalCatalogResolver.Alias(
            title = "晴天",
            artist = "周杰伦",
            language = "zh-Hans-CN",
            album = "叶惠美",
        )
        assertTrue(
            mediaApiAliasAlreadyApplied(
                kind = InAppLibraryEntityKind.SONG,
                currentName = "晴天",
                currentArtist = "周杰伦",
                currentAlbum = "叶惠美",
                alias = alias,
            ),
        )
        assertFalse(
            mediaApiAliasAlreadyApplied(
                kind = InAppLibraryEntityKind.SONG,
                currentName = "Sunny Day",
                currentArtist = "周杰伦",
                currentAlbum = "叶惠美",
                alias = alias,
            ),
        )
    }

    @Test
    fun rowEntityFieldWalkIsCachedPerModelClass() {
        val first = CollectionPageRowEntityFieldCache.fieldFor(
            RowModel::class.java,
            SongEntity::class.java,
        )
        val second = CollectionPageRowEntityFieldCache.fieldFor(
            RowModel::class.java,
            SongEntity::class.java,
        )
        assertSame(first, second)
        val model = RowModel()
        assertSame(model.entity, collectionPageRowEntity(model, SongEntity::class.java))
    }
}
