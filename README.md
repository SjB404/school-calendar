# Deltion Rooster

Android app that copies your Deltion College class schedule (roosters.deltion.nl) into your phone's calendar and keeps it up to date, checking about every 3 hours.

## Build

**On your PC** (needs the Android SDK, same as any Android project):

```
gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

**On GitHub:** every push to `main` builds the app and puts `DeltionRooster.apk` on the [latest release](../../releases/latest).

Both ways use the same signing key (`app/debug.keystore`), so a new build installs over the old one.

## Use

1. Install the APK and open it. Allow calendar access.
2. Type your class (for example `SD4A`). Suggestions appear once Deltion's class list loads.
3. Pick where the lessons go:
   - **Only on this phone**: a separate "Deltion Rooster" calendar. Shows in the phone's calendar apps, not on your PC.
   - **A Google calendar**: also shows up in Google Calendar on your PC.
4. Tap **Save and sync**.

It syncs this week plus the next 3 weeks. Lessons that change or get cancelled are removed and re-added. The app only touches events it made itself (they have `#deltionrooster` in the description).

If the phone keeps killing the background sync, set battery use for the app to "Unrestricted".

## If something breaks

Tap **Show raw data**. It copies what Deltion's server actually sent to the clipboard. That shows whether the server is down, blocked the request, or changed its data format.

## How it gets the data

roosters.deltion.nl has no official public API. The app uses the hidden addresses the website itself loads data from:

- `GET /api/group` gives the list of class names
- `GET /api/roster?group=SD4A&start=20261102&end=20261129` gives the lessons

Deltion can change these at any time, without notice.
