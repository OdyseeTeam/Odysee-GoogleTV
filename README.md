# Odysee Android TV / Fire TV

Native Android TV and Fire TV client for Odysee, built in Kotlin with a TV-first Compose UI.

This project is currently focused on a practical MVP: browsing content, signing in, opening channels, and playing videos reliably on 10-foot devices.

## Overview

The app provides a native TV interface for browsing Odysee content with a remote-first navigation model. It is shared across Android TV and Fire TV and uses Compose for UI, OkHttp for networking, and Media3 / ExoPlayer for playback.

## Features

- Android TV launcher app with Leanback support
- Shared Android TV / Fire TV codebase
- Remote-first focus navigation
- Frontpage category browsing with pagination
- Search
- Channel pages
- Follow / unfollow actions
- Email sign-in and password sign-in
- Persisted auth session
- Signed-in `Following` and `Watch Later` feeds
- Default channel switching for multi-channel accounts
- Media3 / ExoPlayer playback
- Recommended videos and autoplay handoff
- Fire and Slime reactions in the player

## Stack

- Kotlin
- Jetpack Compose
- Material 3
- OkHttp
- Media3 / ExoPlayer
- Single app module: `:app`

## Requirements

- Android Studio (latest stable recommended) or JDK 17 + command-line Android SDK tools
- Android SDK Platform 34
- Android SDK Build-Tools 34.x or newer
- Android SDK Platform-Tools (`adb`)
- Android TV emulator or a physical Android TV / Fire TV device

Android Studio's bundled JDK is fine. No app-specific secrets are required for a local debug build.

## Build

Open `odysee-androidtv/` in Android Studio and let Gradle sync, or build from the command line:

```bash
./gradlew assembleDebug
```

## Install

Install to a connected emulator or device:

```bash
./gradlew installDebug
```

Install the built APK directly:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Fire TV

Enable Developer Options and ADB Debugging on the Fire TV device, then connect over ADB:

```bash
adb connect <fire-tv-ip>:5555
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project Layout

- `app/src/main/java/com/odysee/androidtv/ui` - app shell, layouts, dialogs, and player UI
- `app/src/main/java/com/odysee/androidtv/feature/auth` - auth flow and channel switching
- `app/src/main/java/com/odysee/androidtv/feature/discover` - categories, feeds, search, follows, channels
- `app/src/main/java/com/odysee/androidtv/feature/player` - playback state, recommendations, reactions
- `app/src/main/java/com/odysee/androidtv/core` - API client, auth persistence, shared helpers
- `app/src/main/assets` - packaged artwork and SVG assets

## Notes

- `minSdk` is 26
- `targetSdk` is 34
