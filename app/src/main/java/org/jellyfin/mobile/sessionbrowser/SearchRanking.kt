package org.jellyfin.mobile.sessionbrowser

import java.text.Normalizer
import java.util.Locale

/**
 * How well a name matches what was searched for, best first. Jellyfin finds the matches; this puts
 * them in the order a person expects: "Queen" before "Queens of the Stone Age" before "Killer Queen".
 */
object SearchRanking {
    const val EXACT = 0
    const val STARTS_WITH = 1
    const val WORD_STARTS_WITH = 2
    const val CONTAINS = 3
    const val NO_MATCH = 4

    private val marks = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** Lower case, no accents or punctuation, "&" as "and", no leading "the". */
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(marks, "")
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(separators, " ")
        .trim()
        .removePrefix("the ")

    fun score(name: String, query: String): Int {
        val n = normalize(name)
        val q = normalize(query)
        return when {
            q.isEmpty() || n.isEmpty() -> NO_MATCH
            n == q -> EXACT
            n.startsWith(q) -> STARTS_WITH
            " $n".contains(" $q") -> WORD_STARTS_WITH
            n.contains(q) -> CONTAINS
            else -> NO_MATCH
        }
    }

    /**
     * [items] best match first. Ties go to favourites, then what's played most, then the shorter
     * name; otherwise Jellyfin's order is kept.
     */
    fun <T> rank(
        items: List<T>,
        query: String,
        name: (T) -> String,
        favourite: (T) -> Boolean = { false },
        plays: (T) -> Int = { 0 },
    ): List<T> = items.sortedWith(
        compareBy<T> { score(name(it), query) }
            .thenBy { if (favourite(it)) 0 else 1 }
            .thenByDescending { plays(it) }
            .thenBy { name(it).length },
    )
}
