package io.github.axon07.channelbox.data

/**
 * Links playlist channels to XMLTV guide channels, in order of confidence:
 *  1. tvg-id equals the XMLTV channel id (ignoring case)
 *  2. tvg-id equals the XMLTV id after normalising ("CBC.ca" ~ "cbc ca")
 *  3. tvg-name / channel name equals one of the XMLTV display-names after normalising
 *     ("CA: CBC Toronto HD" ~ "CBC Toronto")
 *
 * XMLTV files list their <channel>s before the <programme>s, so channels are collected
 * first and matched when the first programme arrives; any later channels are matched as they come.
 */
class EpgMatcher(channels: List<Candidate>) {

    data class Candidate(val channelId: String, val epgId: String?, val names: List<String>)

    private data class XmlChannel(val id: String, val names: List<String>)

    private val unmatched = LinkedHashMap<String, Candidate>().apply { channels.forEach { put(it.channelId, it) } }
    private val pending = ArrayList<XmlChannel>()
    private var finalized = false

    /** playlist channel id → XMLTV channel id (lower-case). */
    val assigned = HashMap<String, String>()

    /** XMLTV ids (lower-case) we want programmes for. */
    val wanted = HashSet<String>()

    fun onChannel(id: String, displayNames: List<String>) {
        val x = XmlChannel(id.trim(), displayNames)
        if (finalized) match(listOf(x)) else pending += x
    }

    /** Returns true if programmes for [xmlChannelId] should be kept. */
    fun wants(xmlChannelId: String): Boolean {
        if (!finalized) finish()
        return xmlChannelId.trim().lowercase() in wanted
    }

    fun finish() {
        if (finalized) return
        finalized = true
        match(pending)
        pending.clear()
    }

    private fun match(xml: List<XmlChannel>) {
        // Pass 1: exact id.
        val byExact = HashMap<String, MutableList<Candidate>>()
        unmatched.values.forEach { c -> c.epgId?.let { byExact.getOrPut(it.trim().lowercase()) { mutableListOf() } += c } }
        xml.forEach { x -> byExact[x.id.lowercase()]?.forEach { assign(it, x.id) } }

        // Pass 2: normalised id.
        val byNormId = HashMap<String, MutableList<Candidate>>()
        unmatched.values.forEach { c ->
            c.epgId?.let { normalizeName(it) }?.takeIf { it.isNotEmpty() }?.let { byNormId.getOrPut(it) { mutableListOf() } += c }
        }
        xml.forEach { x -> byNormId[normalizeName(x.id)]?.toList()?.forEach { assign(it, x.id) } }

        // Pass 3: names.
        val byName = HashMap<String, MutableList<Candidate>>()
        unmatched.values.forEach { c ->
            c.names.map { normalizeName(it) }.filter { it.isNotEmpty() }.distinct()
                .forEach { byName.getOrPut(it) { mutableListOf() } += c }
        }
        xml.forEach { x ->
            x.names.forEach { n -> byName[normalizeName(n)]?.toList()?.forEach { assign(it, x.id) } }
        }
    }

    private fun assign(c: Candidate, xmlId: String) {
        if (unmatched.remove(c.channelId) == null) return
        val key = xmlId.lowercase()
        assigned[c.channelId] = key
        wanted += key
    }

    companion object {
        private val countryPrefix = Regex("""^\s*[A-Za-z]{2,4}\s*[:|]\s*""")
        private val bracketed = Regex("""[\[(][^\])]*[\])]""")
        private val noise = Regex("""\b(hd|fhd|uhd|sd|4k|hevc|h265|h264|1080p|720p|50fps|60fps|backup|raw)\b""")

        /** "CA: CBC Toronto HD (1080p)" → "cbctoronto" */
        fun normalizeName(name: String): String =
            name.replace(countryPrefix, "")
                .replace(bracketed, " ")
                .lowercase()
                .replace(noise, " ")
                .filter { it.isLetterOrDigit() }
    }
}
