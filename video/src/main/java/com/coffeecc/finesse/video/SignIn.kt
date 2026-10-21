package com.coffeecc.finesse.video

import android.content.Context
import android.net.Uri
import android.util.Log
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

/**
 * The main Finesse app's sign-in, read from its SessionShareProvider (only apps signed with the same
 * key may). Finesse Video has no sign-in of its own: sign in to Finesse on the phone.
 */
object SignIn {
    sealed interface Result {
        data class SignedIn(val api: ApiClient) : Result

        /** The main Finesse app isn't installed (or is an older one without sharing). */
        data object NoFinesse : Result

        /** Finesse is installed but nobody is signed in. */
        data object SignedOut : Result

        /** Finesse refused: the two apps weren't signed with the same key. */
        data object NotAllowed : Result
    }

    private const val TAG = "FinesseVideo"

    fun read(context: Context): Result {
        val uri = Uri.parse("content://${BuildConfig.SESSION_AUTHORITY}")
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return Result.NoFinesse
            cursor.use { c ->
                if (!c.moveToFirst()) return Result.SignedOut
                val baseUrl = c.getString(c.getColumnIndexOrThrow("base_url"))
                val token = c.getString(c.getColumnIndexOrThrow("access_token"))
                val deviceId = c.getString(c.getColumnIndexOrThrow("device_id"))
                Result.SignedIn(api(context, baseUrl, token, deviceId))
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Finesse didn't share its sign-in", e)
            Result.NotAllowed
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unexpected sign-in from Finesse", e)
            Result.NoFinesse
        }
    }

    private fun api(context: Context, baseUrl: String, token: String, deviceId: String): ApiClient =
        createJellyfin {
            this.context = context.applicationContext
            clientInfo = ClientInfo(name = "Finesse Video", version = BuildConfig.VERSION_NAME)
        }.createApi(
            baseUrl = baseUrl,
            accessToken = token,
            // The main app's device: playback shows up as the same phone on the server.
            deviceInfo = DeviceInfo(id = deviceId, name = "Finesse Video"),
        )

    fun explain(result: Result): String = when (result) {
        is Result.SignedIn -> "Signed in to ${result.api.baseUrl?.removeSuffix("/jellyfin") ?: "Finesse"}."
        Result.NoFinesse -> "Install the Finesse app and sign in to your server first."
        Result.SignedOut -> "Open Finesse on your phone and sign in."
        Result.NotAllowed ->
            "Finesse wouldn't share its sign-in. Install Finesse and Finesse Video from the same place, so they're signed with the same key."
    }
}
