package com.paperlight.reader.data.pdf

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Pure layout rules kept separate so paragraph, continuation and fallback decisions can be fixture-tested. */
internal object PdfReflowRules {
    data class Paragraph(val text: String, val startX: Float, val firstLine: String, val lastLine: String)

    fun looksMultiColumn(lines: List<PdfTextLine>, pageWidth: Float): Boolean {
        if (lines.any { it.maxHorizontalGap >= max(30f, pageWidth * 0.05f) }) return true
        val body = lines.filter { it.text.length >= 12 }
        if (body.size < 8) return false
        val sorted = body.sortedBy { it.x }
        var bestGap = 0f
        var split = -1
        for (index in 2 until sorted.size - 2) {
            val gap = sorted[index].x - sorted[index - 1].x
            if (gap > bestGap) {
                bestGap = gap
                split = index
            }
        }
        if (split < 0 || bestGap < max(54f, pageWidth * 0.12f)) return false
        val left = sorted.take(split)
        val right = sorted.drop(split)
        val gutter = right.minOf { it.x } - left.maxOf { it.x + it.width }
        return left.size >= 3 && right.size >= 3 && gutter >= max(18f, pageWidth * 0.025f)
    }

    fun paragraphs(lines: List<PdfTextLine>): List<Paragraph> {
        if (lines.isEmpty()) return emptyList()
        val typical = lines.map { it.fontSize }.filter { it > 0f }.sorted().let { it.getOrNull(it.size / 2) ?: 10f }
        val leftMargin = lines.map { it.x }.sorted().let { it.getOrNull(it.size / 4) ?: 0f }
        val typicalRight = lines.map { it.x + it.width }.sorted().let { it.getOrNull(it.size / 2) ?: 0f }
        val indentThreshold = max(typical * 1.2f, 10f)
        val output = mutableListOf<Paragraph>()
        val text = StringBuilder()
        var startX = lines.first().x
        var firstLine = ""
        var previousLine = ""
        var lastLine = ""
        var previous: PdfTextLine? = null
        fun flush() {
            if (text.isNotEmpty()) output += Paragraph(text.toString().trim(), startX, firstLine, lastLine)
            text.setLength(0)
        }
        lines.forEach { line ->
            val prior = previous
            val verticalGap = prior != null && line.y - prior.y > max(typical * 1.8f, 18f)
            val firstLineIndent = prior != null && line.x > leftMargin + indentThreshold && prior.x <= leftMargin + indentThreshold
            val priorLooksLikeFinishedLine = prior != null && previousLine.trimEnd().lastOrNull()?.let { it in ".!?" } == true &&
                prior.x + prior.width < typicalRight - max(typical * 1.5f, 24f) && line.x <= leftMargin + indentThreshold
            if (verticalGap || firstLineIndent || priorLooksLikeFinishedLine) {
                flush()
                startX = line.x
            }
            if (text.isEmpty()) {
                startX = line.x
                firstLine = line.text
                text.append(line.text)
            } else {
                text.append(joinSuffix(previousLine, line.text)).append(line.text)
            }
            previousLine = line.text
            lastLine = line.text
            previous = line
        }
        flush()
        return output
    }

    fun canContinue(previousLastLine: String, nextFirstLine: String, previousX: Float, nextX: Float): Boolean {
        if (nextFirstLine.isBlank()) return false
        if (nextFirstLine.matches(Regex("(?i)^(chapter|part|section|appendix|prologue|epilogue)\\b.*"))) return false
        val last = previousLastLine.trimEnd().lastOrNull() ?: return false
        if (previousX.isFinite() && nextX.isFinite() && (abs(previousX - nextX) > 28f || (nextX - previousX > 12f && last != '-'))) return false
        if (last in ".!?\"”’)]") return false
        if (last in ",;:" || last == '-') return true
        return nextFirstLine.firstOrNull() != null
    }

    /** The suffix to insert between two source lines, preserving a visible source hyphen. */
    fun joinSuffix(previousLine: String, nextLine: String): String =
        if ((previousLine.endsWith("-") && nextLine.firstOrNull()?.isLowerCase() == true) || nextLine.firstOrNull()?.let { it in ".,;:!?)]}" } == true) "" else " "

    fun repeatedEdgeKeys(pages: Sequence<PdfPageRecord>): Set<String> {
        data class Stat(var count: Int = 0, var minY: Float = 1f, var maxY: Float = 0f)
        val stats = mutableMapOf<String, Stat>()
        var pageCount = 0
        pages.forEach { page ->
            pageCount++
            val seen = mutableSetOf<String>()
            page.lines.forEachIndexed { index, line ->
                val relativeY = line.y / page.height
                val edge = when {
                    relativeY <= 0.08f && index <= 2 -> "top"
                    relativeY >= 0.92f && index >= page.lines.size - 3 -> "bottom"
                    else -> return@forEachIndexed
                }
                if (line.text.length > 140) return@forEachIndexed
                val key = "$edge:${normalizeEdge(line.text)}"
                if (normalizeEdge(line.text).isBlank() || !seen.add(key)) return@forEachIndexed
                val stat = stats.getOrPut(key) { Stat() }
                stat.count++
                stat.minY = minOf(stat.minY, relativeY)
                stat.maxY = maxOf(stat.maxY, relativeY)
            }
        }
        if (pageCount < 3) return emptySet()
        val threshold = ceil(pageCount * 0.35).toInt().coerceAtLeast(3)
        return stats.filterValues { it.count >= threshold && it.maxY - it.minY <= 0.08f }.keys
    }

    fun isRepeatedEdge(line: PdfTextLine, page: PdfPageRecord, repeated: Set<String>): Boolean {
        val index = page.lines.indexOf(line)
        val edge = when {
            line.y / page.height <= 0.08f && index in 0..2 -> "top"
            line.y / page.height >= 0.92f && index >= page.lines.size - 3 -> "bottom"
            else -> return false
        }
        return "$edge:${normalizeEdge(line.text)}" in repeated
    }

    private fun normalizeEdge(value: String): String =
        value.lowercase().replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").trim()
}
