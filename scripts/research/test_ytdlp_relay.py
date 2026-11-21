"""Issue #24 loopback-only research fixture. Run directly with the pinned tools."""

import hashlib
import json
import re
import subprocess
import sys
import unittest
from contextlib import contextmanager
from functools import partial
from http.client import HTTPConnection, IncompleteRead
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import metadata, resources
from threading import Event, Thread

BLOCK = 8192
OWNER = "Bearer fixture-owner"


def run(*args, data=None):
    return subprocess.run(args, input=data, capture_output=True, check=True, timeout=15)


def make_audio(frequency):
    return run(
        "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
        f"sine=frequency={frequency}:sample_rate=48000:duration=8",
        "-c:a", "aac", "-b:a", "96k", "-fflags", "+bitexact", "-flags:a", "+bitexact",
        "-movflags", "+empty_moov+default_base_moof+frag_keyframe",
        "-frag_duration", "500000", "-f", "mp4", "pipe:1",
    ).stdout


def first_frame(data):
    return run(
        "ffmpeg", "-v", "error", "-i", "pipe:0", "-frames:a", "1",
        "-f", "s16le", "pipe:1", data=data,
    ).stdout


@contextmanager
def server(handler, fixture):
    with ThreadingHTTPServer(("127.0.0.1", 0), partial(handler, fixture)) as http:
        thread = Thread(target=http.serve_forever, kwargs={"poll_interval": 0.05})
        thread.start()
        try:
            yield http.server_port
        finally:
            http.shutdown()
            thread.join()


@contextmanager
def get(port, path, headers):
    connection = HTTPConnection("127.0.0.1", port, timeout=20)
    try:
        connection.request("GET", path, headers=headers)
        yield connection.getresponse()
    finally:
        connection.close()


class Handler(BaseHTTPRequestHandler):
    def __init__(self, fixture, *args):
        self.fixture = fixture
        super().__init__(*args)

    def log_message(self, *_):
        pass

    def headers_for(self, status, length, **headers):
        self.send_response(status)
        self.send_header("Content-Length", str(length))
        for name, value in headers.items():
            self.send_header(name.replace("_", "-"), value)
        self.end_headers()


class Origin(Handler):
    def do_GET(self):
        f = self.fixture
        f.requests.append((self.path, self.headers.get("Range")))
        if self.path == "/manifest.mpd":
            media = f"/audio-{f.generation}.m4a"
            audio = f"""<AdaptationSet mimeType="audio/mp4" codecs="mp4a.40.2">
                  <Representation id="audio" bandwidth="96000"><BaseURL>{media}</BaseURL></Representation>
                </AdaptationSet>""" if f.audio_available else ""
            body = f"""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static"
                mediaPresentationDuration="PT8S"><Period>{audio}
                <AdaptationSet mimeType="video/mp4" codecs="avc1.64001f">
                  <Representation id="video" bandwidth="1000000"><BaseURL>/video.mp4</BaseURL></Representation>
                </AdaptationSet></Period></MPD>""".encode()
            self.headers_for(200, len(body), Content_Type="application/dash+xml")
            self.wfile.write(body)
            return
        if self.path != f"/audio-{f.generation}.m4a":
            self.headers_for(410, 0)
            return

        audio = f.replacement if f.changed else f.audio
        etag = '"' + hashlib.sha256(audio).hexdigest() + '"'
        start, end = 0, len(audio) - 1
        status, headers = 200, {}
        if self.headers.get("Range"):
            start, end = map(int, re.fullmatch(r"bytes=(\d+)-(\d+)", self.headers["Range"]).groups())
            status = 206
            sent_start = start + 1 if f.bad_range else start
            headers["Content_Range"] = f"bytes {sent_start}-{end}/{len(audio)}"
        self.headers_for(status, end - start + 1, Content_Type="audio/mp4", ETag=etag, **headers)
        body = memoryview(audio)[start:end + 1]
        try:
            self.wfile.write(body[:BLOCK])
            if f.interrupt:
                return
            if f.hold.is_set() and not f.release.wait(12):
                return
            self.wfile.write(body[BLOCK:])
            f.finished.set()
        except (BrokenPipeError, ConnectionResetError):
            pass


