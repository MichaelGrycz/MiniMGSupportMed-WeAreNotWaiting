package pl.mgsupport.mglycemia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `reads the tag from a GitHub release redirect`() {
        assertEquals(
            "v1.0-beta19",
            parseTagFromReleaseUrl("https://github.com/MichaelGrycz/MiniMGSupportMed-WeAreNotWaiting/releases/tag/v1.0-beta19"),
        )
    }

    @Test
    fun `ignores query strings and trailing slashes`() {
        assertEquals(
            "v1.0-beta19",
            parseTagFromReleaseUrl("https://github.com/o/r/releases/tag/v1.0-beta19?foo=bar"),
        )
        assertEquals(
            "v1.0-beta19",
            parseTagFromReleaseUrl("https://github.com/o/r/releases/tag/v1.0-beta19/"),
        )
    }

    @Test
    fun `returns null for a URL that is not a release tag`() {
        assertNull(parseTagFromReleaseUrl("https://github.com/o/r/releases/latest"))
        assertNull(parseTagFromReleaseUrl("https://github.com/o/r/releases/tag/"))
    }

    @Test
    fun `reads the newest release from the Atom feed`() {
        val xml = """
            <feed>
              <entry>
                <title>v1.0-beta23</title>
                <link href="https://github.com/o/r/releases/tag/v1.0-beta23"/>
              </entry>
              <entry><title>v1.0-beta22</title></entry>
            </feed>
        """.trimIndent()
        assertEquals("v1.0-beta23", parseAtomTitle(xml))
        assertNull(parseAtomTitle("<feed></feed>"))
    }

    @Test
    fun `newer beta numbers compare higher`() {
        assertTrue(compareVersions("1.0-beta19", "1.0-beta18") > 0)
        assertTrue(compareVersions("1.0-beta18", "1.0-beta19") < 0)
        assertEquals(0, compareVersions("1.0-beta19", "1.0-beta19"))
    }
}
