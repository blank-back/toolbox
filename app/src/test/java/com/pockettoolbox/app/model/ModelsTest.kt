package com.pockettoolbox.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelsTest {
    @Test
    fun parsesBvFromCodeAndUrl() {
        assertEquals("BV1GJ411x7h7", BiliId.parse("BV1GJ411x7h7").value)
        assertEquals(
            "BV1GJ411x7h7",
            BiliId.parse("https://www.bilibili.com/video/BV1GJ411x7h7?p=2").value,
        )
        assertEquals(MediaKind.VIDEO, BiliId.parse("BV1GJ411x7h7").kind)
    }

    @Test
    fun parsesAuCaseInsensitively() {
        assertEquals(BiliId("au13598", MediaKind.AUDIO), BiliId.parse("AU 13598"))
        assertEquals(BiliId("au13598", MediaKind.AUDIO), BiliId.parse("https://bilibili.com/audio/au13598"))
    }

    @Test
    fun rejectsUnsupportedInput() {
        assertThrows(IllegalArgumentException::class.java) { BiliId.parse("av123") }
        assertThrows(IllegalArgumentException::class.java) { BiliId.parse("not a media code") }
    }

    @Test
    fun sanitizesFileNames() {
        assertEquals("bad_name_", safeFileName("bad:name? "))
        assertEquals("bilibili", safeFileName("..."))
        assertEquals(80, safeFileName("a".repeat(120)).length)
    }

    @Test
    fun buildsDownloadNamesAndSourceLinks() {
        val info = BiliMediaInfo(
            id = BiliId("BV1GJ411x7h7", MediaKind.VIDEO),
            title = "demo:title",
            author = "author",
            durationSeconds = null,
            coverBytes = byteArrayOf(),
            coverMimeType = "image/webp",
        )
        assertEquals("demo_title.webp", info.downloadSpec(DownloadKind.COVER).fileName)
        assertEquals("demo_title.mp4", info.downloadSpec(DownloadKind.VIDEO).fileName)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", info.id.pageUrl)
    }

    @Test
    fun buildsMusicDisplayValuesAndCacheKeys() {
        val track = MusicTrack(
            id = "123",
            source = "netease",
            name = "Demo",
            artists = listOf("Artist A", "Artist B"),
            album = "Album",
        )
        assertEquals("Artist A / Artist B", track.artistText)
        assertEquals("netease:123", track.cacheKey)
    }
}
