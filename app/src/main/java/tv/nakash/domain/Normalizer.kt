package tv.nakash.domain

import tv.nakash.data.remote.LiveStreamDto

/**
 * Pure normalization rules, derived from the real server data (18.9.2026). Unit-tested against fixtures.
 * Everything here runs once in the repository, before Room. The UI never sees raw names.
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
object Normalizer {

    private val DIRECTIONAL = Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")
    private val MULTI_SPACE = Regex("\\s+")
    private val STRAY_EDGE = Regex("^[\\s'\"!.\\-–]+|[\\s'\"\\-–]+$")
    private val BACKUP_SUFFIX = Regex("""\s*[-–]?\s*\(?\s*גיבוי\s*\d*\s*\)?\s*[-–]?\s*""")
    private val ACCESSIBLE_SUFFIX = Regex("""\s*[-–]\s*מונגש\s*$""")
    private val RUS_SUFFIX = Regex("""\s+RUS$""", RegexOption.IGNORE_CASE)
    private val SXXEXX = Regex("""^(.*?)\s*[-–]\s*S(\d{1,2})E(\d{1,4})\s*[-–]\s*(.*)$""")
    private val YEAR_SUFFIX = Regex("""\s*\((\d{4})\)\s*$""")

    /** Display-name overrides for names the server stores with broken bidi ordering. */
    private val OVERRIDES = mapOf(
        "4K ספורט 5" to "ספורט 5 4K",
        "STARS ספורט 5" to "ספורט 5 STARS",
        "PLUS ספורט 5" to "ספורט 5 PLUS",
        "GOLD ספורט 5" to "ספורט 5 GOLD",
        "LIVE ספורט 5" to "ספורט 5 LIVE",
        "il ערוץ המוזיקה" to "ערוץ המוזיקה",
        "!הופ" to "הופ!",
        "'ויוה וינטג" to "ויוה וינטג'",
        "FOMO ערוץ" to "ערוץ FOMO",
        "USA- WWE" to "WWE",
        "N. Geographic - נשיונל ג'יאוגרפיק" to "נשיונל ג'יאוגרפיק",
        "N. Geographic Wild - נשיונל ג'יאוגרפיק ווילד" to "נשיונל ג'יאוגרפיק ווילד",
        "Nickelodeon - ניקלודיאון (yes)" to "ניקלודיאון",
        "E! - ערוץ הבידור" to "E!",
    )

    fun clean(raw: String): String =
        raw.replace(DIRECTIONAL, "").replace(MULTI_SPACE, " ").trim().replace(STRAY_EDGE, "").trim()

    /** Name without backup/accessible/RUS markers — the key used to group variants. */
    fun baseName(raw: String): String {
        var s = clean(raw)
        s = s.replace(BACKUP_SUFFIX, " ").replace(ACCESSIBLE_SUFFIX, "").replace(RUS_SUFFIX, "")
        return clean(s)
    }

    private val normalizedOverrides = OVERRIDES.mapKeys { baseName(it.key) }

    fun displayName(raw: String): String {
        val base = baseName(raw)
        return normalizedOverrides[base] ?: base
    }

    enum class SourceKind { PRIMARY, BACKUP, ACCESSIBLE, RUSSIAN }

    fun sourceKind(raw: String): SourceKind = when {
        raw.contains("גיבוי") -> SourceKind.BACKUP
        raw.contains("מונגש") -> SourceKind.ACCESSIBLE
        RUS_SUFFIX.containsMatchIn(clean(raw)) -> SourceKind.RUSSIAN
        else -> SourceKind.PRIMARY
    }

    /** "israel  - ישראל" -> Hebrew part if present, else the English part. */
    fun categoryName(raw: String): String {
        val c = clean(raw)
        val parts = c.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        val hebrew = parts.firstOrNull { it.any { ch -> ch in '\u0590'..'\u05FF' } }
        return hebrew ?: parts.firstOrNull() ?: c
    }

    /** epg ids known to be placeholders on this server. */
    private val PLACEHOLDER_EPG = setOf("150100", "0", "")

    fun hasEpg(dto: LiveStreamDto): Boolean {
        val id = dto.epgChannelId?.trim() ?: return false
        if (id in PLACEHOLDER_EPG) return false
        return true
    }

    /** Filler detection: the panel fills channels without a guide with the channel name in 3h blocks. */
    fun isFiller(channelName: String, programTitle: String, sameTitleCountToday: Int, durationMin: Int): Boolean {
        if (clean(programTitle).equals(clean(channelName), ignoreCase = true)) return true
        if (durationMin >= 180 && sameTitleCountToday >= 4) return true
        return false
    }

    /** Pure Kotlin so it runs on minSdk 24 and in JVM unit tests. */
    fun decodeBase64(s: String): String = try {
        kotlin.io.encoding.Base64.Default.decode(s.trim()).toString(Charsets.UTF_8)
    } catch (_: Throwable) { s }

    /** "פרחי דם - S01E05 - פרק 5" -> (season 1, episode 5, "פרק 5"). Falls back to episode_num from the DTO. */
    data class EpisodeTitle(val season: Int?, val episode: Int?, val title: String)
    fun episodeTitle(raw: String): EpisodeTitle {
        val m = SXXEXX.find(clean(raw)) ?: return EpisodeTitle(null, null, clean(raw))
        val (_, s, e, tail) = m.destructured
        return EpisodeTitle(s.toInt(), e.toInt(), tail.ifBlank { "פרק ${e.toInt()}" })
    }

    /** "לוריין הקטנה (2026)" -> "לוריין הקטנה". Prefer the DTO's `title`; this is the fallback. */
    fun titleWithoutYear(name: String) = clean(name).replace(YEAR_SUFFIX, "")

    fun genres(raw: String?): List<String> =
        raw?.split(',', '،', '/')?.map { clean(it) }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

    fun plot(raw: String?): String? = raw?.replace('\u00a0', ' ')?.replace(Regex("[ \\t]+"), " ")
        ?.replace(Regex("\\n{3,}"), "\n\n")?.trim()?.takeIf { it.isNotEmpty() }

    /** Backdrop equal to the cover means "no real backdrop": the UI blurs the cover instead. */
    fun backdrop(backdrops: List<String>, cover: String?): String? =
        backdrops.firstOrNull()?.takeIf { it != cover }

    /** http -> https for the main image host; other http hosts are allowed via network_security_config. */
    fun imageUrl(raw: String?): String? {
        val u = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (u.startsWith("http://ilvip.net")) u.replaceFirst("http://", "https://") else u
    }

    fun initials(name: String): String {
        val words = clean(name).split(' ').filter { it.any(Char::isLetterOrDigit) }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2)
            else -> "${words[0].first()}${words[1].first()}"
        }
    }
}
