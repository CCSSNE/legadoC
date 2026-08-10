package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadBookConfigTest {

    @Test
    fun sanitize_clampsUnsafeLineSpacing() {
        val config = ReadBookConfig.Config(lineSpacingExtra = -5)

        val changed = config.sanitize()

        assertTrue(changed)
        assertEquals(10, config.lineSpacingExtra)
    }

    @Test
    fun sanitize_clampsOutOfRangeLayoutValues() {
        val config = ReadBookConfig.Config(
            textSize = 1,
            letterSpacing = 2f,
            lineSpacingExtra = 100,
            paragraphSpacing = -3,
            titleSize = 99,
            paddingTop = 300,
            paddingLeft = -10,
            headerPaddingTop = -1,
            footerPaddingRight = 120
        )

        val changed = config.sanitize()

        assertTrue(changed)
        assertEquals(5, config.textSize)
        assertEquals(0.5f, config.letterSpacing, 0.0f)
        assertEquals(20, config.lineSpacingExtra)
        assertEquals(0, config.paragraphSpacing)
        assertEquals(20, config.titleSize)
        assertEquals(200, config.paddingTop)
        assertEquals(0, config.paddingLeft)
        assertEquals(0, config.headerPaddingTop)
        assertEquals(100, config.footerPaddingRight)
    }

    @Test
    fun sanitize_keepsValidConfig() {
        val config = ReadBookConfig.Config()

        val changed = config.sanitize()

        assertFalse(changed)
    }

}
