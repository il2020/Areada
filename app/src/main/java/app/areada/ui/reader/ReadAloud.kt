package app.areada.ui.reader

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.text.BreakIterator
import kotlin.math.abs

/** A piece of text spoken as one utterance; [start] and [end] are its bounds in the source text. */
internal data class ReadAloudSegment(
    val text: String,
    val start: Int,
    val end: Int,
    val fraction: Float,
)

private const val PreferredSegmentLength = 500
private const val MaxSegmentLength = 3_500
private const val ProbeLength = 40

private val HtmlParagraphSeparator = Regex("\\n")
private val TextParagraphSeparator = Regex("\\n\\s*\\n")
private val Whitespace = Regex("\\s+")
private val InvisibleChars = Regex("[\u00AD\u200B]")

internal fun htmlReadAloudSegments(html: String): List<ReadAloudSegment> {
    val body = Jsoup.parse(html).body()
    body.select("script, style, noscript, rt, rp").remove()
    val text = StringBuilder()
    NodeTraversor.traverse(
        object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> text.append(node.text())
                    node is Element && (node.isBlock || node.normalName() == "br") -> text.append('\n')
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.isBlock) {
                    text.append('\n')
                }
            }
        },
        body,
    )
    return readAloudSegments(text.toString(), HtmlParagraphSeparator)
}

internal fun plainTextReadAloudSegments(text: String): List<ReadAloudSegment> =
    readAloudSegments(text, TextParagraphSeparator)

/** Whether [source] still holds this segment's text at its offsets. */
internal fun ReadAloudSegment.matchesSource(source: String): Boolean =
    end <= source.length &&
        source.substring(start, end).replace(InvisibleChars, "").replace(Whitespace, " ") == text

private fun readAloudSegments(text: String, paragraphSeparator: Regex): List<ReadAloudSegment> {
    val segments = mutableListOf<ReadAloudSegment>()
    val length = text.length.coerceAtLeast(1).toFloat()

    fun addSegment(start: Int, end: Int) {
        val raw = text.substring(start, end)
        val clean = raw.replace(InvisibleChars, "").replace(Whitespace, " ").trim()
        if (clean.isEmpty()) return
        val offset = start + raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val endOffset = end - (raw.length - raw.trimEnd().length)
        segments += ReadAloudSegment(text = clean, start = offset, end = endOffset, fraction = offset / length)
    }

    fun addLongSentence(start: Int, end: Int) {
        var chunkStart = start
        while (end - chunkStart > MaxSegmentLength) {
            val limit = chunkStart + MaxSegmentLength
            val breakAt = text.lastIndexOf(' ', limit).takeIf { it > chunkStart } ?: limit
            addSegment(chunkStart, breakAt)
            chunkStart = breakAt
        }
        addSegment(chunkStart, end)
    }

    fun addParagraph(start: Int, end: Int) {
        if (end - start <= PreferredSegmentLength) {
            addSegment(start, end)
            return
        }
        val sentences = BreakIterator.getSentenceInstance()
        sentences.setText(text.substring(start, end))
        var chunkStart = start
        var sentenceStart = start
        var sentenceEnd = sentences.next()
        while (sentenceEnd != BreakIterator.DONE) {
            val absoluteEnd = start + sentenceEnd
            if (absoluteEnd - sentenceStart > MaxSegmentLength) {
                addSegment(chunkStart, sentenceStart)
                addLongSentence(sentenceStart, absoluteEnd)
                chunkStart = absoluteEnd
            } else if (absoluteEnd - chunkStart > PreferredSegmentLength && sentenceStart > chunkStart) {
                addSegment(chunkStart, sentenceStart)
                chunkStart = sentenceStart
            }
            sentenceStart = absoluteEnd
            sentenceEnd = sentences.next()
        }
        addSegment(chunkStart, end)
    }

    var paragraphStart = 0
    paragraphSeparator.findAll(text).forEach { match ->
        addParagraph(paragraphStart, match.range.first)
        paragraphStart = match.range.last + 1
    }
    addParagraph(paragraphStart, text.length)
    return segments
}

/** Text source for [ReadAloudViewModel]; chapters are loaded on demand, off the main thread. */
internal class ReadAloudBook(
    val documentUri: String,
    val title: String,
    val language: String?,
    val chapterCount: Int,
    val loadChapter: suspend (chapterIndex: Int) -> List<ReadAloudSegment>,
)

internal data class ReadAloudState(
    val documentUri: String,
    val chapterIndex: Int,
    /** The segment being spoken, or the one reading is paused on. */
    val segment: ReadAloudSegment?,
    val playing: Boolean,
)

