package org.jellyfin.mobile.sessionbrowser.page

import android.content.Context
import org.jellyfin.mobile.R
import org.jellyfin.mobile.sessionbrowser.LibraryItemAction
import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.MusicSearch
import org.jellyfin.mobile.sessionbrowser.libraryPage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

private const val MAX_SONGS = 25
private const val MAX_OTHERS = 15

/**
 * What a search result does when picked. A song plays within its album, so the album carries on
 * after it; albums, artists and playlists open.
 */
fun searchAction(item: BaseItemDto): LibraryItemAction = when (item.type) {
    BaseItemKind.MUSIC_ARTIST -> LibraryItemAction.Navigate(LibraryRoute.Artist(item.id))
    BaseItemKind.MUSIC_ALBUM -> LibraryItemAction.Navigate(LibraryRoute.Album(item.id))
    BaseItemKind.PLAYLIST -> LibraryItemAction.Navigate(LibraryRoute.Playlist(item.id))
    BaseItemKind.AUDIO -> LibraryItemAction.Play(item, queue = item.albumId?.let { LibraryRoute.Album(it) })
    else -> LibraryItemAction.Play(item)
}

val SearchLibraryPage = { context: Context, api: ApiClient ->
    libraryPage<LibraryRoute.Search>(grid = true) { route, _, _ ->
        if (route.query.isNullOrBlank()) return@libraryPage emptyList()

        val results = MusicSearch.search(api, route.query)
        val top = results.top
        fun List<BaseItemDto>.section(title: Int, max: Int) = LibraryPageElement.Group(
            title = context.getString(title),
            items = filter { it.id != top?.id }.take(max).map {
                LibraryPageElement.baseItem(api, it, action = searchAction(it))
            },
        )

        listOfNotNull(
            top?.let {
                LibraryPageElement.Group(
                    title = context.getString(R.string.media_service_car_section_top_result),
                    items = listOf(LibraryPageElement.baseItem(api, it, action = searchAction(it))),
                )
            },
            results.songs.section(R.string.media_service_car_section_songs, MAX_SONGS),
            results.artists.section(R.string.media_service_car_section_artists, MAX_OTHERS),
            results.albums.section(R.string.media_service_car_section_albums, MAX_OTHERS),
            results.playlists.section(R.string.media_service_car_section_playlists, MAX_OTHERS),
            results.audioBooks.section(R.string.media_service_car_section_audiobooks, MAX_OTHERS),
        )
    }
}
