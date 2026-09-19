package tv.nakash.domain
import java.text.Normalizer as UnicodeNormalizer
import java.util.Locale
object SearchMatcher {
    private val marks = Regex("\\p{M}+")
    private val punctuation = Regex("['\"׳״‘’“”\\u200e\\u200f\\u2066-\\u2069]")
    fun key(value: String): String = UnicodeNormalizer.normalize(value,UnicodeNormalizer.Form.NFD)
        .replace(marks, "").replace(punctuation, "").lowercase(Locale.ROOT).trim().replace(Regex("\\s+")," ")
    fun matches(query: String,vararg values: String?): Boolean {
        val q=key(query)
        return q.isEmpty() || values.any { it!=null && key(it).contains(q) }
    }
}
