# Google Play submission materials

The listing JSON files are source drafts for Play Console. Saving a console
draft does not submit it for review or establish production readiness.

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

## Remaining submission inputs

- Confirm the Play application identity, existing releases and signing state.
  The package is `xyz.gaon.codeclear`; the release version name is `1.0.0`.
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
uploading. Only the synthetic fixture activity clears its protected-window
flag; the production application keeps its protection.

### Accessibility review recording

`AccessibilityReviewRecordingDeviceTest` is an explicitly invoked recording
session for the real `MainActivity` consent and settings flows. It accepts only
a debug validation package on reviewed emulator hardware. Use a disposable
emulator containing synthetic alerts; never instrument or record owner history.
The session temporarily makes that synthetic activity recordable, restores its
protected-window flag on exit, and times out after three minutes. Release
packages do not contain the session or an option to disable protection.

An external reviewer drives ordinary controls, including refusal, reopening the
separate accessibility explanation, consent, Android's grant screen and the
feature using accessibility. The session exposes a private
`cache/accessibility-review.ready` marker and finishes when the controller
creates `cache/accessibility-review.stop` through ADB `run-as`. Preserve the
distinction between an emulated system-receiver injection and physical radio
reception in the video's captions. Review the recording before sharing it, and
host it only at an authorized review destination.

Keep configured APKs/AABs, credentials and the actual translation route out of
this directory and public repository. Follow [private build instructions](../../tools/PRIVATE_BUILDS.md).

## Official references

- [Preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)
- [Prepare an app for review](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en)
- [Accessibility API declaration](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- [Data safety requirements](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
- [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
