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
Keep configured APKs and generated build output out of public artifacts too.

Normal CI builds do not load the private file and do not receive the real URL.
Use `--config /absolute/private/path/server-build.env` for another private file.