/** What the page shows at the top of the screen, used to decide where reading starts. */
internal data class ReadAloudProbe(
    val text: String,
    val fraction: Float,
    val highlightVisible: Boolean,
)

/**
 * Index of the segment containing the probed text; the occurrence closest to its position wins.
 * Whitespace is ignored: the page doesn't separate adjacent blocks the way segments are.
 */
internal fun List<ReadAloudSegment>.indexForProbe(probe: ReadAloudProbe): Int {
    if (isEmpty()) return 0
    val starts = IntArray(size)
    val joined = buildString {
        this@indexForProbe.forEachIndexed { index, segment ->
            starts[index] = length
            segment.text.filterNotTo(this) { it.isWhitespace() }
        }
    }
    val hint = probe.fraction * joined.length
    val needle = probe.text.filterNot { it.isWhitespace() }.take(ProbeLength)
    val position = needle
        .takeIf { it.isNotEmpty() }
        ?.let { joined.closestIndexOf(it, hint) }
        ?.takeIf { it >= 0 }
        ?: hint.toInt()
    return starts.indexOfLast { it <= position }.coerceAtLeast(0)
}

private fun String.closestIndexOf(needle: String, hint: Float): Int {
    var best = -1
    var position = indexOf(needle)
    while (position >= 0) {
        if (best < 0 || abs(position - hint) < abs(best - hint)) best = position
        if (position > hint) break
        position = indexOf(needle, position + 1)
    }
    return best
}

/** A reader screen's view of [ReadAloudViewModel], limited to its own document. */
@Stable
internal class ReadAloudHandle(
    private val player: ReadAloudViewModel,
    private val documentUri: String,
) {
    private val state: ReadAloudState?
        get() = player.state?.takeIf { it.documentUri == documentUri }

    val playing: Boolean
        get() = state?.playing == true

    /** Chapter the player moved on to by itself and the screen should open. */
    val chapterToOpen: Int?
        get() = player.chapterToOpen.takeIf { state != null }

    /** Bumped when a WebView page should report where reading starts via [onProbe]. */
    var probeRequest by mutableIntStateOf(0)
        private set

    private var probeTarget: Pair<ReadAloudBook, Int>? = null

    fun chapterOpened() = player.chapterOpened()

    fun segmentIn(chapterIndex: Int): ReadAloudSegment? =
        state?.takeIf { it.chapterIndex == chapterIndex }?.segment

    fun play(
        book: ReadAloudBook,
        chapterIndex: Int,
        resumeAllowed: Boolean,
        startIndex: (List<ReadAloudSegment>) -> Int,
    ) = player.play(book, chapterIndex, resumeAllowed, startIndex)

    fun pause() = player.pause()

    /** Play/pause for WebView screens: playback starts where the page reports the reader is. */
    fun toggleForPage(book: ReadAloudBook, chapterIndex: Int, pageShown: Boolean) {
        when {
            playing -> player.pause()
            pageShown -> {
                probeTarget = book to chapterIndex
                probeRequest += 1
            }
            else -> player.play(book, chapterIndex, resumeAllowed = false) { 0 }
        }
    }

    fun onProbe(probe: ReadAloudProbe) {
        val (book, chapterIndex) = probeTarget ?: return
        probeTarget = null
        player.play(book, chapterIndex, resumeAllowed = probe.highlightVisible) { segments ->
            segments.indexForProbe(probe)
        }
    }

    /** Called when the user opens another chapter, so reading moves along with them. */
    fun followChapter(chapterIndex: Int) = player.followChapter(documentUri, chapterIndex)
}

/** The read-aloud player for [documentUri]; reading stops when the reader is closed. */
@Composable
internal fun rememberReadAloud(documentUri: String): ReadAloudHandle {
    val player: ReadAloudViewModel = viewModel()
    val activity = LocalActivity.current
    DisposableEffect(player, documentUri) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                player.stop(documentUri)
            }
        }
    }
    return remember(player, documentUri) { ReadAloudHandle(player, documentUri) }
}

/**
 * Opens the chapter the player has moved on to by itself (e.g. while the screen was off). Chapters
 * the user left the paused player in are not forced back, even when the activity is recreated.
 */
@Composable
internal fun ReadAloudChapterSync(
    readAloud: ReadAloudHandle,
    onChapterChange: (Int) -> Unit,
) {
    val target = readAloud.chapterToOpen
    LaunchedEffect(target) {
        if (target != null) {
            onChapterChange(target)
            readAloud.chapterOpened()
        }
    }
}
