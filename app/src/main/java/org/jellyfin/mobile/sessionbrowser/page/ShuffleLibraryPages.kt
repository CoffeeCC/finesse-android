package org.jellyfin.mobile.sessionbrowser.page

import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.libraryPage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import java.util.UUID

private suspend fun shuffledSongs(api: ApiClient, artistId: UUID?, offset: Int, limit: Int): List<LibraryPageElement> {
    val result by api.itemsApi.getItems(
        includeItemTypes = listOf(BaseItemKind.AUDIO),
        artistIds = listOfNotNull(artistId),
        sortBy = listOf(ItemSortBy.RANDOM),
        recursive = true,
        imageTypeLimit = 1,
        enableImageTypes = listOf(ImageType.PRIMARY),
        startIndex = offset,
        limit = limit,
    )
    return result.items.map { LibraryPageElement.baseItem(api, it) }
}

val ArtistShuffleLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.ArtistShuffle> { route, offset, limit ->
        shuffledSongs(api, route.artistId, offset, limit)
    }
}

val ShuffleAllLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.ShuffleAll> { _, offset, limit ->
        shuffledSongs(api, null, offset, limit)
    }
}