class Relay(Handler):
    def do_GET(self):
        f = self.fixture
        if self.headers.get("Authorization") != OWNER:
            self.headers_for(404, 0)
            return
        if self.path == "/audio?save=1":
            self.headers_for(403, 0)
            return
        if self.path != "/audio":
            self.headers_for(404, 0)
            return

        requested_range = self.headers.get("Range")
        start, end = 0, len(f.audio) - 1
        headers = {}
        if requested_range is not None:
            match = re.fullmatch(r"bytes=(\d{1,9})-(\d{1,9})", requested_range)
            if match:
                start, end = map(int, match.groups())
            if not match or not 0 <= start <= end < len(f.audio):
                self.headers_for(416, 0, Content_Range=f"bytes */{len(f.audio)}")
                return
            headers = {"Range": requested_range}

        for attempt in range(2):
            with get(f.origin_port, f.media, headers) as upstream:
                if upstream.status == 410 and attempt == 0:
                    try:
                        f.media = f.resolve()
                    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                        break
                    continue
                if upstream.status not in (200, 206):
                    break
                if upstream.getheader("ETag") != f.etag:
                    self.headers_for(409, 0)
                    return
                expected_status = 206 if requested_range else 200
                content_range = f"bytes {start}-{end}/{len(f.audio)}" if requested_range else None
                length = end - start + 1
                if (upstream.status != expected_status
                        or upstream.getheader("Content-Range") != content_range
                        or upstream.getheader("Content-Length") != str(length)
                        or upstream.getheader("Content-Type") != "audio/mp4"):
                    break
                response_headers = {"Content_Range": content_range} if content_range else {}
                self.headers_for(expected_status, length, Content_Type="audio/mp4", **response_headers)
                try:
                    while chunk := upstream.read(BLOCK):
                        self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError, TimeoutError):
                    pass
                return
        self.headers_for(502, 0)


class RelayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.audio = make_audio(997)
        cls.replacement = make_audio(1499)
        cls.etag = '"' + hashlib.sha256(cls.audio).hexdigest() + '"'
        probe = run(
            "ffprobe", "-v", "error", "-show_streams", "-show_packets", "-of", "json",
            "-i", "pipe:0", data=cls.audio,
        )
        info = json.loads(probe.stdout)
        assert [(s["codec_type"], s["codec_name"]) for s in info["streams"]] == [("audio", "aac")]
        cls.packet = next(p for p in info["packets"] if float(p["pts_time"]) >= 3)
        print(f"fixture: AAC/fMP4 {len(cls.audio)} bytes, sha256={cls.etag[1:-1]}", flush=True)
        print(f"replacement: {len(cls.replacement)} bytes, "
              f"sha256={hashlib.sha256(cls.replacement).hexdigest()}", flush=True)

    def setUp(self):
        self.generation = 1
        self.audio_available = True
        self.changed = self.interrupt = self.bad_range = False
        self.requests = []
        self.hold, self.release, self.finished = Event(), Event(), Event()
        self.origin_port = self.enterContext(server(Origin, self))
        self.relay_port = self.enterContext(server(Relay, self))
        self.media = self.resolve()

    def resolve(self):
        base = f"http://127.0.0.1:{self.origin_port}"
        result = run(
            sys.executable, "-m", "yt_dlp", "--ignore-config", "--no-plugin-dirs",
            "--proxy", "", "--no-remote-components", "--no-js-runtimes", "--js-runtimes", "node",
            "--skip-download", "-f", "bestaudio", "--dump-single-json", "--verbose",
            f"{base}/manifest.mpd",
        )
        self.assertIn(b"JS runtimes: node-24.21.0", result.stderr)
        self.assertIn(b"yt_dlp_ejs-0.8.0", result.stderr)
        info = json.loads(result.stdout)
        self.assertTrue(any(f["vcodec"].startswith("avc1") for f in info["formats"]))
        self.assertEqual((info["protocol"], info["acodec"], info["vcodec"]),
                         ("http", "mp4a.40.2", "none"))
        self.assertEqual(info["url"], f"{base}/audio-{self.generation}.m4a")
        return info["url"].removeprefix(base)

    def request(self, path="/audio", **headers):
        return get(self.relay_port, path, {"Authorization": OWNER, **headers})

    def test_start_and_finish(self):
        self.hold.set()
        with self.request() as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.getheader("Content-Type"), "audio/mp4")
            self.assertIsNone(response.getheader("Location"))
            prefix = response.read(BLOCK)
            try:
                decoded = first_frame(prefix)
                self.assertEqual(len(decoded), 2048)
                self.assertEqual(decoded, first_frame(self.audio))
                self.assertFalse(self.finished.is_set())
            finally:
                self.release.set()
            self.assertEqual(prefix + response.read(), self.audio)
        self.assertEqual(self.requests, [("/manifest.mpd", None), ("/audio-1.m4a", None)])
        print(f"prefix={len(prefix)} bytes / first PCM frame={len(decoded)} bytes", flush=True)

    def test_range(self):
        start = int(self.packet["pos"])
        end = start + int(self.packet["size"]) - 1
        header = f"bytes={start}-{end}"
        with self.request(Range=header) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(response.getheader("Content-Range"), f"{header.replace('=', ' ')}/{len(self.audio)}")
            self.assertEqual(response.read(), self.audio[start:end + 1])
        self.assertEqual(self.requests[-1], ("/audio-1.m4a", header))
        print(f"packet PTS={self.packet['pts_time']}s / {header}", flush=True)
        with self.request(Range=f"bytes={len(self.audio)}-{len(self.audio)}") as response:
            self.assertEqual(response.status, 416)
        self.bad_range = True
        with self.request(Range=header) as response:
            self.assertEqual((response.status, response.read()), (502, b""))

    def test_expiry(self):
        self.generation = 2
        with self.request(Range="bytes=8192-12287") as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(response.read(), self.audio[8192:12288])
        self.assertEqual(self.requests, [
            ("/manifest.mpd", None), ("/audio-1.m4a", "bytes=8192-12287"),
            ("/manifest.mpd", None), ("/audio-2.m4a", "bytes=8192-12287"),
        ])

    def test_expiry_rejects_replacement(self):
        self.generation = 2
        self.changed = True
        with self.request(Range="bytes=8192-12287") as response:
            self.assertEqual((response.status, response.read()), (409, b""))
        self.assertEqual(self.requests[-1], ("/audio-2.m4a", "bytes=8192-12287"))

    def test_expiry_without_audio_does_not_fall_back(self):
        self.generation = 2
        self.audio_available = False
        with self.request() as response:
            self.assertEqual((response.status, response.read()), (502, b""))
        self.assertEqual(self.requests, [
            ("/manifest.mpd", None), ("/audio-1.m4a", None), ("/manifest.mpd", None),
        ])

    def test_disconnect_and_resume(self):
        self.interrupt = True
        with self.request() as response:
            self.assertEqual(response.status, 200)
            with self.assertRaises(IncompleteRead) as failure:
                response.read()
        partial_body = failure.exception.partial
        self.assertEqual(len(partial_body), BLOCK)
        self.interrupt = False
        with self.request(Range=f"bytes={len(partial_body)}-{len(self.audio) - 1}") as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(partial_body + response.read(), self.audio)

    def test_owner_and_save_grant(self):
        with get(self.relay_port, "/audio", {}) as response:
            self.assertEqual(response.status, 404)
        with self.request(Authorization="Bearer fixture-other") as response:
            self.assertEqual(response.status, 404)
        with self.request("/audio?save=1") as response:
            self.assertEqual(response.status, 403)
        self.assertEqual(self.requests, [("/manifest.mpd", None)])


class RuntimeTest(unittest.TestCase):
    def test_pinned_tools(self):
        self.assertEqual(sys.version_info[:3], (3, 12, 14))
        self.assertEqual(metadata.version("yt-dlp"), "2026.10.26")
        self.assertEqual(metadata.version("yt-dlp-ejs"), "0.8.0")
        self.assertEqual(run("node", "--version").stdout.strip(), b"v24.21.0")
        solver = resources.files("yt_dlp_ejs.yt.solver")
        for name in ("lib.min.js", "core.min.js"):
            run("node", "--check", data=solver.joinpath(name).read_bytes())
        print("Python 3.12.14 / yt-dlp 2026.10.26 / Node 24.21.0 / yt-dlp-ejs 0.8.0", flush=True)


if __name__ == "__main__":
    unittest.main()
