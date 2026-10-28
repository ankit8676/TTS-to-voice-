package com.example.srtvoice

data class Cue(val index: Int, val startMs: Long, val endMs: Long, val text: String)

object SrtParser {
    private val timeRegex = Regex("(\\d{2}):(\\d{2}):(\\d{2})[,\.](\\d{3})")

    fun parse(input: String): List<Cue> {
        val normalized = input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.trim().split(Regex("\\n\\s*\\n"))
        val cues = mutableListOf<Cue>()
        for ((blockNo, block) in blocks.withIndex()) {
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.size < 2) continue
            val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIndex < 0) continue
            val parts = lines[timeLineIndex].split("-->")
            if (parts.size != 2) continue
            val start = parseTime(parts[0].trim())
            val end = parseTime(parts[1].trim().split(Regex("\\s+"))[0])
            if (end <= start) continue
            val body = lines.drop(timeLineIndex + 1).joinToString(" ").replace(Regex("<[^>]*>"), "").trim()
            if (body.isNotBlank()) cues += Cue(lines.firstOrNull()?.toIntOrNull() ?: blockNo + 1, start, end, body)
        }
        require(cues.isNotEmpty()) { "No valid subtitle cues found. Check that the file is a valid SRT." }
        return cues.sortedBy { it.startMs }
    }

    private fun parseTime(value: String): Long {
        val m = timeRegex.find(value) ?: error("Invalid SRT timestamp: $value")
        val (hh, mm, ss, ms) = (1..4).map { m.groupValues[it].toLong() }
        return (((hh * 60 + mm) * 60 + ss) * 1000) + ms
    }
}
