"""Offline reference checks for synthetic fixtures. No product code or network."""
import hashlib
import json
from pathlib import Path
import unittest
from urllib.parse import urlencode
from xml.etree import ElementTree


def signature(params, secret):
    fields = sorted(set(params) - {"api_sig", "format", "callback"})
    before_hash = "".join(name + params[name] for name in fields) + secret
    return hashlib.md5(before_hash.encode("utf-8"), usedforsecurity=False).hexdigest()


def response_root(body):
    try:
        root = ElementTree.fromstring(body)
    except ElementTree.ParseError:
        return None
    return root if root.tag == "lfm" else None


def can_exchange(same_owner, pending, has_token, already_claimed):
    return same_owner and pending and has_token and not already_claimed


def session_result(body):
    root = response_root(body)
    if root is not None:
        if root.get("status") == "ok" and root.findtext("session/name") and root.findtext("session/key"):
            return "SESSION"
        if root.get("status") == "failed":
            return {
                "4": "INVALID_TOKEN",
                "14": "NOT_AUTHORIZED",
                "15": "EXPIRED_TOKEN",
            }.get(error_code(root), "UNCONFIRMED")
    return "UNCONFIRMED"


def error_code(root):
    error = root.find("error")
    return error.get("code") if error is not None else None


def write_result(method, http_status, body, count):
    root = response_root(body)
    unconfirmed = ["STOP"] if method == "nowplaying" else ["UNCERTAIN"] * count
    if root is None:
        return unconfirmed
    if root.get("status") == "failed":
        code = error_code(root)
        if code == "9":
            return ["AUTH_REQUIRED"] * count
        if method == "nowplaying":
            return ["STOP"]
        if code in {"11", "16"}:
            return ["RETRYABLE"] * count
        return ["PERMANENT_FAILURE"] * count if code else unconfirmed
    if http_status != 200 or root.get("status") != "ok":
        return unconfirmed

    items = root.findall("scrobbles/scrobble" if method == "scrobble" else "nowplaying")
    codes = [item.get("code", "") for entry in items for item in entry.findall("ignoredMessage")]
    if len(items) != count or any(len(entry.findall("ignoredMessage")) != 1 for entry in items):
        return unconfirmed
    if not all(code.isascii() and code.isdecimal() for code in codes):
        return unconfirmed
    results = ["ACKNOWLEDGED" if int(code) == 0 else "IGNORED" for code in codes]
    if method == "scrobble":
        summary = root.find("scrobbles")
        if (summary.get("accepted"), summary.get("ignored")) != (
            str(results.count("ACKNOWLEDGED")), str(results.count("IGNORED"))
        ):
            return unconfirmed
    return results


def eligible(duration, played, permission, consent, history_present, linked, owns_event):
    return (
        permission == "ALLOWED" and consent and history_present and linked and owns_event
        and duration is not None and duration > 30_000
        and (2 * played >= duration or played >= 240_000)
    )


class ContractFixtures(unittest.TestCase):
    fixtures = json.loads(Path(__file__).with_name("fixtures.json").read_text(encoding="utf-8"))

    def test_signature_and_form(self):
        for case in self.fixtures["requests"]:
            with self.subTest(case=case["name"]):
                params = case["params"]
                secret = self.fixtures["shared_secret"]
                self.assertEqual(signature(params, secret), case["signature"])
                self.assertEqual(signature(dict(params, format="xml", callback="unused"), secret), case["signature"])
                if "form" in case:
                    signed = dict(params, api_sig=case["signature"])
                    self.assertEqual(urlencode(sorted(signed.items())), case["form"])

    def test_browser_session_exchange_responses(self):
        for name, *values, expected in self.fixtures["authorization_attempts"]:
            with self.subTest(case=name):
                self.assertEqual(can_exchange(*values), expected)
        for name, body, expected in self.fixtures["session_responses"]:
            with self.subTest(case=name):
                self.assertEqual(session_result(body), expected)

    def test_write_responses(self):
        for name, method, status, count, body, expected in self.fixtures["write_responses"]:
            with self.subTest(case=name):
                self.assertEqual(write_result(method, status, body, count), expected)

    def test_scrobble_gate_on_send_or_retry(self):
        for name, *values, expected in self.fixtures["scrobble_gate"]:
            with self.subTest(case=name):
                self.assertEqual(eligible(*values), expected)


if __name__ == "__main__":
    unittest.main()
