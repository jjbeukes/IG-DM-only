# IG-DM-only (DM Only)

DM only ig for focus.

A distraction-free Android app for Instagram. It keeps **direct messages**, **profile search**, **profiles** (yours and other people's) and **followers / following lists**, and nothing else.

| You can | You can't |
|---|---|
| Log in (username + password, 2FA, security checks) | See the home feed |
| Read and send DMs: text, photos, likes and reactions | See stories, story rings or highlights |
| Search for accounts | Watch reels (tab, links, or reels shared in DMs) |
| Open any profile and follow / unfollow | See posts (profile grids, post pages, posts shared in DMs) |
| Open followers / following lists and the profiles in them | Use Explore, hashtag/place/keyword search or suggested posts |
| Open your own profile and lists | See notes, notifications\*, Threads, Meta AI, create/upload, live |

\* Notifications can be switched on in Settings, because that is where follow requests for private accounts are approved.

Posts, reels and stories that people send you in a chat are replaced by a small "Shared post hidden" placeholder and can't be opened. Photos and videos someone sends you directly still show. Settings has an option to blur those too, so a tap reveals them.

## Install

1. On your phone, open the [latest release](https://github.com/jjbeukes/IG-DM-only/releases/latest) and download `DM-Only-<version>.apk`. Recent builds of every commit are also available for a limited time under [Actions](https://github.com/jjbeukes/IG-DM-only/actions); those downloads need you to be signed in to GitHub.
2. Open the APK. Android will ask you to allow installs from your browser or file manager the first time.
3. Open **DM Only** and log in with your Instagram username and password.

Things to know when logging in:

- "Log in with Facebook" and Google sign-in generally don't work inside an app's web view (Meta and Google block it). If your account only uses Facebook login, set an Instagram password first.
- If Instagram asks to "Save your login info", either choice is fine.
- The session is kept until you log out under **Settings → Log out and clear data**.

## How it works

DM Only is a small native app (Kotlin) that shows Instagram's own mobile website, `www.instagram.com`, in a WebView. It doesn't use Instagram's private API or a modified Instagram app. Your phone talks to Instagram exactly as Chrome would.

There are three layers:

1. **A URL allow-list.** [`UrlPolicy.kt`](app/src/main/java/com/jjbeukes/dmonly/UrlPolicy.kt) decides every page the app may show: DMs, the search page, profiles, follow lists, and the login and security pages. Anything else is refused before it loads. If Instagram's single-page app has already switched to it, the app steps back. The home feed redirects to your inbox.
2. **An injected script.** [`dmonly.js`](app/src/main/assets/dmonly.js) runs before Instagram's own code on every page. It cancels taps and in-app navigation to refused pages, and asks `UrlPolicy` synchronously so there is only one set of rules. It hides Instagram's own tab bar, story rings and highlights, profile post grids, the notes tray, suggested accounts and non-account search results. It covers shared posts and reels in chats, and closes the in-chat reel viewer if it ever opens. Elements are found by where their links go, by ARIA roles and by layout, never by Instagram's generated class names.
3. **A native bottom bar** with Chats, Search, Profile and Settings. The app reads your username from Instagram's page so the Profile tab knows where to go. If it can't, it asks once.

Other details:

- The microphone is offered only to `www.instagram.com`, and only after you allow it.
- The camera is offered only to Meta's selfie identity check, which can appear when logging in.
- Links to other websites open in your browser.
- Links that would launch the real Instagram app are dropped.

## Known limitations

- **No push notifications.** Android WebViews can't receive web push. The Chats tab shows an unread count while the app is open.
- **Calls** aren't available on Instagram's mobile website.
- **Voice messages:** received ones play. Recording depends on Instagram's mobile site offering it; the app grants the microphone if it asks.
- **Instagram changes its website often.** The URL allow-list is the guarantee. The hiding of individual elements, such as the notes tray and shared-post detection, is best effort and may need updating when Instagram changes its markup. If something slips through, open an issue with what you saw.

## Building

Every push builds and tests the app on GitHub Actions ([`build.yml`](.github/workflows/build.yml)):

- unit tests for the URL policy
- jsdom tests for the injected script ([`tools/js-tests`](tools/js-tests))
- a release APK, uploaded as an artifact

Pushing a tag such as `v1.0.1` also publishes a GitHub release with the APK attached.

To build locally you need JDK 17 and the Android SDK (platform 37):

```bash
./gradlew testDebugUnitTest assembleRelease
```

Toolchain: AGP 9.4.1 (built-in Kotlin), Gradle 9.8.2, compileSdk/targetSdk 37, minSdk 26.

### Signing

Android only installs an update over an app signed with the **same key**. Until a release key is set up, CI signs each build with a throwaway key. To install a newer build you then have to uninstall the old one, which logs you out.

To give every build the same key:

1. Create a keystore once and keep it safe (for example in a password manager):

   ```bash
   keytool -genkeypair -v -keystore release.jks -storetype PKCS12 -alias dmonly -keyalg RSA -keysize 2048 -validity 10000
   ```

2. In the repository, go to **Settings → Secrets and variables → Actions** and add these secrets:
   - `RELEASE_KEYSTORE_BASE64`: the keystore, base64-encoded (`base64 -w0 release.jks`, or `[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path release.jks).Path)) | Set-Clipboard` in PowerShell, which copies it straight to the clipboard)
   - `RELEASE_STORE_PASSWORD`
   - `RELEASE_KEY_ALIAS` (`dmonly` above)
   - `RELEASE_KEY_PASSWORD` (for the PKCS12 keystore above, the same as the store password)

For local builds, put the same values in a gitignored `keystore.properties` in the project root, with the keys `storeFile` (relative to the project root, or absolute), `storePassword`, `keyAlias` and `keyPassword`. A partly configured key fails the build rather than silently signing with a throwaway key.

## Disclaimer

A personal project. It isn't affiliated with, endorsed by or connected to Instagram or Meta. It shows Instagram's public website and hides parts of it on your own device, much like a content blocker in a browser. Use it in line with Instagram's Terms of Use.
