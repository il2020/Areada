package app.areada.ui

import app.areada.ui.reader.htmlReadAloudSegments
import app.areada.ui.reader.ReadAloudProbe
import app.areada.ui.reader.indexForProbe
import app.areada.ui.reader.matchesSource
import app.areada.ui.reader.plainTextReadAloudSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudSegmentsTest {
    @Test
    fun plainTextSplitsOnBlankLinesAndJoinsWrappedLines() {
        val text = "First line\nwrapped here.\n\n  Second paragraph."

        val segments = plainTextReadAloudSegments(text)

        assertEquals(listOf("First line wrapped here.", "Second paragraph."), segments.map { it.text })
        assertEquals(0, segments[0].start)
        assertEquals(text.indexOf("Second"), segments[1].start)
        assertEquals(text.indexOf("\n\n"), segments[0].end)
        assertEquals(text.length, segments[1].end)
    }

    @Test
    fun htmlSplitsOnBlocksAndSkipsScripts() {
        val html = """
            <html><head><style>p { color: red; }</style></head><body>
            <h1>Title</h1>
            <p>One <em>two</em><br>three</p>
            <script>window.scrollTo(0, 0)</script>
            <div><p>Nested</p></div>
            </body></html>
        """.trimIndent()

        val segments = htmlReadAloudSegments(html)

        assertEquals(listOf("Title", "One two", "three", "Nested"), segments.map { it.text })
    }

    @Test
    fun longParagraphIsChunkedAtSentenceBoundaries() {
        val sentence = "This sentence is exactly fifty characters long ok. "
        val text = sentence.repeat(30)

        val segments = plainTextReadAloudSegments(text)

        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.text.length <= 200 && it.text.endsWith(".") })
        assertEquals(text.trim(), segments.joinToString(" ") { it.text })
    }

    @Test
    fun probeTextSelectsSegmentClosestToItsPosition() {
        val segments = plainTextReadAloudSegments("Repeat me.\n\nSomething else.\n\nRepeat me.")

        assertEquals(1, segments.indexForProbe(ReadAloudProbe("thing else. Repeat", 0.4f, false)))
        assertEquals(0, segments.indexForProbe(ReadAloudProbe("Repeat me.", 0f, false)))
        assertEquals(2, segments.indexForProbe(ReadAloudProbe("Repeat me.", 0.9f, false)))
        assertEquals(0, segments.indexForProbe(ReadAloudProbe("", 0f, false)))
    }

    @Test
    fun probeTextMatchesPageThatJoinsBlocksWithoutSpaces() {
        val segments = htmlReadAloudSegments("<p>First block ends here.</p><p>Second block follows.</p>")

        assertEquals(0, segments.indexForProbe(ReadAloudProbe("ends here.Second block", 0f, false)))
        assertEquals(1, segments.indexForProbe(ReadAloudProbe("Second block follows.", 0f, false)))
    }

    @Test
    fun segmentMatchesOnlyUnchangedSource() {
        val text = "First line\nwrapped here.\n\nSecond paragraph."
        val segment = plainTextReadAloudSegments(text).first()

        assertTrue(segment.matchesSource(text))
        assertFalse(segment.matchesSource(text.replace("wrapped", "changed")))
        assertFalse(segment.matchesSource("First"))
    }
}
