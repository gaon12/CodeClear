# Private translation routing

Keep the actual service URL and client token outside every checkout. The
standard build has an empty `BuildConfig.OWNED_TRANSLATION_URL`. A configured
build injects only the fixed HTTPS route through `CODECLEAR_TRANSLATION_URL`;
no actual endpoint is committed to source, examples, tests or CI inputs.

Create `~/.codeclear/server-build.env` outside the repository:

```dotenv
CODECLEAR_TRANSLATION_URL=https://example.invalid/api.php
CODECLEAR_TRANSLATION_TOKEN=YOUR_PRIVATE_CLIENT_TOKEN
```

The reserved example domain is a placeholder. Run:

```sh
python tools/build_private.py
python tools/build_private.py --validation
```

The wrapper validates/redacts configuration failures and passes only the URL
to Gradle. The token is excluded even when inherited from the parent process;
it is reserved for separate local API verification and device provisioning.
Never embed a provider key or administrator token in an APK. Build injection
keeps the route out of Git; the resulting APK still contains its network route.
Keep configured APKs and generated build output out of the repository and public
CI artifacts. Authorized store uploads contain the route as part of the app;
build injection does not make that route a secret from installed-app inspection.

Normal CI builds do not load the private file and do not receive the real URL.
Use `--config /absolute/private/path/server-build.env` for another private file.

## Release signing

Build an optimized release bundle with the same private environment, then sign
an output copy with a dedicated upload key before uploading to Play Console.
Keep the keystore and its randomly generated password outside every checkout
and synchronized workspace, with access restricted to the owner. Back them up
using an owner-controlled secure location. Do not commit the key, password,
private build file or configured bundle.

Use `keytool` to create an RSA upload key with at least 25 years of certificate
validity. Pass its password through the tool's environment-variable option,
not a literal command argument, shell history or Gradle source. Export only
the public certificate when registration requires it. Sign the AAB with
`jarsigner`, using SHA-256 and an explicit output path; verify its signature
against the same keystore and confirm that signing did not alter the original
bundle payload. AAB signing and APK signing use different tools.

Play App Signing manages the certificate used for installed Play builds. The
upload key authenticates bundle uploads; it is separate from the installed-app
signing identity. Preserve existing debug-installation data when moving to a
Play-signed build. Never uninstall the owner's app merely to resolve a signature
mismatch without an approved data migration.

- [Android app-signing guidance](https://developer.android.com/studio/publish/app-signing)
- [Java keytool reference](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html)
- [Java jarsigner reference](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jarsigner.html)
