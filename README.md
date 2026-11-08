# CodeClear

CodeClear is an Android app for preserving emergency-alert originals and showing
translations alongside them. It targets Android 11 (API 30) and later smartphones
and tablets, with on-device translation as the default.

## Download

[Get CodeClear on Google Play](https://play.google.com/store/apps/details?id=xyz.gaon.codeclear)

CodeClear 1.0.0 is available on Google Play. See [GitHub Releases](https://github.com/gaon12/CodeClear/releases)
for archived release files and their checksums. An Android App Bundle (`.aab`)
is a publishing artifact and cannot be installed directly on a device.

APK updates require the same package identity and compatible app-signing
certificates; future Play updates also require a higher version code. The AAB
upload certificate can differ from the certificate Google Play uses for installed
APKs. Public APKs must come from Play Console's signed universal APK download,
or use the same app-signing identity. Local debug-signed builds use a separate
identity. See [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en).

## Project status

Reliable recovery of complete alert bodies remains the primary capture criterion.
Store availability, Android version compatibility, a successful build, or a working
translation engine does not establish capture support for every alert source and
firmware profile.

Home, searchable history, original/translation details, model management, speech
playback, and settings are implemented. The interface supports English, Korean,
Japanese, French, German, Simplified Chinese, and Traditional Chinese.

## Capture and translation boundaries

- Capture uses reviewed system alert sources through notification access and a
  scoped accessibility service. The user grants these accesses in Android
  Settings. The app does not acknowledge or dismiss system warnings for the user.
- Wi-Fi-only hardware cannot receive cellular emergency broadcasts or use their
  automatic capture path. A cellular device using Wi-Fi, lacking a SIM, or having
  mobile data disabled is not necessarily Wi-Fi-only hardware.
- ML Kit is the default translation engine. Model preparation requires an explicit
  download action; background translation does not silently download models.
- Android system translation is available only when the installed provider exposes
  a supported language pair. Service presence alone does not establish support.
- Labs contains experimental nearby sharing and MyMemory translation. Configured
  private builds can also expose the fixed CodeClear translation service. Each
  remote service requires its own default-off permission and retention/quota
  disclosure. There is no silent remote fallback.
- Alert history stays in app-private storage until deletion. App backup and device
  transfer are disabled. Remote permission covers automatic captured-alert
  translation and requested historical/peer-original translations. Nearby sharing
  has its own disclosure and permission for peer exchange.
- The optional owned backend is in `server/php`; the owner deploys it separately.
  Private builds inject only its route, while the client token is stored through
  Android Keystore-backed encryption. See [private builds](tools/PRIVATE_BUILDS.md).
  Arbitrary provider endpoints and provider-key integration remain out of scope.

[Google Play materials](distribution/play/README.md) include localized listing
sources and a checklist for future submissions. Store approval does not replace
source-specific capture or optimized-runtime validation.

## Capture evidence and remaining limits

This is an evidence summary as of November 8, 2026, not a certification of every
device running Android 11+. A candidate system app, granted access, and a connected
service are prerequisites. They do not prove that every original was exposed or
saved. Home describes a connection; it does not report verified delivery coverage.

| Profile or scenario                                                                                  | Evidence                                                                                                                                                                               | Remaining limit                                                                                                                                                                                  |
| ---------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Tested AOSP API 30–37 emulator profiles                                                              | Historical receiver-injection runs checked the first original automatically and the exact seven-original set after explicit source actions, using separate testing-mode OFF/ON guests. | Synthetic injection is not physical radio reception. Full-set recovery after a source action is not automatic recovery of every hidden queued body.                                              |
| API 30 rapid alert batches with an immediate HOME transition                                         | A historical scenario recovered only six of seven expected originals.                                                                                                                  | This remains a separate unresolved scenario; successful foreground source-action runs do not close it.                                                                                           |
| SM-S731N, API 37, `com.google.android.cellbroadcastreceiver`, version code `300000000` (`R-initial`) | On November 2, one owner-reported fresh event was stored through the notification route. Its original visually matched the visible system warning.                                     | Radio/test-mode state was not independently established. This single event does not establish stacked recovery, accessibility-route success, or resolution of an earlier suspected missed event. |
| Other cellular devices, receivers, and firmware                                                      | Support must be established for the installed source profile.                                                                                                                          | Android version, manufacturer, package presence, and service connections alone do not establish capture support.                                                                                 |
| Wi-Fi-only hardware                                                                                  | Saved-history and translation features remain usable.                                                                                                                                  | Cellular emergency-broadcast reception and its automatic capture path are unavailable.                                                                                                           |

Historical software evidence includes the [baseline source matrix](https://github.com/gaon12/CodeClear/actions/runs/41305818397)
and [API 37 16 KiB source validation](https://github.com/gaon12/CodeClear/actions/runs/41326764494).
Each run applies to its recorded revision and profile. Later builds require
affected checks again. Firmware updates can change source behavior.

Further capture claims need a complete expected-original set, independent
testing-mode evidence, and a clear distinction between automatic collection and
user-opened source recovery. Physical reception, reboot/locked-device behavior,
constrained-device latency and idle energy remain separate validation work.

## Translation meaning review

Successful engine execution and preserved numbers do not establish correct
emergency instructions. The [synthetic review suite](tools/translation_review_cases.json)
contains eight English/Korean cases covering negation, shelter versus evacuation,
upstream/downstream direction, locations, times, distances, issuer wording, and a
reserved example URL. Its reference translations are drafts for review, not
accepted engine outputs or evidence of seven-language translation quality.

Create a local review file outside the repository, then enter the actual outputs,
engine/revision description, a reviewer label, and a boolean judgment for each
case's semantic questions. Record unavailable model-revision information honestly;
do not invent a version. Use only these synthetic originals in this process.

```powershell
python tools/translation_review.py --template "$env:TEMP/codeclear-translation-review.json"
python tools/translation_review.py --review "$env:TEMP/codeclear-translation-review.json"
```

The tool runs offline and makes no translation or model-download requests. It
binds reviews to the exact suite SHA-256, requires every case once, and checks
numeric-token and exact-URL preservation. Each semantic question explains what
the reviewer must assess. Missing outputs, reviewer/engine metadata, or judgments
remain `pending` (exit 2); rejected judgments or structural output mismatches are
`failed` (exit 1). A complete submitted review is `passed` (exit 0). This records
the reviewer's judgments; it cannot independently verify them. Equivalent
numeric/time reformattings need a reviewed suite change rather than bypassing a
failure. The tooling unit tests validate this review process, not an engine's
translation quality. Human engine reviews remain outstanding.

## Build and check

Use Android Studio with a JDK 25 installation and Android SDK platform 37. The
checked-in Gradle wrapper and daemon criteria define the build toolchain. Configure
the SDK through Android Studio or an untracked `local.properties` file; do not
commit machine-specific SDK paths or signing keys.

From PowerShell at the repository root, with Java available to the wrapper:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug :app:lintRelease
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebugAndroidTest
```

On other platforms, use `./gradlew` with the same task arguments.

The debug version includes diagnostic capture tooling and has a `-capture-probe`
version suffix. Synthetic ordinary notifications, injected system receiver alerts,
and physical radio reception are distinct validation paths. Passing a fixture test
does not prove physical reception or recovery of every stacked alert.

Instrumented tests require a deliberately prepared emulator or authorized device.
Some test classes change app state, use Bluetooth, or contact the remote provider.
Review the selected fixtures and use isolated synthetic data before running device
tests. Building the test APK does not execute those tests.

The `Source checks` workflow runs on source/configuration pushes to `main`, pull
requests, and manual dispatch. Changes confined to Markdown, public documentation,
or store materials do not start Android checks. It checks tracked Kotlin
formatting, Python formatting/lint and tooling tests, workflow syntax, both Android
lint variants, JVM tests, and app/test APK compilation. Tool versions are pinned;
downloaded lint binaries have pinned
SHA-256 checksums. It starts no emulator, downloads no translation models, and
uses no private translation configuration or signing credentials. These checks
do not execute instrumented tests or establish alert-capture support.

The manually dispatched `Android validation` GitHub Actions workflow builds a
clean checkout on standard Linux runners and runs an explicit software-fixture
allowlist on disposable AOSP API 30, 31, and 35 emulators. Each emulator uses two
cores and 2 GiB of guest RAM. This is the default `baseline` group; `extended`
selects AOSP API 32, 33, 34, 36, and 37 plus the Google API 37 16 KiB profile.
The `all` group or an individual profile can also be selected. The 16 KiB guest
must report actual 16384-byte pages and uses a bounded 4 GiB data partition.
The default software-fixture path does not use
provider keys, download translation models, grant capture access, or test physical reception. Live-provider,
Bluetooth, installed-voice, prepared-model, and owner-granted capture tests require separate
environments. No APK or test artifact is published by the workflow.

Its separate `system_receiver` option resets only the disposable emulator's app
data, enables capture through the app disclosures and ordinary Android Settings
confirmations, and injects known drill messages into the genuine system receiver.
It compares the complete expected original set after automatic capture and an
explicit original-source action. It never acknowledges system alert warnings.
Each testing mode uses a separate fresh guest. The workflow changes mode through
the ordinary dialer and verifies the source's own state report against its installed
public resources. It checks the first original automatically before any source
action, then checks recovery of the complete seven-original set after two rapid
batches and explicit source actions. An older undismissed warning can hide later
bodies, so switching modes within one pending queue does not establish fresh
automatic capture in the second mode. These debug-emulator injections do not
establish physical radio reception or other manufacturers' firmware support.

The separate manual `Optimized Android runtime` workflow selects API 30 or the
API 37 16 KiB guest and builds an optimized,
non-debuggable APK and signs it with the disposable runner's Android debug key.
After normal capture preparation, it seeds local-only translation/view preferences
in the debug fixture, upgrades to the optimized APK, and checks an exact Chinese
script conversion offline plus restart persistence through the product UI. This
runtime fixture verifies process replacement during the upgrade and again during
the persistence reinstall. It does not validate the local-consent UX or production
signing. It uses no production key and publishes no APK or test artifact.

## Standard wording translation

The translation queue checks a bounded, versioned local catalog before calling the
selected engine. A complete, unique match uses prepared wording without requesting
a model or sending the alert to a provider. Where engine attribution is shown,
the result is labeled **Standard wording translation**. The selected engine still identifies the existing consent
and history partition; catalog attribution records how this particular result was
produced.

The initial catalog contains 44 exact source variants:

| Source                                                                   | Variants | Prepared direction                                  |
| ------------------------------------------------------------------------ | -------: | --------------------------------------------------- |
| Korean general disaster, earthquake/tsunami, and civil-defense standards |       27 | Korean to English, application-authored adaptations |
| Japan Meteorological Agency earthquake/tsunami expressions               |       16 | Japanese to English, official expression pairs      |
| FCC earthquake warning                                                   |        1 | English to Korean, official expression pair         |

Sources are the Korean [general disaster-message rules](https://www.law.go.kr/행정규칙/재난문자방송기준및운영규정),
[KMA Annex 2](https://www.law.go.kr/LSW/flDownload.do?flSeq=164566155&bylClsCd=200201),
[civil-defense Annex 9](https://www.law.go.kr/LSW/flDownload.do?flSeq=160780739&bylClsCd=200201),
[JMA's 2015 multilingual dictionary](https://www.jma.go.jp/jma/press/1510/29a/tagengo20151029_dictionary.pdf),
and [FCC DA 25-12](https://docs.fcc.gov/public/attachments/DA-25-12A1.pdf).
The catalog records the edition, source reference, and translation origin. JMA
expression pairs are reproduced with attribution; this application and its
adaptations are not government-produced or government-endorsed translations.
The JMA import covers selected 2015 expressions, not the unreviewed 2026 workbook.
FCC publication does not establish that every US sender or installed receiver uses
its wording.

Matching escapes fixed text and uses anchored, bounded typed slots, valid dates
and times, exact jurisdiction-scoped names, and complete variable preservation.
Only NFC and ASCII layout whitespace are normalized. The initial name list has
17 Korean first-level regions, four Korean agency/government names, and four US
agency names/abbreviations. Ambiguous short names such as `광주`, `제주`, and unqualified
`중구` are not guessed. This is a prepared subset, not a complete geographic or
worldwide language catalog.

Changed instructions, negation, added conditions, unknown names, ambiguous
matches, and unprepared target languages continue through the existing engine
with the **entire original body**. Missing models and remote permission continue
to follow that engine's existing rules. The catalog never enables remote fallback
or model downloads. Matching does not authenticate a sender, establish capture
support, or infer an official alert level. For example, the same Korean tsunami
body can represent different structured warning levels.

Catalog results carry a template ID and revision. On a catalog revision change,
only older catalog outputs expire; ordinary engine translations remain. Expired
outputs wait for an explicit retry, which avoids historical result notifications
or background provider work after an application update.
Synthetic tests cover action/release distinctions, malformed variables,
nonstandard fallback, scoped names, ambiguity, cache/migration preservation,
permission changes, deletion, result attribution, and long near-match inputs.
These checks do not certify translation semantics or physical alert reception.

## Source map

All handwritten sources are Kotlin and live under each source set's `kotlin/`
directory in the single `app` module. Main packages under
`app/src/main/kotlin/xyz/gaon/codeclear/` are:

| Package       | Responsibility                                                                                            |
| ------------- | --------------------------------------------------------------------------------------------------------- |
| `mobile`      | Product screens, settings, history, runtime ownership, and work coordination                              |
| `capture`     | Alert-source observation, extraction, source verification, and capture services                           |
| `probe`       | Stable Android service entry points that preserve existing user grants                                    |
| `translation` | Shared translation contracts, on-device engines, MyMemory transport, and future server response contracts |
| `nearby`      | Experimental Bluetooth relay protocol, radio work, and peer inbox                                         |
| `reception`   | Radio/network country context and country-code mappings                                                   |

`src/debug/kotlin` contains diagnostic entry points. `src/testDebug/kotlin`
contains JVM tests, and `src/androidTest/kotlin` contains instrumented tests.

Within `mobile`, `MobileController` owns product state and consent decisions.
`AlertCaptureIngestor` applies current source/access decisions and deduplicates
observations before storage. `AlertHistoryReader` composes bounded history views;
the controller owns their cancellation and stale-response checks.
`AlertTranslationQueue` selects and completes translation jobs while preserving
their selected engine, catalog attribution, and work generation. `HistoryWorker`
serializes storage operations, bounds pending work, and releases storage after active work completes.
`MobileScreen` owns navigation and scroll state; tutorial steps, home/history
feeds, and the detail dialog render through focused components. Alert formatting,
metadata, message panels, and shared settings controls have separate owners.

## Contributing

Follow [AGENTS.md](AGENTS.md). Keep changes focused: edit, format and lint, run the
affected tests, inspect the staged diff, then commit. Kotlin formatting uses
standalone ktlint 1.8.0 and the checked-in [.editorconfig](.editorconfig); Android
lint is a separate required check. Do not treat template tests as feature evidence.

Keep real alert bodies, private device captures, credentials, generated output,
and local configuration out of commits. Internal development records stay in the
ignored `.dev/` directory; the public build instructions must remain usable
without those records.
