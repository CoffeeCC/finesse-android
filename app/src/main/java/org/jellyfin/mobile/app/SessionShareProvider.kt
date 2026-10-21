package org.jellyfin.mobile.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Hands the current sign-in to Finesse Video, the separate car app for video (Android Auto lists
 * one car app per package, and this one is the music app). Read-only, and only apps signed with the
 * same key may read it: see the READ_SESSION signature permission in the manifest.
 *
 * One row: [COLUMNS] = Jellyfin address (Finesse's <address>/jellyfin), access token, user id,
 * device id. No row when nobody is signed in.
 */
class SessionShareProvider : ContentProvider(), KoinComponent {
    private val apiClientController: ApiClientController by inject()
    private val apiClient: ApiClient by inject()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val serverUser = runBlocking { apiClientController.loadSavedServerUser() } ?: return cursor
        val token = serverUser.user.accessToken ?: return cursor
        cursor.addRow(
            arrayOf<Any?>(
                serverUser.server.hostname,
                token,
                serverUser.user.userId.toString(),
                apiClient.deviceInfo.id,
            ),
        )
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private companion object {
        val COLUMNS = arrayOf("base_url", "access_token", "user_id", "device_id")
    }
}
