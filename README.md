# Finesse for Android

The [Finesse](https://github.com/CoffeeCC/finesse) app for Android phones, with **Android Auto**:
your music on the car screen, next to Maps.

On the phone it's Finesse, exactly as in the browser. In the car, Android Auto shows Finesse's
music (recently added, albums, artists, favourites, genres, playlists, search) with Android
Auto's own player, in the split screen beside navigation.

## Install

1. Download the `.apk` from [Releases](https://github.com/CoffeeCC/finesse-android/releases) and open it on
   your phone (allow installing from your browser when Android asks).
2. Open **Finesse**, type your Finesse address (for example `192.168.1.20:8080`, or your
   Tailscale / Cloudflare address for use away from home) and sign in.
3. **Android Auto** only lists apps from the Play Store unless you allow others:
   in the Android Auto settings on your phone, tap **Version** ten times to unlock developer
   settings, then ⋮ → **Developer settings** → turn on **Unknown sources**. Finesse then appears
   in the car's app list.

### Finesse Video (experimental, parked only)

A second car app, **Finesse Video**, lists Continue watching and Recently added and plays them on
the car screen, but only while the car is stopped. Android Auto doesn't tell apps the gear, so the
app reads the car's speed: video is allowed after 10 seconds stopped, pauses and is covered the
moment the car moves, and never restarts by itself. If the car doesn't share its speed, video stays
off. It draws on the surface Android Auto gives navigation apps, which Google doesn't intend for
video, so an Android Auto update may break it, and it can't go on the Play Store.

In the car your phone is usually on mobile data, so Finesse needs to be reachable away from home
(Finesse's Settings → Server → Downloads & away from home).

## How it works

This is a fork of [Jellyfin for Android](https://github.com/jellyfin/jellyfin-android) (see
[README.jellyfin.md](README.jellyfin.md)). Finesse runs Jellyfin behind it at
`<your address>/jellyfin`, so the Jellyfin app's native parts work unchanged:

- The app shows Finesse (`<your address>/finesse/`) instead of Jellyfin's web app, and doesn't
  add Jellyfin's web-app shell to it (`webapp/JellyfinWebViewClient.kt`, `webapp/WebViewFragment.kt`).
- When Finesse signs in, the app reads its sign-in (`finesse.session` in the page's storage) and
  hands it to the native player and the Android Auto service (`sessionbrowser/`), which talk to
  Jellyfin directly.
- Setup takes your Finesse address and finds the Jellyfin behind it (`utils/FinesseUrls.kt`,
  `setup/ConnectionHelper.kt`).
- Its own name, icon and package id (`com.coffeecc.finesse`), so it installs next to the
  Jellyfin app.

## Building

```bash
./gradlew assembleLibreDebug      # app/build/outputs/apk/libre/debug/
./gradlew testLibreDebugUnitTest
```

## License

GPL-2.0, like the Jellyfin app it's built from (see [LICENSE.md](LICENSE.md)). Finesse itself is
MIT; this app is a separate program that opens it.
