package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test

class WallSettingsTest {
    @Test fun acceptsDisabledPageAndHttpPages() {
        assertEquals(WallSettings.Config(), WallSettings.parse("", "1", "0"))
        assertEquals(WallSettings.Config("https://example.com/?a=1&b=2", 1.5f, 60),
            WallSettings.parse(" https://example.com/?a=1&b=2 ", "1.5", "60"))
        assertEquals("http://192.168.1.1:8080", WallSettings.parse("http://192.168.1.1:8080", "1", "0").url)
    }

    @Test fun rejectsUnsafeUrlsAndInvalidNumericValues() {
        for (url in listOf("javascript:alert(1)", "file:///sdcard/test.html", "https://", "https://user:pass@host")) {
            assertTrue(runCatching { WallSettings.parse(url, "1", "0") }.isFailure)
        }
        for (zoom in listOf("NaN", "Infinity", "0", "6", "oops")) {
            assertTrue(runCatching { WallSettings.parse("", zoom, "0") }.isFailure)
        }
        for (refresh in listOf("-1", "86401", "1.5", "oops")) {
            assertTrue(runCatching { WallSettings.parse("", "1", refresh) }.isFailure)
        }
    }
}
