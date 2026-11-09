package org.jellyfin.mobile.sessionbrowser.page

import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.libraryPage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.model.api.ImageType

// In the playlist's own order, not alphabetical.
val PlaylistLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.Playlist>(grid = true) { route, offset, limit ->
        val result by api.playlistsApi.getPlaylistItems(
            playlistId = route.playlistId,
            imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY),
            startIndex = offset,
            limit = limit,
        )
        result.items.map { LibraryPageElement.baseItem(api, it) }
    }
}
