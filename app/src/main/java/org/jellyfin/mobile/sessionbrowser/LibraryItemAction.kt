package org.jellyfin.mobile.sessionbrowser

import org.jellyfin.sdk.model.api.BaseItemDto

sealed interface LibraryItemAction {
    /**
     * Play [item]. [queue] is the list it plays within (a song found by search plays within its
     * album); without one, it's the page it was picked from.
     */
    data class Play(
        val item: BaseItemDto,
        val queue: LibraryRoute? = null,
    ) : LibraryItemAction

    data class Navigate(val route: LibraryRoute) : LibraryItemAction
}
