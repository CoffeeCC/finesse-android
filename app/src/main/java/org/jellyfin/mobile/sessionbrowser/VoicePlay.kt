package org.jellyfin.mobile.sessionbrowser

import android.os.Bundle
import android.provider.MediaStore
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * "Hey Google, play … on Finesse": what to play for what was said. The Assistant often says what
 * kind of thing was asked for (an artist, an album, a song) and splits out the artist; when it
 * doesn't, the best search match decides.
 */
object VoicePlay {
    data class Request(
        val query: String,
        val focus: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val title: String? = null,
        val playlist: String? = null,
    ) {
        companion object {
            fun from(query: String?, extras: Bundle?) = Request(
                query = query.orEmpty().trim(),
                focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS),
                artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)?.takeIf { it.isNotBlank() },
                album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM)?.takeIf { it.isNotBlank() },
                title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)?.takeIf { it.isNotBlank() },
                playlist = extras?.getString(EXTRA_PLAYLIST)?.takeIf { it.isNotBlank() },
            )
        }
    }

    sealed interface Queue {
        /** Play [route]'s songs, from [startItemId] if given. */
        data class FromRoute(val route: LibraryRoute, val startItemId: UUID? = null) : Queue

        /** Play just this (an audiobook). */
        data class Single(val item: BaseItemDto) : Queue
    }

    private const val FOCUS_ARTIST = MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
    private const val FOCUS_ALBUM = MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
    private const val FOCUS_SONG = MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
    // Android deprecated its playlist constants, but the Assistant still sends these.
    private const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"
    private const val EXTRA_PLAYLIST = "android.intent.extra.playlist"

    // "bohemian rhapsody by queen"
    private val byArtist = Regex("^(.+?)\\s+by\\s+(.+)$", RegexOption.IGNORE_CASE)

    /** What to play, or null when nothing matched (the caller then shuffles everything). */
    suspend fun resolve(api: ApiClient, request: Request): Queue? {
        if (request.query.isEmpty() && request.focus == null) return Queue.FromRoute(LibraryRoute.ShuffleAll)

        return when (request.focus) {
            FOCUS_ARTIST -> MusicSearch.best(api, request.artist ?: request.query, BaseItemKind.MUSIC_ARTIST)
            FOCUS_ALBUM ->
                MusicSearch.best(api, request.album ?: request.query, BaseItemKind.MUSIC_ALBUM, request.artist)
            FOCUS_SONG ->
                MusicSearch.best(api, request.title ?: request.query, BaseItemKind.AUDIO, request.artist)
            FOCUS_PLAYLIST -> MusicSearch.best(api, request.playlist ?: request.query, BaseItemKind.PLAYLIST)
            else -> null
        }?.let(::queueFor) ?: anything(api, request.query)
    }

    private suspend fun anything(api: ApiClient, query: String): Queue? {
        if (query.isEmpty()) return null
        MusicSearch.search(api, query).top?.let { return queueFor(it) }

        // Nothing matched the whole phrase: try it as "<song or album> by <artist>".
        val (name, artist) = byArtist.find(query)?.destructured ?: return null
        val match = MusicSearch.best(api, name, BaseItemKind.AUDIO, artist)
            ?: MusicSearch.best(api, name, BaseItemKind.MUSIC_ALBUM, artist)
        return match?.let(::queueFor)
    }

    /** An artist shuffles, an album plays from the start, a song plays within its album. */
    fun queueFor(item: BaseItemDto): Queue = when (item.type) {
        BaseItemKind.MUSIC_ARTIST -> Queue.FromRoute(LibraryRoute.ArtistShuffle(item.id))
        BaseItemKind.MUSIC_ALBUM -> Queue.FromRoute(LibraryRoute.Album(item.id))
        BaseItemKind.PLAYLIST -> Queue.FromRoute(LibraryRoute.Playlist(item.id))
        BaseItemKind.AUDIO -> when (val albumId = item.albumId) {
            null -> Queue.Single(item)
            else -> Queue.FromRoute(LibraryRoute.Album(albumId), startItemId = item.id)
        }
        else -> Queue.Single(item)
    }
}
