# Kosher Photos

A small Android app that opens the Google Photos website (photos.google.com) in its own window, with the "create album" / print-store pages blocked.

## Get the APK (no computer needed)

1. Open this repo on github.com and tap the **Actions** tab.
2. Open the latest **Build APK** run (green check = success).
3. Scroll to **Artifacts** and download **gphotos-viewer-debug-apk**.
4. Unzip it and install `app-debug.apk`.

To rebuild any time: Actions > Build APK > Run workflow.

## Changing which pages are blocked

Edit `BLOCKED_URL_CONTAINS` in
`app/src/main/java/com/example/gphotosviewer/MainActivity.java`.
Any Google Photos address containing one of those pieces of text is refused.
