package org.jellyfin.mobile.sessionbrowser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/**
 * Searching the music, shared by the car's search screen and by voice ("play … on Finesse").
 * Each kind is looked up on its own and put in [SearchRanking] order.
 */
object MusicSearch {
    private const val LIMIT = 50

    // When a song, an album and an artist match equally well, the artist is what was meant.
    private val kindOrder = listOf(
        BaseItemKind.MUSIC_ARTIST,
        BaseItemKind.MUSIC_ALBUM,
        BaseItemKind.AUDIO,
        BaseItemKind.PLAYLIST,
        BaseItemKind.AUDIO_BOOK,
    )

    data class Results(
        val query: String,
        val songs: List<BaseItemDto>,
        val artists: List<BaseItemDto>,
        val albums: List<BaseItemDto>,
        val playlists: List<BaseItemDto>,
        val audioBooks: List<BaseItemDto>,
    ) {
        /** The best match of any kind, if anything matched at all. */
        val top: BaseItemDto? = listOf(artists, albums, songs, playlists, audioBooks)
            .mapNotNull { it.firstOrNull() }
            .filter { SearchRanking.score(it.name.orEmpty(), query) < SearchRanking.NO_MATCH }
            .minWithOrNull(
                compareBy<BaseItemDto> { SearchRanking.score(it.name.orEmpty(), query) }
                    .thenBy { kindOrder.indexOf(it.type) },
            )

        val isEmpty get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty() &&
            playlists.isEmpty() && audioBooks.isEmpty()
    }

    suspend fun search(api: ApiClient, query: String): Results = coroutineScope {
        val songs = async { find(api, query, BaseItemKind.AUDIO) }
        val artists = async { find(api, query, BaseItemKind.MUSIC_ARTIST) }
        val albums = async { find(api, query, BaseItemKind.MUSIC_ALBUM) }
        val playlists = async { find(api, query, BaseItemKind.PLAYLIST) }
        val audioBooks = async { find(api, query, BaseItemKind.AUDIO_BOOK) }
        Results(query, songs.await(), artists.await(), albums.await(), playlists.await(), audioBooks.await())
    }

    /** Items of one [kind] matching [query], best first. */
    suspend fun find(api: ApiClient, query: String, kind: BaseItemKind): List<BaseItemDto> {
        if (query.isBlank()) return emptyList()
        val result = withContext(Dispatchers.IO) {
            api.itemsApi.getItems(
                searchTerm = query.trim(),
                includeItemTypes = listOf(kind),
                recursive = true,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY),
                limit = LIMIT,
            ).content
        }
        return SearchRanking.rank(
            items = result.items,
            query = query,
            name = { it.name.orEmpty() },
            favourite = { it.userData?.isFavorite == true },
            plays = { it.userData?.playCount ?: 0 },
        )
    }

    /**
     * The best match of one [kind] for [name]. With an [artist] ("… by …"), matches by that artist
     * come first; if none are, the artist is ignored rather than finding nothing.
     */
    suspend fun best(api: ApiClient, name: String, kind: BaseItemKind, artist: String? = null): BaseItemDto? {
        val found = find(api, name, kind)
            .filter { SearchRanking.score(it.name.orEmpty(), name) < SearchRanking.NO_MATCH }
        if (artist.isNullOrBlank()) return found.firstOrNull()
        return found.firstOrNull { it.isBy(artist) } ?: found.firstOrNull()
    }

    private fun BaseItemDto.isBy(artist: String): Boolean =
        (artists.orEmpty() + listOfNotNull(albumArtist)).any {
            SearchRanking.score(it, artist) <= SearchRanking.WORD_STARTS_WITH
        }
}
