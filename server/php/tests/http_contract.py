"""Exercise the real PHP HTTP boundary with synthetic, pre-cached results only."""

import hashlib
import http.client
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

PHP = str(Path(sys.argv[1]).resolve())
API = Path(__file__).resolve().parents[1] / "public/api.php"
TOKEN = "synthetic-http-client-aaaaaaaaaaaaaaaa"
OTHER = "synthetic-http-client-bbbbbbbbbbbbbbbb"
TEXT = "[DRILL] Gate 3, call 112."
BODY = json.dumps({"text": TEXT, "target_lang": "en"}).encode()
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(
    url,
    method="POST",
    body=BODY,
    token=TOKEN,
    content_type="application/json",
    extra=None,
):
    headers = {"Content-Type": content_type}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    headers.update(extra or {})
    if os.name == "nt":
        # Use the platform HTTP client for bounded Windows loopback fixtures.
        curl = shutil.which("curl.exe")
        assert curl is not None, "The Windows HTTP fixture requires curl.exe"
        arguments = [
            curl,
            "--noproxy",
            "*",
            "--silent",
            "--show-error",
            "--http1.0",
            "--max-time",
            "10",
            "--include",
            "--request",
            method,
        ]
        for key, value in headers.items():
            arguments.extend(["--header", key + ": " + value])
        if body is not None:
            arguments.extend(["--data-binary", "@-"])
        completed = subprocess.run(
            [*arguments, url], input=body, capture_output=True, timeout=15, check=False
        )
        assert completed.returncode == 0, completed.stderr.decode(errors="replace")
        raw_headers, raw = completed.stdout.split(b"\r\n\r\n", 1)
        status_line, raw_headers = raw_headers.split(b"\r\n", 1)
        parsed = http.client.parse_headers(io.BytesIO(raw_headers + b"\r\n\r\n"))
        assert int(parsed["Content-Length"]) == len(raw) <= 100000
        assert parsed.get_content_type() == "application/json"
        assert parsed.get("Cache-Control") == "no-store"
        assert parsed.get("X-Content-Type-Options") == "nosniff"
        return int(status_line.split()[1]), json.loads(raw), parsed
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        response = OPENER.open(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        length = int(response.headers["Content-Length"])
        assert 0 < length <= 100000
        raw = response.read(length)
        assert len(raw) == length
        assert response.headers.get_content_type() == "application/json"
        assert response.headers.get("Cache-Control") == "no-store"
        assert response.headers.get("X-Content-Type-Options") == "nosniff"
        return response.status, json.loads(raw), response.headers


class Server:
    def __init__(self, root, env):
        address = "::1" if socket.has_ipv6 else "127.0.0.1"
        family = socket.AF_INET6 if socket.has_ipv6 else socket.AF_INET
        with socket.socket(family) as reserved:
            reserved.bind((address, 0))
            port = reserved.getsockname()[1]
        authority = ("[::1]" if address == "::1" else address) + ":" + str(port)
        self.log = (root / ("http-" + str(port) + ".log")).open("wb")
        self.process = subprocess.Popen(
            [PHP, "-S", authority, "-t", str(API.parent)],
            env=env,
            stdout=self.log,
            stderr=subprocess.STDOUT,
        )
        self.url = "http://" + authority + "/api.php"
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("Synthetic PHP server stopped during startup")
            try:
                with socket.create_connection((address, port), timeout=0.2):
                    return
            except OSError:
                time.sleep(0.05)
        raise RuntimeError("Synthetic PHP server did not start")

    def close(self):
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        self.log.close()


temporary = tempfile.TemporaryDirectory(prefix="codeclear-http-tests-")
root = Path(temporary.name).resolve()
assert root.parent == Path(tempfile.gettempdir()).resolve()
assert root.name.startswith("codeclear-http-tests-")
servers = []
try:
    env = {
        key: value
        for key, value in os.environ.items()
        if not key.startswith(("CODECLEAR_", "GROQ_"))
    }
    env.update(
        {
            "GROQ_API_KEY": "synthetic-http-groq-key-aaaaaaaaaaa",
            "CODECLEAR_CACHE_SECRET": "d" * 64,
            "CODECLEAR_CLIENT_TOKENS": json.dumps(
                {
                    "phone": hashlib.sha256(TOKEN.encode()).hexdigest(),
                    "tablet": hashlib.sha256(OTHER.encode()).hexdigest(),
                }
            ),
            "CODECLEAR_DB_PATH": str(root / "cache.sqlite3"),
            "CODECLEAR_ENV_FILE": str(root / "absent.env"),
            "CODECLEAR_REQUIRE_HTTPS": "false",
        }
    )
    bootstrap = root / "prime.php"
    bootstrap.write_text(
        """<?php
declare(strict_types=1);
define('CODECLEAR_LIBRARY', true);
require $argv[1];
$config = CodeClear\\Configuration::load();
$store = new CodeClear\\CacheStore($config);
$service = new CodeClear\\TranslationService($config, $store,
    static function (array $payload): array {
        if (($GLOBALS['argv'][2] ?? '') === 'race') {
            $root = dirname(getenv('CODECLEAR_DB_PATH'));
            $counter = fopen($root.'/race.count', 'c+');
            flock($counter, LOCK_EX);
            $count = (int) stream_get_contents($counter);
            rewind($counter);
            fwrite($counter, (string) ($count + 1));
            fflush($counter);
            flock($counter, LOCK_UN);
            fclose($counter);
            file_put_contents($root.'/race.ready', 'ready');
            $deadline = microtime(true) + 8;
            while (!is_file($root.'/race.release')) {
                if (microtime(true) >= $deadline) throw new RuntimeException('Fixture barrier timed out');
                usleep(10000);
            }
        }
        return ['model' => $payload['model'], 'choices' => [['finish_reason' => 'stop',
            'message' => ['role' => 'assistant', 'content' => CodeClear\\encode([
                'translated_text' => $payload['messages'][1]['content'],
                'issuer_original' => '', 'issuer_translated' => '', 'issuer_evidence' => '',
            ])]]]];
    }, static fn () => $config);
$body = stream_get_contents(STDIN);
$tokens = ($argv[2] ?? '') === 'race' ? ['synthetic-http-client-aaaaaaaaaaaaaaaa'] :
    ['synthetic-http-client-aaaaaaaaaaaaaaaa', 'synthetic-http-client-bbbbbbbbbbbbbbbb'];
try {
    foreach ($tokens as $token) {
        $service->handle('POST', 'Bearer '.$token, 'application/json', $body);
    }
    echo '200';
} catch (CodeClear\\ApiFailure $error) {
    if (($argv[2] ?? '') !== 'race') throw $error;
    echo $error->status;
}
""",
        encoding="utf-8",
    )
    subprocess.run(
        [PHP, str(bootstrap), str(API)],
        input=BODY,
        env=env,
        check=True,
        capture_output=True,
        timeout=15,
    )
    server = Server(root, env)
    servers.append(server)
    status, response, _ = request(server.url)
    assert status == 200
    assert response == {
        "translated_text": TEXT,
        "issuer": {"original": "", "translated": ""},
        "target_lang": "en",
        "source_sha256": hashlib.sha256(TEXT.encode()).hexdigest(),
    }
    cases = [
        ({"token": None, "body": b""}, 401),
        ({"token": "synthetic-unrecognized-client-aaaaaaaa", "body": b""}, 401),
        ({"method": "GET", "body": None}, 405),
        ({"content_type": "text/plain"}, 415),
        ({"body": b"[]"}, 400),
        ({"body": b'{"text":"[DRILL]","text":"[DRILL]"}'}, 400),
        ({"body": b'{"text":"[DRILL]","target_lang":"unknown"}'}, 400),
        ({"body": b"x" * 100001}, 413),
        ({"method": "DELETE", "body": b"{}"}, 400),
    ]
    for arguments, expected in cases:
        status, result, _ = request(server.url, **arguments)
        assert status == expected, (status, expected)
        assert set(result) == {"error"}
    status, result, _ = request(server.url, method="DELETE", body=b"")
    assert status == 200 and result == {"cleared": True}
    assert request(server.url, token=OTHER)[0] == 200
    strict = Server(root, dict(env, CODECLEAR_REQUIRE_HTTPS="true"))
    servers.append(strict)
    status, result, _ = request(
        strict.url, token=OTHER, body=b"", extra={"X-Forwarded-Proto": "https"}
    )
    assert status == 400 and result == {"error": "https_required"}
    invalid = Server(root, dict(env, GROQ_API_KEY=""))
    servers.append(invalid)
    status, result, _ = request(invalid.url, body=b"")
    assert status == 503 and result == {"error": "service_unavailable"}
    unauth_path = root / "unauth.sqlite3"
    unauthenticated = Server(root, dict(env, CODECLEAR_DB_PATH=str(unauth_path)))
    servers.append(unauthenticated)
    assert request(unauthenticated.url, token=None, body=b"")[0] == 401
    assert not unauth_path.exists()

    race_env = dict(env, CODECLEAR_DB_PATH=str(root / "race.sqlite3"))
    arguments = [PHP, str(bootstrap), str(API), "race"]
    first = subprocess.Popen(
        arguments,
        env=race_env,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        first.stdin.write(BODY)
        first.stdin.close()
        first.stdin = None
        deadline = time.monotonic() + 5
        while not (root / "race.ready").exists():
            assert first.poll() is None and time.monotonic() < deadline
            time.sleep(0.01)
        second = subprocess.run(
            arguments,
            input=BODY,
            env=race_env,
            capture_output=True,
            check=True,
            timeout=15,
        )
        assert second.stdout == b"409"
        (root / "race.release").write_text("release", encoding="ascii")
        stdout, stderr = first.communicate(timeout=15)
        assert first.returncode == 0 and stdout == b"200", stderr.decode()
        third = subprocess.run(
            arguments,
            input=BODY,
            env=race_env,
            capture_output=True,
            check=True,
            timeout=15,
        )
        assert third.stdout == b"200" and (root / "race.count").read_text() == "1"
    finally:
        if first.poll() is None:
            first.kill()
            first.wait(timeout=5)
    print(
        "15 HTTP boundary checks and a separate-process lease race passed; no provider requests were made."
    )
except Exception:
    for server in servers:
        server.log.flush()
        print(
            Path(server.log.name).read_text(encoding="utf-8", errors="replace")[-4000:],
            file=sys.stderr,
        )
    raise
finally:
    for server in servers:
        server.close()
    temporary.cleanup()
