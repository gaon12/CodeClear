# Google Play submission materials

The listing JSON files are local drafts. They have not been entered into Play
Console or submitted for review. They do not establish production readiness.

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
  The latest optimized artifacts are unsigned and are not upload-ready.
- Complete the current optimized-runtime and capture validation gates. Do not
  describe a debug installation or a successful bundle build as a final release.
- Provide the public privacy-policy URL and a working privacy contact. Confirm
  the deployed service's logging, retention and deletion behavior before making
  declarations about hosting metadata or provider handling.
- Complete accurate Data safety, accessibility and foreground-service forms,
  app-access instructions, audience/content-rating and distribution settings.
- Prepare the app icon, feature graphic, localized screenshots and accessibility
  demonstration video. No completed image assets are included in this folder.

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

Keep configured APKs/AABs, credentials and the actual translation route out of
this directory and public repository. Follow [private build instructions](../../tools/PRIVATE_BUILDS.md).

## Official references

- [Preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)
- [Prepare an app for review](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en)
- [Accessibility API declaration](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- [Data safety requirements](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
- [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
