# CodeClear PHP translation endpoint

This standalone endpoint translates through the fixed Groq HTTPS API. Provider
keys stay on the server. SQLite reuses validated results for identical text,
target, model, prompt version and authenticated client. No live alerts or paid
provider calls are needed for the supplied tests.

## Deployment

Use a current security-patched PHP 8.3, 8.4 or 8.5 release with `curl`,
`pdo_sqlite` and `mbstring`. Minimum security patch levels checked by the
endpoint are 8.3.36, 8.4.27 and 8.5.12. Use the latest platform build as well;
Windows builds were additionally rebuilt for a Brotli fix. The locally
verified development runtime is the official PHP 8.3.36-1 Windows build.
See [PHP downloads](https://www.php.net/downloads.php).

Use this directory arrangement:

```text
/srv/codeclear/
  .env                 # private, mode 0600
  data/                # private, mode 0700; writable by PHP
  public/
    api.php            # the only public deployment file
```

1. Copy `public/api.php` to the public directory. It has no Composer runtime
   dependencies. Set the web server document root to that directory.
2. Copy `.env.example` to the parent directory as `.env`, then fill in the
   Groq key, absolute private DB path and a new random cache secret. Process
   environment variables override file values. `CODECLEAR_ENV_FILE` can point
   to a different private file. Do not place the DB, `.env`, backups or logs
   in the document root. The endpoint rejects configured public private-file
   paths, but the web server must also prevent static secret-file exposure.
3. Run `php public/api.php generate-token` on your server for each authorized
   client. Give that client the generated token and put only its SHA-256
   digest in `CODECLEAR_CLIENT_TOKENS`, using a distinct label. Tokens must
   never be provider API keys. The example contains invalid placeholders.
   Public mobile distribution needs a deliberate secure token-provisioning
   and abuse-control flow; do not embed an administrator token in an APK.
4. Run `php public/api.php config-check` with the same PHP user/environment
   as the web process. This checks configuration/storage without calling Groq.
5. Serve HTTPS, forward the `Authorization` header to PHP, and set the PHP
   `HTTPS` server parameter correctly if TLS terminates at a trusted proxy.
   The endpoint does not trust a caller's `X-Forwarded-Proto` header. Do not
   disable HTTPS on a public deployment. The false setting is for isolated
   loopback tests only.
6. Limit request bodies to 100 KB at the web server too. Apply edge rate and
   connection limits. Run only one SQLite deployment on local storage; shared
   network filesystems and multiple independent replicas are unsupported.

Generate a cache secret without printing a provider credential:

```sh
php -r 'echo bin2hex(random_bytes(32)), PHP_EOL;'
```

The default model is `openai/gpt-oss-120b`. The reviewed explicit alternatives
are `openai/gpt-oss-20b` and experimental `qwen/qwen3.8-27b`. Set one model in
`.env`; there is no automatic model or key fallback on quota errors. Defaults
bound provider calls to 10/minute, 500/day and two concurrent requests. These
are application limits, not a guarantee of your account's provider allowance.
The fixed provider, selected model and
[strict structured-output support](https://console.groq.com/docs/structured-outputs)
were reviewed on 2026-11-02.

## HTTP contract

Send `POST /api.php` with `Authorization: Bearer <client-token>` and
`Content-Type: application/json`:

```json
{"text":"[DRILL] Gate 3, call 112.","target_lang":"en"}
```

Targets are `en`, `ko`, `ja`, `fr`, `de`, `zh-Hans` and `zh-Hant`. The previous
English language names are accepted as aliases. Omitting the target selects
Korean for compatibility. Text is limited to 15,000 UTF-8 bytes. Source text
is treated as data; it cannot select an endpoint, model, owner or prompt.

Successful responses contain only:

```json
{
  "translated_text": "[DRILL] Gate 3, call 112.",
  "issuer": {"original": "", "translated": ""},
  "target_lang": "en",
  "source_sha256": "<SHA-256 of the exact original UTF-8 text>"
}
```

The client must match the digest and target to its pending original before
attaching the result. Missing or uncertain issuers remain empty strings.
Nonempty issuers require a verbatim source excerpt and a literal issuer
substring. They remain **text-derived, unverified claims**; these checks do
not authenticate an organization or prove translation accuracy. Strict field,
type, byte-length, control-character, numeric-token and URL checks reject
malformed/truncated output. Semantic mistranslation can still pass structural
validation, so retain the original and official guidance in the client.

Model names, reasoning, provider diagnostics, token usage and inference time
are not returned to clients. Errors contain only an `error` code. Important
statuses are 401 (authorization), 409 (identical work in progress or canceled
work), 429 (request/provider budget), 502 (provider/validation failure), and
503 (temporary storage/capacity/configuration failure). Honor bounded
`Retry-After` headers; do not retry indefinitely or silently switch providers.

Authenticated `DELETE /api.php` with an empty body clears only that client's
private cached content, including pending leases. A late worker cannot
recreate cleared content. It cannot recall source text already sent to Groq.
Request budgets are retained so clearing cannot bypass abuse limits. Revoking
a token prevents new requests and abandons an in-flight result when its
configuration is rechecked. Rotate secrets through an atomic private-file
replacement or a controlled process restart.

## SQLite data and maintenance

The `cache` table contains the exact original, target, prompt version,
requested/actual model, validated result JSON (translation, original and
translated issuer, source evidence), measured `inference_ms`, bounded token
usage, creation/expiry, and pending/failure state. Timing is measured by the
server around inference, not self-reported by the model. A cache hit does not
change that inference measurement or extend expiry. Failed output is not
stored as a translation. `budgets` contains body-free admission counters.
The bounded `scopes` table records body-free deletion generations so work
admitted before a clear cannot acquire a new lease afterward.

All content is private to the authenticated client. The server does not
infer public provenance from client assertions or share unverified messages
between clients. Cache keys are server-keyed HMACs. Rotating provider keys,
models, client tokens or the cache secret changes the appropriate scope or
cache epoch. No cache-enumeration endpoint exists.

Default retention is 30 days from creation, without hit extension; it may be
shortened with `CODECLEAR_CACHE_DAYS`. Capacity defaults to 2,000 entries and
is bounded. Full capacity rejects new work instead of dropping unexpired
results and causing repeated provider calls. Schedule this command at least
hourly to remove expired content and truncate the SQLite WAL:

```sh
php /srv/codeclear/public/api.php prune
```

Backups, web/proxy request logging and provider retention are separate from
this TTL. Exclude request/response bodies and authorization headers from
access logs/APM; protect or exclude this cache in backups and apply their own
expiry. Configure the selected provider's account data controls. A provider
may already have processed a source when the client clears its cache.

## Validation

Run with a patched PHP executable and PDO SQLite enabled:

```sh
php -l server/php/public/api.php
php -l server/php/tests/run.php
php server/php/tests/run.php
python server/php/tests/http_contract.py /absolute/path/to/php
```

Format/check PHP with the reviewed PHP-CS-Fixer 3.95.27 release and `@PSR12`:

```sh
php php-cs-fixer.phar fix server/php --rules=@PSR12 --using-cache=no
php php-cs-fixer.phar fix server/php --rules=@PSR12 --using-cache=no --dry-run
python -m ruff check server/php/tests/http_contract.py
```

The PHP suite uses temporary synthetic databases and an injected fake
provider. The HTTP suite serves only on loopback, primes synthetic results
through that fake provider, then checks the real HTTP/auth/cache boundary.
It never sends a cache miss to the real provider. Real hosting, HTTPS proxy
configuration, account permissions, quotas and live model quality still need
verification after deployment and before release.

The HTTP harness uses loopback IPv6 when available and the platform `curl.exe`
on Windows. It also starts two independent PHP processes against one SQLite
database to verify that an identical concurrent miss consumes one provider
attempt and that a later request reuses the completed result.
