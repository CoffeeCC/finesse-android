package org.jellyfin.mobile.sessionbrowser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchRankingTest {
    @Test
    fun `names are compared without case, accents, punctuation or a leading "the"`() {
        assertEquals("beyonce", SearchRanking.normalize("Beyoncé"))
        assertEquals("ac dc", SearchRanking.normalize("AC/DC"))
        assertEquals("beatles", SearchRanking.normalize("The Beatles"))
        assertEquals("simon and garfunkel", SearchRanking.normalize("Simon & Garfunkel"))
    }

    @Test
    fun `an exact name beats one that starts with it, which beats a word inside`() {
        assertEquals(SearchRanking.EXACT, SearchRanking.score("Queen", "queen"))
        assertEquals(SearchRanking.STARTS_WITH, SearchRanking.score("Queens of the Stone Age", "queen"))
        assertEquals(SearchRanking.WORD_STARTS_WITH, SearchRanking.score("Killer Queen", "queen"))
        assertEquals(SearchRanking.CONTAINS, SearchRanking.score("Dancing Queens", "ancing"))
        assertEquals(SearchRanking.NO_MATCH, SearchRanking.score("Bohemian Rhapsody", "queen"))
        assertEquals(SearchRanking.EXACT, SearchRanking.score("The Beatles", "beatles"))
    }

    @Test
    fun `results come best match first`() {
        val ranked = SearchRanking.rank(
            listOf("Killer Queen", "Queens of the Stone Age", "Queen"),
            "queen",
            name = { it },
        )
        assertEquals(listOf("Queen", "Queens of the Stone Age", "Killer Queen"), ranked)
    }

    @Test
    fun `equal matches go to favourites, then the most played`() {
        data class Song(val name: String, val favourite: Boolean = false, val plays: Int = 0)
        val ranked = SearchRanking.rank(
            listOf(Song("Home", plays = 3), Song("Home", plays = 40), Song("Home", favourite = true)),
            "home",
            name = { it.name },
            favourite = { it.favourite },
            plays = { it.plays },
        )
        assertEquals(listOf(Song("Home", favourite = true), Song("Home", plays = 40), Song("Home", plays = 3)), ranked)
    }
}
