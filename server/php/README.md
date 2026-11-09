# CodeClear PHP translation endpoint

This standalone endpoint translates through the fixed Groq HTTPS API. Provider
keys stay on the server. SQLite reuses validated results for identical text,
target, model, prompt version and private client/installation scope. No live alerts or paid
provider calls are needed for the supplied tests.

## Deployment

Use PHP 8.3 or newer with `curl`, `pdo_sqlite` and `mbstring`. Install the
current security updates from the runtime vendor or Linux distribution.
Runtime compatibility checks do not determine security patch status from
`PHP_VERSION_ID`: distributions backport fixes while retaining an older
upstream number. For example, Ubuntu 24.04 maintains its PHP 8.3.6 packages
through its security repository. See [Ubuntu security updates](https://documentation.ubuntu.com/security/security-updates/)
and the [PHP package](https://packages.ubuntu.com/noble/php8.3).

Put `api.php` and `.env` in the same directory:

```text
codeclear/
  api.php
  .env                 # private, mode 0600
  .htaccess            # Apache deny rules; do not omit
  data/
    .htaccess          # denies every private storage file
    cache.sqlite3      # created automatically, mode 0600
```

1. Upload `api.php`, `.htaccess` and `data/.htaccess`. There are no Composer
   runtime dependencies. Only `api.php` should be accessible over HTTP.
   Apache 2.4 must allow the supplied `.htaccess` directives. For Nginx,
   adapt `nginx-location.example.conf` instead; Nginx ignores `.htaccess`.
   Keep the supplied files present for the adjacent-layout checks too.
   `CGIPassAuth On` forwards the Bearer header to CGI/PHP-FPM; see the
   [Apache directive](https://httpd.apache.org/docs/2.4/mod/core.html#cgipassauth).
2. Copy `.env.example` to `.env` beside `api.php`. Fill in the Groq key and
   a new random cache secret. The adjacent protected `data/cache.sqlite3`
   is the default; an optional `CODECLEAR_DB_PATH` must be absolute. A DB
   outside the document root remains supported. Process environment variables
   override file values. `CODECLEAR_ENV_FILE` can select a different private
   external file. Never commit populated configuration, databases or backups.
3. For mobile access without manual token entry, set `CODECLEAR_ALLOW_ANONYMOUS=true`
   and `CODECLEAR_CLIENT_TOKENS='{}'`, as in the example. The app generates a
   random installation identifier; it supplies `X-CodeClear-Installation` instead
   of an Authorization header. Existing deployments default to anonymous access
   disabled until explicitly configured. Optional legacy clients can still use
   `php api.php generate-token` and register its SHA-256 digest in
   `CODECLEAR_CLIENT_TOKENS`. Never embed a provider or administrator key in an APK.
4. Run `php api.php config-check` as the same user/environment as the web
   process. This checks runtime/configuration/storage without calling Groq.
   CLI failures identify the configuration category without printing secrets;
   HTTP failures remain generic.
5. Verify that HTTP requests for `.env`, `data/`, database files and sidecars
   return 403/404 before real use. Merely shipping deny files cannot prove
   the web server honors them. If a private path is accessible, fix the
   server configuration before storing credentials or alert bodies.
6. Serve HTTPS, forward `Authorization` to PHP and set the server `HTTPS`
   parameter correctly if TLS terminates at a trusted proxy. A caller's
   `X-Forwarded-Proto` is not trusted. Disabling HTTPS is for isolated loopback
   tests only. Limit request bodies to 100 KB at the web server too, and
   apply edge connection/rate limits. Use local SQLite storage on one server;
   shared network filesystems and independent replicas are unsupported.

Generate a cache secret without printing a provider credential:

```sh
php -r 'echo bin2hex(random_bytes(32)), PHP_EOL;'
```

The default model is `openai/gpt-oss-120b`. Reviewed explicit alternatives are
`openai/gpt-oss-20b` and experimental `qwen/qwen3.8-27b`. There is no automatic
model or key fallback. Defaults bound provider calls to 10/minute, 500/day
and two concurrent requests. These are application limits, not a guarantee
of your account's allowance. The fixed provider, selected model and
[strict structured-output support](https://console.groq.com/docs/structured-outputs)
were reviewed on 2026-11-02.

## HTTP contract

Send `POST /api.php` with `Authorization: Bearer <client-token>` and
`Content-Type: application/json`:

```json
{ "text": "[DRILL] Gate 3, call 112.", "target_lang": "en" }
```

Targets are `en`, `ko`, `ja`, `fr`, `de`, `zh-Hans` and `zh-Hant`. The previous
English language names are accepted as aliases. Omitting the target selects
Korean for compatibility. Text is limited to 15,000 UTF-8 bytes. Source text
is treated as data; it cannot select an endpoint, model, owner or prompt.

Successful responses contain only:

```json
{
    "translated_text": "[DRILL] Gate 3, call 112.",
    "issuer": { "original": "", "translated": "" },
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

Scoped `DELETE /api.php` with an empty body clears only that client's or installation's
private cached content, including pending leases. A late worker cannot
recreate cleared content. It cannot recall source text already sent to Groq.
Request budgets are retained so clearing cannot bypass abuse limits. Revoking
a token prevents new requests and abandons an in-flight result when its
configuration is rechecked. Rotate secrets through an atomic private-file
replacement or a controlled process restart.

## SQLite data and maintenance

### Access without manual client tokens

Anonymous installations supply exactly 64 lowercase hexadecimal characters,
generated from 32 cryptographically random bytes. This identifier is a private
cache/deletion capability, not proof of an official client or genuine alert.
Protect it like other private app state and exclude the header from logs. A
caller cannot claim verified public provenance or upload translated cache entries.
Supplying both access mechanisms is rejected; invalid Bearer credentials never
fall back to anonymous access. POST and bodyless DELETE use the same scope.

The server HMACs the installation identifier into a separate private scope.
Identical requests reuse a result within that scope; another installation cannot
read or clear it. Missing or expired results alone can consume provider budget.
Concurrent misses retain the existing unique lease, so they cannot start duplicate
provider work. There is no cross-installation shared alert cache or enumeration API.

Anonymous requests additionally consume `CODECLEAR_NETWORK_RPM` (default 30) and
service-wide `CODECLEAR_ANONYMOUS_RPM` (default 120), including cache hits and DELETE.
Rotating identifiers cannot bypass these limits or the provider/day/concurrency
limits. Network counters use a keyed hash of the web server's actual peer address,
never a caller-supplied forwarding header; raw addresses are not stored in SQLite.
Behind a proxy, requests share the proxy's budget unless its trusted server
configuration supplies the actual peer address. Carrier/NAT users can also share
a budget. Configure edge limits before exposing the service; these bounds limit
work and cost but do not authenticate senders or eliminate denial of service.

Empty scopes become eligible for bounded cleanup after five inactive minutes,
so identifier rotation cannot retain empty admission records for the content TTL.
Recreated scopes receive unpredictable generations; an old admitted request
cannot acquire a lease after its scope was removed and recreated. Scopes holding
cached data retain the existing content/deletion rules.

The `cache` table contains the exact original, target, prompt version,
requested/actual model, validated result JSON (translation, original and
translated issuer, source evidence), measured `inference_ms`, bounded token
usage, creation/expiry, and pending/failure state. Timing is measured by the
server around inference, not self-reported by the model. A cache hit does not
change that inference measurement or extend expiry. Failed output is not
stored as a translation. `budgets` contains body-free admission counters.
The bounded `scopes` table records body-free deletion generations so work
admitted before a clear cannot acquire a new lease afterward.

All content is private to its authenticated client or anonymous installation. The server does not
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
php /srv/codeclear/api.php prune
```

Backups, web/proxy request logging and provider retention are separate from
this TTL. Exclude request/response bodies and authorization headers from
access logs/APM; protect or exclude this cache in backups and apply their own
expiry. Configure the selected provider's account data controls. A provider
may already have processed a source when the client clears its cache.

## Validation

Run with a patched PHP executable and PDO SQLite enabled:

```sh
php -l server/php/api.php
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

The HTTP harness covers both Bearer and installation-header access, opt-in,
cache reuse, cross-installation deletion isolation, and forwarding-header abuse.
The HTTP harness uses loopback IPv6 when available and the platform `curl.exe`
on Windows. It also starts two independent PHP processes against one SQLite
database to verify that an identical concurrent miss consumes one provider
attempt and that a later request reuses the completed result.
