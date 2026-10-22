# 卡布 Android

The `kabu` branch is a Kabu Xiyou launcher built on [Ruffle](https://ruffle.rs).
Install **卡布**, enter your 4399 username and password, and tap **登录并进入游戏**.
It obtains a fresh `auth_string` using the same protocol as `kabu-login`, then opens
`GreenGame.swf` with the token as a FlashVar and the game's resource base URL configured automatically.
Passwords are not saved to files, preferences or saved instance state, and are not logged.
Accounts requiring a CAPTCHA cannot complete verification in this shortcut login screen.

The menu button opens a small floating toolbar. Drag it to move the toolbar;
it docks to the nearest side and stays inside the display's safe area. The menu includes **重新加载**,
which restarts the player with the same game and launch parameters. Exit returns to the login screen.

Tap a Flash text field to open the phone's input method.
Chinese composition, committed Unicode text, backspace and forward delete are supported.
The keyboard overlays the game without resizing its render surface. For a movable small
keyboard, enable **悬浮键盘** / **游戏键盘** in your input method's own menu; its appearance
and availability depend on the installed input method. Back or the IME's Done button hides it.
Baidu Input Honor's dedicated game keyboard is a separate mode controlled by the input method's
application recognition. On the tested device, enabling its game-keyboard switch and declaring
the app as a game did not activate that mode for Ruffle; ordinary floating mode remains available.

The application ID is `rs.ruffle.kabu`, so this build can coexist with the general Ruffle app.
The app label is **卡布**, and CI produces `卡布-<ABI>-release.apk` files in the **kabu-release-apks** artifact.
Pushes to `kabu` automatically trigger the Build workflow. The `main` branch remains aligned with upstream;
general Android improvements are maintained on `base`.
Release versions use tags; the shared development branch name stays unchanged.

The general Ruffle documentation below applies to the non-Kabu build unless otherwise stated.

It is in a very early stage.

# Prebuilt APKs

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="75">](https://f-droid.org/packages/rs.ruffle/)

The latest release [(here)](https://github.com/ruffle-rs/ruffle-android/releases) should have a few `.apk` files uploaded as assets.

You can try this app by downloading and installing one of those.

- **For the vast majority of modern phones, tablets, single board computers, and small game consoles, you'll need the `arm64-v8a` version.**

- The `armeabi-v7a` version is for older, 32-bit ARM stuff.

- The `x86_64` version is for some rare Intel/Microsoft tablets and/or for Chromebooks, and/or for running on a PC on Android-x86 or in Waydroid or similar.

- The `x86` version is there mostly just for completeness.

- The `universal` version should work on all 4 of the above architectures, but it's _huge_.

# Building from source

Please see [CONTRIBUTING.md](CONTRIBUTING.md#building-from-source) for details about how to build this repository yourself.

## Loading resources from an HTTP server

On the opening screen, set **Custom base URL (optional)** before selecting a local SWF or opening a SWF URL.
For example, with `http://192.168.1.100:8000/game/` as the base URL, a SWF request for `images/title.png`
loads `http://192.168.1.100:8000/game/images/title.png`. End directory URLs with `/`.
Leave the field empty to use the SWF location. This setting resolves relative URLs; absolute URLs inside the SWF are unchanged.

Use a server address reachable from the Android device. `localhost` and `127.0.0.1` refer to the Android device itself;
for a server running on your computer, use the computer's LAN IP address (or `10.0.2.2` in the standard Android emulator).
HTTP requests are supported without automatically upgrading them to HTTPS.

## Development core and Chinese fonts

This development branch pins all Ruffle crates to commit `96672e20dc02566a6befd17a3a9cd18d9c773de6`
of `aojiaoxiaolinlin/ruffle`. It includes the shared Scale9 rendering, AVM2 broadcast-event propagation,
and default-font glyph fallback fixes used by our desktop build. Update all Ruffle revisions together
and regenerate `Cargo.lock` when changing the core.

The Android player loads device fonts from Android's system, product, vendor, and updated-font directories.
Missing Windows font families use Android alternatives, and Chinese glyphs can fall back to Noto CJK
or Droid Sans Fallback, with simplified Chinese preferred. Font collections retain their face indices
and share file data between faces. No extra font download or storage permission is needed.
Available glyphs still depend on the fonts installed by the device manufacturer.

## Building APKs with GitHub Actions

The repository includes a **Build** workflow in `.github/workflows/build.yml`.
After pushing your changes to your fork, enable Actions if necessary, select **Actions → Build → Run workflow**,
and choose the branch containing your changes. Pushes to `main` and pull requests targeting `main` also trigger the workflow.

Once the `build-apks` job succeeds, download the **ruffle-release-apks** artifact from the workflow run.
It contains APKs for all four supported ABIs and a universal APK. Most current Android devices use `arm64-v8a`.
No signing secrets are required for a personal build: the workflow generates a temporary signing key when `KEYSTORE` is absent.
That key changes between runs, so installing a later build may require uninstalling the previous one, which removes app data.
For consistent signing, configure `KEYSTORE` (base64-encoded keystore), `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`,
and `SIGNING_STORE_PASSWORD` as repository secrets.

---

# TODO

In no particular order:

- [X] Ability to show the system virtual keyboard (softinput), for text input
- [ ] Controller/Gamepad input?
  - Mapped to key presses and/or virtual mouse pointer
- [ ] Own custom keyboard overlay, maybe even per-content configs
- [ ] Error/panic handling
- [ ] Loading "animation" (spinner)
- [ ] Alternative audio backend (OpenSL ES) for Android < 8
- [ ] Proper storage backend?
- [ ] Resolve design glitches/styling/theming (immersive mode, window insets for holes/notches/corners)
- [ ] Publish to various app stores, maybe automatically?
- [ ] Bundle demo animations/games
- [ ] Add ability to load content from well known online collections? (well maybe not z0r... unless?)
- [ ] History, favorites, other flair...?

### DONE:

- [X] Clean up ~everything
- [X] Cross-platform build instructions?
  - I think gradle should take care of it now
- [X] UI backend (context menu)
  - Context menu works
- [X] Logging?
- [X] Navigator backend (fetch, open browser)
  - Opening links works at least
- [X] Touch/mouse input
- [X] Keyboard input: physical keyboard, `scrcpy`, and the system input method
  - This was needed: https://github.com/rust-windowing/winit/pull/2226
- [X] Split into a separate repo
- [X] Add ability to Open SWF by entered/pasted URL (or even directly from clipboard)
  - No direct clipboard open, but easy to paste into the text field...
- [X] Unglitchify rendering: scale, center and letterbox the content properly
- [ ] Ask CPAL/Oboe to open a "media" type output stream instead of a "call" one
  - so the right volume slider controls it, and it uses the loud(er)speaker
  - -> solved by switching to a direct AAudio (ndk-audio) backend
- [X] Add building this to CI, at least to the release workflow
  - This repo has its own CI setup, which builds APKs
- [X] Simplify build process (hook cargo-apk into gradle, drop cargo-apk?)
  - ~cargo-apk is fine, but is only used to detect the SDK/NDK environment and run Cargo in it, and not to build an APK.~
  - actually solved by switching to `cargo-ndk` and the corresponding Gradle plugin
- [X] Somehow filter files to be picked to .swf
  - How well this works depends on the file picker, but it "should work most of the time"
- [X] Unglitchify audio volume (buttons unresponsive?)
  - (pending: https://github.com/rust-windowing/winit/pull/1919)
  - actually solved by switching to GameActivity instead
- [ ] Register Ruffle to open .swf files
  - How well this works depends on the application opening the file, but it "should work most of the time"
- [X] Figure out why videos are not playing (could be a seeking issue)
  - The video decoder features weren't enabled on `ruffle_core`...
- [X] Sign the APK
  - Using a very simple key for now, with just my name in it
- [X] Support for 32-bit ARM phones
  - Untested, but should work in theory
- [X] Support for x86(_64) tablets?
  - Sorted out
- [X] Consider not building the intermediate .apk just for the shared libraries
  - Figured out, no intermediate .apk any more, only native libs built
- [ ] Unbreak the regular build on CI
  - No longer relevant after the repo split
- [ ] Clean up commit history of the branch
  - No longer relevant after the repo split
