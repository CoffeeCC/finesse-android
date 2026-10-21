package org.jellyfin.mobile.sessionbrowser

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.R
import org.jellyfin.mobile.sessionbrowser.page.AlbumLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.AlbumsAlphaLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.AlbumsLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.ArtistLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.ArtistsAlphaLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.ArtistsLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.AudioBooksAlphaLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.AudioBooksLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.FavoritesLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.GenreLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.GenresLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.PlaylistLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.PlaylistsLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.RecentLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.RootLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.SearchLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.SuggestedLibraryPage
import org.jellyfin.mobile.sessionbrowser.page.UserViewLibraryPage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber

@Suppress("InjectDispatcher")
class SessionBrowserCallback(
    private val context: Context,
    private val api: ApiClient,
    private val resumption: PlaybackResumptionStore,
) : MediaLibrarySession.Callback {
    companion object {
        const val MAX_PAGE_SIZE = 250
        const val MEDIA_ITEM_EXTRA_START_POSITION = "start_position"

        // Android Auto, and the desktop head unit used to test it.
        private val CAR_HOST_PACKAGES = listOf(
            "com.google.android.projection.gearhead",
            "com.google.android.apps.automotive.templates.host",
        )
    }

    val pages = listOf(
        RootLibraryPage(api),
        UserViewLibraryPage(context),
        AlbumsLibraryPage(api),
        AlbumsAlphaLibraryPage,
        AlbumLibraryPage(api),
        AudioBooksLibraryPage(api),
        AudioBooksAlphaLibraryPage,
        ArtistsLibraryPage(api),
        ArtistsAlphaLibraryPage,
        ArtistLibraryPage(api),
        FavoritesLibraryPage(api),
        GenresLibraryPage(api),
        GenreLibraryPage(api),
        PlaylistsLibraryPage(api),
        PlaylistLibraryPage(api),
        RecentLibraryPage(api),
        SuggestedLibraryPage(api),
        SearchLibraryPage(context, api),
    )

    private val LibraryRoute.page get() = pages.firstOrNull { page -> page.route == this::class }

    private fun LibraryPageElement.Item.toMediaItem(
        route: LibraryRoute,
        groupTitle: String? = null,
    ): MediaItem = MediaItem.Builder().apply {
        val extras = bundleOf()
        groupTitle?.let {
            extras.putString(androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it)
        }

        if (action is LibraryItemAction.Navigate) {
            val page = action.route.page

            val contentStyle = when (page?.grid == true) {
                true -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                false -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            }
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, contentStyle)
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, contentStyle)
            setMediaId(Json.encodeToString<LibraryMediaId>(LibraryMediaId.Route(action.route)))
        } else if (action is LibraryItemAction.Play) {
            setMediaId(Json.encodeToString<LibraryMediaId>(LibraryMediaId.Item(action.item.id, route)))
            action.item.userData?.playbackPositionTicks?.ticks?.inWholeMilliseconds?.let {
                extras.putLong(MEDIA_ITEM_EXTRA_START_POSITION, it)
            }
        }

        setMediaMetadata(
            MediaMetadata.Builder().apply {
                setTitle(title)
                setArtist(artist)
                setAlbumTitle(album)
                setIsBrowsable(action is LibraryItemAction.Navigate)
                setIsPlayable(action is LibraryItemAction.Play)

                if (image != null) {
                    grantToCarHosts(image)
                    setArtworkUri(image)
                } else if (iconRes != null) {
                    setArtworkUri(iconRes.asResourceUri())
                }

                setExtras(extras)
            }.build(),
        )
    }.build()

    /**
     * Artwork is served by the app's ImageProvider, which only apps holding a system media permission
     * may read. Let Android Auto read each artwork address outright, so the art shows on any phone.
     */
    private fun grantToCarHosts(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        for (pkg in CAR_HOST_PACKAGES) {
            runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun Int.asResourceUri() = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(this))
        .appendPath(context.resources.getResourceTypeName(this))
        .appendPath(context.resources.getResourceEntryName(this))
        .build()

    private fun List<LibraryPageElement>.toMediaItems(route: LibraryRoute): List<MediaItem> = flatMap { element ->
        when (element) {
            is LibraryPageElement.Group -> element.items.map { item ->
                item.toMediaItem(
                    route = route,
                    groupTitle = element.title,
                )
            }

            is LibraryPageElement.Item -> listOf(element.toMediaItem(route))
        }
    }

    private fun createPageMediaItem(route: LibraryRoute, page: LibraryPage<*> = route.page!!) = MediaItem.Builder().apply {
        val extras = Bundle()
        val contentStyle = when (page.grid) {
            true -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            false -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        }
        extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, contentStyle)
        extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, contentStyle)

        setMediaId(Json.encodeToString<LibraryMediaId>(LibraryMediaId.Route(route)))
        setMediaMetadata(
            MediaMetadata.Builder().apply {
                setIsBrowsable(true)
                setIsPlayable(false)
                setExtras(extras)
            }.build(),
        )
    }.build()

    private suspend fun createPageContentResult(
        route: LibraryRoute,
        params: LibraryParams? = null,
        pageIndex: Int,
        pageSize: Int,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val page = route.page ?: return LibraryResult.ofError(
            SessionError(
                SessionError.ERROR_BAD_VALUE,
                context.getString(R.string.media_service_unknown_page)
            )
        )

        if (api.baseUrl == null || api.accessToken == null) {
            return LibraryResult.ofError(
                SessionError(
                    SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                    context.getString(R.string.media_service_auth_expired),
                ),
            )
        }

        @Suppress("UNCHECKED_CAST")
        val items = (page as? LibraryPage<LibraryRoute>)?.getContent(
            route,
            pageIndex * pageSize,
            minOf(pageSize, MAX_PAGE_SIZE),
        )?.toMediaItems(route)

        return if (items == null) {
            LibraryResult.ofError(
                SessionError(SessionError.ERROR_BAD_VALUE, context.getString(R.string.media_service_no_items)),
            )
        } else {
            LibraryResult.ofItemList(items, params)
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = CoroutineScope(Dispatchers.IO).future {
        val route = when {
            params?.isRecent == true -> LibraryRoute.Recent()
            params?.isSuggested == true -> LibraryRoute.Suggested
            else -> LibraryRoute.Root
        }

        Timber.d("onGetLibraryRoot $session $browser $params $route")

        val page = route.page

        if (page == null) {
            LibraryResult.ofError(
                SessionError(SessionError.ERROR_NOT_SUPPORTED, context.getString(R.string.media_service_unknown_page)),
                params ?: LibraryParams.Builder().build(),
            )
        } else {
            LibraryResult.ofItem(createPageMediaItem(route, page), params)
        }
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = CoroutineScope(Dispatchers.IO).future {
        Timber.d("onGetChildren $parentId $page $pageSize $params")

        val libraryMediaId = runCatching { Json.decodeFromString<LibraryMediaId>(parentId) }.getOrNull()
        Timber.d("onGetChildren $libraryMediaId")

        if (libraryMediaId !is LibraryMediaId.Route) {
            LibraryResult.ofError(
                SessionError(SessionError.ERROR_BAD_VALUE, context.getString(R.string.media_service_unknown_page)),
            )
        } else {
            createPageContentResult(libraryMediaId.route, params, page, pageSize)
        }
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = CoroutineScope(Dispatchers.IO).future {
        Timber.d("onSearch $query $params")

        // Add fake item count because we have not actually searched yet
        session.notifySearchResultChanged(browser, query, 1, params)
        LibraryResult.ofVoid(params)
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = CoroutineScope(Dispatchers.IO).future {
        Timber.d("onGetSearchResult $query $page $pageSize $params")
        createPageContentResult(LibraryRoute.Search(query), params, page, pageSize)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = CoroutineScope(Dispatchers.IO).future {
        Timber.d("onGetItem $session $browser $mediaId")

        val libraryMediaId = runCatching { Json.decodeFromString<LibraryMediaId>(mediaId) }.getOrNull()
        when (libraryMediaId) {
            is LibraryMediaId.Item -> {
                val item by api.userLibraryApi.getItem(itemId = libraryMediaId.itemId)
                LibraryResult.ofItem(LibraryPageElement.baseItem(api, item).toMediaItem(libraryMediaId.route), null)
            }

            is LibraryMediaId.Route -> {
                val page = libraryMediaId.route.page

                if (page == null) {
                    LibraryResult.ofError(
                        SessionError(
                            SessionError.ERROR_NOT_SUPPORTED,
                            context.getString(R.string.media_service_unknown_page),
                        ),
                    )
                } else {
                    LibraryResult.ofItem(createPageMediaItem(libraryMediaId.route, page), null)
                }
            }

            null -> LibraryResult.ofError(
                SessionError(SessionError.ERROR_BAD_VALUE, context.getString(R.string.media_service_invalid_id)),
            )
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = CoroutineScope(Dispatchers.IO).future {
        Timber.d("onAddMediaItems $mediaSession $controller $mediaItems")

        mediaItems.mapNotNull {
            val libraryMediaId = runCatching { Json.decodeFromString<LibraryMediaId>(it.mediaId) }.getOrNull()
            if (libraryMediaId !is LibraryMediaId.Item) return@mapNotNull null

            val playbackUri = api.universalAudioApi.getUniversalAudioStreamUrl(
                itemId = libraryMediaId.itemId,
                deviceId = api.deviceInfo.id,
                maxStreamingBitrate = 140000000,
                container = listOf(
                    "opus",
                    "mp3|mp3",
                    "aac",
                    "m4a",
                    "m4b|aac",
                    "flac",
                    "webma",
                    "webm",
                    "wav",
                    "ogg",
                ),
                transcodingProtocol = MediaStreamProtocol.HLS,
                transcodingContainer = "ts",
                audioCodec = "aac",
                enableRemoteMedia = true,
            )

            it.buildUpon().apply {
                setUri(playbackUri + "&ApiKey=${api.accessToken}")
            }.build()
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = CoroutineScope(Dispatchers.IO).future {
        Timber.d("onSetMediaItems $mediaSession $controller $mediaItems $startIndex $startPositionMs")

        var expandedItems = mediaItems
        var newStartIndex = startIndex

        // Modify playlist when a single item is requested
        if (mediaItems.size == 1) {
            val firstItem = mediaItems.first()

            // Expand media item to full playlist
            val libraryMediaId = runCatching {
                Json.decodeFromString<LibraryMediaId>(firstItem.mediaId)
            }.getOrNull()

            when {
                // Play via search
                firstItem.mediaId == MediaItem.DEFAULT_MEDIA_ID && firstItem.requestMetadata.mediaUri == null -> {
                    val search = firstItem.requestMetadata.searchQuery
                    Timber.d("Search requested $search")

                    val route = if (search.isNullOrEmpty()) LibraryRoute.Suggested
                    else LibraryRoute.Search(search)

                    @Suppress("UNCHECKED_CAST")
                    expandedItems = (route.page as? LibraryPage<LibraryRoute>)
                        ?.getContent(route, 0, MAX_PAGE_SIZE)
                        ?.toMediaItems(route)
                        .orEmpty()

                    // Android Auto does not support session error reporting
                    // but Google Play requires feedback on all search requests
                    // so fallback to random item playback if no search results are found
                    if (expandedItems.isEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        expandedItems = (LibraryRoute.Suggested.page as LibraryPage<LibraryRoute>)
                            .getContent(LibraryRoute.Suggested, 0, MAX_PAGE_SIZE)
                            .toMediaItems(route)
                    }

                    newStartIndex = 0
                }

                // Expand media item to full playlist
                libraryMediaId is LibraryMediaId.Item -> {
                    Timber.d("Expanding item $libraryMediaId")
                    val page = libraryMediaId.route.page

                    @Suppress("UNCHECKED_CAST")
                    expandedItems = (page as? LibraryPage<LibraryRoute>)
                        ?.getContent(libraryMediaId.route, startIndex, MAX_PAGE_SIZE)
                        ?.toMediaItems(libraryMediaId.route)
                        .orEmpty()

                    newStartIndex = expandedItems
                        .indexOfFirst {
                            (Json.decodeFromString<LibraryMediaId>(it.mediaId) as? LibraryMediaId.Item)?.itemId == libraryMediaId.itemId
                        }
                        .coerceAtLeast(0)
                }
            }
        }

        // Use the server-side stored position if there is no requested start position
        val newStartPositionMs = when (startPositionMs) {
            0L, C.TIME_UNSET -> expandedItems.firstOrNull()?.mediaMetadata?.extras?.getLong(
                MEDIA_ITEM_EXTRA_START_POSITION,
                startPositionMs
            ) ?: startPositionMs

            else -> startPositionMs
        }

        expandedItems = onAddMediaItems(mediaSession, controller, expandedItems).await()

        Timber.d("onSetMediaItems resulted in ${expandedItems.size} items")
        if (expandedItems.isEmpty()) {
            mediaSession.sendError(
                controller,
                SessionError(SessionError.ERROR_BAD_VALUE, context.getString(R.string.media_service_no_items))
            )
        }
        MediaSession.MediaItemsWithStartPosition(expandedItems, newStartIndex, newStartPositionMs)
    }

    /**
     * Something wants the last music back: Android Auto starting music automatically when the phone
     * connects, a Bluetooth play button, or the system media card. Rebuild what was playing from
     * [PlaybackResumptionStore]. Failing the future tells the system there is nothing to resume.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = CoroutineScope(Dispatchers.IO).future {
        Timber.d("onPlaybackResumption $controller isForPlayback=$isForPlayback")

        val saved = resumption.load() ?: throw UnsupportedOperationException("Nothing played yet")
        val ids = saved.mediaIds.map { id ->
            runCatching { Json.decodeFromString<LibraryMediaId>(id) as? LibraryMediaId.Item }.getOrNull()
        }
        val wanted = ids.filterNotNull()
        if (wanted.isEmpty()) throw UnsupportedOperationException("Nothing to resume")

        val result by api.itemsApi.getItems(
            ids = wanted.map { it.itemId }.distinct(),
            imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY),
        )
        val byId = result.items.associateBy { it.id }

        // Songs deleted since are skipped; the current one is found again by its id.
        val items = wanted.mapNotNull { id ->
            byId[id.itemId]?.let { item -> LibraryPageElement.baseItem(api, item).toMediaItem(id.route) }
        }
        if (items.isEmpty()) throw UnsupportedOperationException("The songs are gone")

        val currentId = ids.getOrNull(saved.index)?.itemId
        val index = items.indexOfFirst {
            (Json.decodeFromString<LibraryMediaId>(it.mediaId) as? LibraryMediaId.Item)?.itemId == currentId
        }
        val playable = onAddMediaItems(mediaSession, controller, items).await()
        if (index < 0) {
            MediaSession.MediaItemsWithStartPosition(playable, 0, 0)
        } else {
            MediaSession.MediaItemsWithStartPosition(playable, index, saved.positionMs)
        }
    }
}
