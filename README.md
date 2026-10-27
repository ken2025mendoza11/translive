# TransLIVE

Chinese → English, drawn on top of the shopping app you already have open.

This is for private testing on your own Android phone. It is not published to the Play Store or any other store.

The app works with no account and no server. A built-in dictionary, a saved cache on the phone, and on-device translation cover everyday shopping screens. A cloud library is optional and stays empty until you add your own keys on a server.

## What each folder is

1. `android/` — the phone app (Kotlin). This is what becomes TransLIVE.apk.
2. `shared/dictionary/` — the shopping phrases shipped inside the app.
3. `backend/` — optional Supabase tables and a translation proxy. You can ignore this until you want shared phrases.
4. `.github/workflows/android.yml` — builds the APK on GitHub every time the code is pushed.

## Install on your phone

When the GitHub build is green, open the latest release and download **TransLIVE.apk**.

On the phone:

1. Open the release link in Chrome and tap **TransLIVE.apk**.
2. If Android asks, allow the browser to install unknown apps.
   - vivo / OriginOS: **Settings → Security & privacy** (sometimes **More settings**) → **Install unknown apps** → allow Chrome or the File Manager.
   - If a “safety check” or “risk app” scan blocks it, turn that prompt off for this install.
3. Open **TransLIVE**.
4. Read the three short screens and tap **I understand**.
5. Tap **Open Accessibility settings**. Then **Accessibility → Downloaded / Installed services → TransLIVE → On**.
   - On Android 13 or newer, if the switch is grey: open **App info → ⋮ → Allow restricted settings**, then come back and turn TransLIVE on.
6. Tap **Allow display over other apps** and switch TransLIVE on.
7. vivo battery: **Settings → Battery → Background power consumption → TransLIVE → Allow**. Also lock TransLIVE in the recent-apps screen so the system does not kill it.
8. Leave **Chinese shopping app** switched on. Open that shopping app. A green **Live** tab sits on the right edge. English is drawn over the Chinese. Touches still reach the buttons underneath.
9. Drag the tab up or down. Tap it for Pause, image text, and Show original. Hold it to report a phrase.

Share any screenshot to TransLIVE to translate a still picture.

## Update later

1. On GitHub, open the repo → **Releases**.
2. Download the newest **TransLIVE.apk** (the tag looks like `build-2`).
3. Install it over the old one. Your on-phone settings stay.

## If a build fails

1. Open the repo → **Actions**.
2. Tap the red run.
3. Copy the red error text and send it back. The project files can be fixed without starting over.

## Add the Gradle wrapper later (only if you build on a computer)

The APK from GitHub does not need this. Inside `android/`:

```
gradle wrapper --gradle-version 9.6.0
```

The wrapper jar is not stored in the repo. GitHub Actions installs Gradle 9.6 itself.

## Optional shared library

Skip this for the first install. Dictionary, cache, and on-device translation already work.

1. Create a Supabase project.
2. Run `backend/supabase/migrations/0001_phrases.sql` in the SQL editor.
3. Deploy `backend/supabase/functions/translate-proxy`.
4. Set secret `TRANSLATOR_KEY` there. Do not put that key in the app.

## Honest limits

- Some shopping screens hide their text from Accessibility. TransLIVE then uses the camera-style text reader (OCR) on banners. Stylised lettering is less accurate.
- The service sleeps until a selected app is in front. Phone makers can still kill background apps. Lock TransLIVE in Recents and allow background battery use.
- Publishing this kind of accessibility tool on the Play Store has extra policy rules. This project does not publish anywhere. Distribution is the APK from GitHub Releases only.
