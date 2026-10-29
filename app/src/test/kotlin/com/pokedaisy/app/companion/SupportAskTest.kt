package com.pokedaisy.app.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The support window's timing: 5 hours of play first, 5 more after REMIND ME LATER, never once answered. */
class SupportAskTest {
    private val h = SupportAsk.HOUR

    @Test fun `asks after 5 hours of play`() {
        assertFalse(SupportAsk.due(played = 4 * h, askAt = SupportAsk.FIRST_ASK, done = false))
        assertTrue(SupportAsk.due(played = 5 * h, askAt = SupportAsk.FIRST_ASK, done = false))
        assertTrue(SupportAsk.due(played = 40 * h, askAt = SupportAsk.FIRST_ASK, done = false))
    }

    @Test fun `remind me later asks again 5 hours of play later`() {
        val at = SupportAsk.remindAt(played = 12 * h)
        assertEquals(17 * h, at)
        assertFalse(SupportAsk.due(played = 16 * h, askAt = at, done = false))
        assertTrue(SupportAsk.due(played = 17 * h, askAt = at, done = false))
    }

    @Test fun `never again once answered`() {
        assertFalse(SupportAsk.due(played = 100 * h, askAt = SupportAsk.FIRST_ASK, done = true))
    }

    @Test fun `the sites are the website's own pages`() {
        assertEquals(
            listOf("https://buymeacoffee.com/lidor30g", "https://www.patreon.com/cw/pokedaisy", "https://ko-fi.com/pokedaisy"),
            SupportAsk.Site.entries.map { it.url },
        )
    }
}
