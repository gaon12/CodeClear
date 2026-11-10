# Google Play submission materials

CodeClear 1.0.0 is [available on Google Play](https://play.google.com/store/apps/details?id=xyz.gaon.codeclear).
The first published release uses version code 5. The listing JSON files retain
the localized submission sources for future updates. Saving a future Console
draft does not submit it for review or establish production readiness.

## Prepared 1.0.1 update

The next bundle uses version code 6. [Localized release notes](releases/1.0.1.json)
describe local issuer estimates for existing history, foreground automatic speech,
private installation-scoped translation access, unverified nearby receipts and
presentation improvements. This source file does not establish an upload,
review submission or published update; verify those states in Play Console.

## Localized listings

Each file contains a title, short description and full description for its
Play locale: English (United States), Korean, Japanese, French, German,
Simplified Chinese and Traditional Chinese. The corresponding application
language tags for Chinese are `zh-Hans` and `zh-Hant`.

The title is limited to 30 characters, the short description to 80, and the full
description to 4,000. Review the wording with native speakers before publication.
The copy describes source-dependent capture rather than promising that every
Android device can expose every alert. Accessibility use and the separate
remote-translation permission are stated in every language.

## Future submission checklist

- Confirm the Play application identity, existing releases and signing state.
  The package is `xyz.gaon.codeclear`; the prepared version name is `1.0.1`.
  Sign the optimized bundle with the protected upload key and verify it before
  uploading. An unsigned bundle is not upload-ready.
- Complete the applicable optimized-runtime and capture validation gates. Do not
  describe a debug installation or a successful bundle build as a final release.
- Provide the public privacy-policy URL and a working privacy contact. Confirm
  the deployed service's logging, retention and deletion behavior before making
  declarations about hosting metadata or provider handling.
- Complete accurate Data safety, accessibility and foreground-service forms,
  app-access instructions, audience/content-rating and distribution settings.
- Review the app icon, feature graphic, localized screenshots and accessibility
  demonstration video. Generated image assets are kept outside the repository.

ML Kit documents collection of diagnostic/usage data, including device/app
information, installation identifiers, performance information and language
configuration. Local text translation must not be described as zero SDK data
collection. Review the actual dependency versions and every enabled remote or
nearby feature when preparing the app's overall Data safety answers.

## Image preparation

Use the selected native vector icon for a 512 by 512 pixel PNG store icon.
Capture actual application screens with clearly labeled synthetic alerts;
never use the owner's private history or real alert text in public assets.
Retain each language's original/translation distinction and avoid claims of
official affiliation, guaranteed delivery or verified severity without evidence.

Plan four portrait screens per language at 1080 by 1920 pixels. Use JPEG or
24-bit PNG without alpha. The screenshot dimensions must remain between 320
and 3840 pixels, with the longer dimension at most twice the shorter. Review
captures rather than stretching a taller device image into that aspect ratio.
Use ADB only on the explicitly prepared fixture/device. Do not disable the
owner application's protected-window behavior to extract private content.

### Explicit synthetic export

`PlayStoreAssetsDeviceTest` renders the application's actual screen components
with three distinct, clearly marked drill messages. Translations are fixture
text: these captures demonstrate presentation, not translation accuracy or
successful emergency reception. Its callbacks cannot request translations,
download models or delete history.

Run the class explicitly on a disposable Android 11 or later emulator with a
1080 by 1920 pixel display. Build the debug application and its instrumentation
APK with `-Pcodeclear.validationBuild=true`, verify that both package identities
use `xyz.gaon.codeclear.validation`, and install only those APKs. The fixture
refuses to export from the owner's normal application package. It is excluded
from the routine device-fixture selection and from release packages.

The instrumentation class is
`xyz.gaon.codeclear.mobile.PlayStoreAssetsDeviceTest`, using
`xyz.gaon.codeclear.CollectionPreservingTestRunner`. Outputs are in the
validation application's private `cache/play-assets` directory: four JPEG
screens per application locale, a 512-pixel native icon and a 1024 by 500 pixel
feature graphic. Export with ADB `run-as` for that validation package, retain
the original pixels, verify file dimensions, and inspect each locale before
uploading. The fixture clears its own protected-window flag for synthetic exports.
Product screens allow task previews and owner screenshots; raw capture diagnostic
windows retain their separate protection.

### Accessibility review recording

`AccessibilityReviewRecordingDeviceTest` is an explicitly invoked recording
session for the real `MainActivity` consent and settings flows. It accepts only
a debug validation package on reviewed emulator hardware. Use a disposable
emulator containing synthetic alerts; never instrument or record owner history.
The session makes the synthetic activity available for a controlled recording
and times out after three minutes. Release packages do not contain the session
or its ready/stop markers.

An external reviewer drives ordinary controls, including refusal, reopening the
separate accessibility explanation, consent, Android's grant screen and the
feature using accessibility. The session exposes a private
`cache/accessibility-review.ready` marker and finishes when the controller
creates `cache/accessibility-review.stop` through ADB `run-as`. Preserve the
distinction between an emulated system-receiver injection and physical radio
reception in the video's captions. Review the recording before sharing it, and
host it only at an authorized review destination.

Keep configured APKs/AABs, credentials and the actual translation route out of
this directory and Git history. Explicitly authorized release binaries belong in
[GitHub Releases](https://github.com/gaon12/CodeClear/releases); they contain the
same configured route distributed through Play, but must never contain client
tokens or signing keys. Follow [private build instructions](../../tools/PRIVATE_BUILDS.md).

## Release files and update compatibility

Archive the exact upload-signed AAB accepted for the published version. An AAB is
for publishing and cannot be installed directly. For a public installable APK,
download the **signed universal APK** for that version from Play Console under
**Test and release > Latest releases and bundles > Downloads**. Verify its package,
version code, non-debuggable state, signature and app-signing certificate before
uploading it, and publish SHA-256 file checksums alongside the assets.

The upload key authenticates AAB submissions; Google Play signs installed APKs
with the app-signing key. Matching only the upload certificate does not establish
APK update compatibility. Preserve the same package identity and compatible
app-signing certificates across distribution channels, and increment the version
code for future Play updates. Do not publish the local `device-release.apk` files:
they preserve an existing debug-signing identity and cannot update Play installs.
Do not uninstall an existing local build to resolve a signing mismatch without a
supported history-preserving migration.

## Official references

- [Preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)
- [Prepare an app for review](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en)
- [Accessibility API declaration](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- [Data safety requirements](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
- [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
