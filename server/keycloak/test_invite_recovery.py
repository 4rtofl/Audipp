"""Contract test for disposable loopback Keycloak and #34 HTTPS API fixtures."""

import argparse
import base64
import hashlib
from html.parser import HTMLParser
from http.cookiejar import CookieJar
import json
from pathlib import Path
import secrets
import ssl
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit
from urllib.request import (
    HTTPCookieProcessor, HTTPRedirectHandler, HTTPSHandler, ProxyHandler, Request, build_opener,
)
from uuid import uuid4


REDIRECT = "com.audipp:/oauth2redirect"
CLIENT = "audipp-android"
OIDC = "/protocol/openid-connect"
LOCAL_EMAIL = "invited@example.test"
USER = "invited-person"
TEMPORARY = "fixture-initial-secret"
PASSWORD = "fixture-changed-secret"
RECOVERY = "fixture-recovery-secret"
UPSTREAM_PASSWORD = "fixture-provider-secret"


class StopRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LoseResetReply(HTTPSHandler):
    def https_response(self, request, response):
        assert request.get_method() == "PUT" and request.selector.endswith("/reset-password")
        assert response.status == 204
        response.close()
        raise TimeoutError


class Forms(HTMLParser):
    def __init__(self, page):
        super().__init__()
        self.actions = {}
        self.feed(page)

    def handle_starttag(self, tag, attrs):
        if tag == "form":
            fields = dict(attrs)
            self.actions[fields.get("id")] = fields.get("action")


def browser(context):
    return build_opener(ProxyHandler({}), HTTPSHandler(context=context),
                        StopRedirects(), HTTPCookieProcessor(CookieJar()))


def request(opener, url, *, method=None, form=None, payload=None, token=None):
    assert urlsplit(url).scheme == "https" and urlsplit(url).hostname == "127.0.0.1"
    headers = {}
    data = None
    if form is not None:
        data = urlencode(form).encode()
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode()
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    try:
        response = opener.open(Request(url, data, headers, method=method), timeout=10)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read().decode()


def expect(response, status):
    actual, headers, body = response
    assert actual == status, f"Expected HTTP {status}, got {actual}"
    return json.loads(body) if headers.get_content_type() == "application/json" and body else None


def follow(opener, response, origin, callback):
    for _ in range(12):
        status, headers, _ = response
        if status not in (302, 303):
            return response
        location = urljoin(origin, headers["Location"])
        if location.split("?", 1)[0] == callback:
            return response
        assert location.startswith(origin + "/"), "Browser redirect escaped the local fixture"
        response = request(opener, location)
    raise AssertionError("Unexpected redirect loop")


def authorization(issuer, client=CLIENT, redirect=REDIRECT, **extra):
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    state = secrets.token_urlsafe(16)
    query = urlencode(dict(response_type="code", client_id=client, redirect_uri=redirect,
                           scope="openid", state=state, code_challenge=challenge,
                           code_challenge_method="S256") | extra)
    return issuer + OIDC + "/auth?" + query, verifier, state


def submit(opener, origin, page, form_id, **fields):
    actions = Forms(page[2]).actions
    assert page[0] == 200 and form_id in actions, f"Expected form {form_id!r} on HTTP {page[0]}"
    return request(opener, urljoin(origin, actions[form_id]), form=fields)


def code_for(response, redirect, state):
    assert response[0] == 302, f"Expected authorization redirect, got HTTP {response[0]}"
    target = response[1]["Location"]
    assert target.split("?", 1)[0] == redirect
    query = parse_qs(urlsplit(target).query)
    assert query.get("state") == [state] and "code" in query, "Expected an authorized callback"
    return query["code"][0], query


def claims(token):
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def user(username, email, password, *, temporary=False):
    return dict(username=username, email=email, firstName="Fixture", lastName="User",
                emailVerified=False, enabled=True,
                credentials=[dict(type="password", value=password, temporary=temporary)])


def verify_contract(origin, api, context):
    admin_browser = browser(context)
    issuer = origin + "/realms/audipp"
    admin_token = expect(request(admin_browser, origin + "/realms/master" + OIDC + "/token", form=dict(
        grant_type="password", client_id="admin-cli", username="fixture-admin",
        password="fixture-admin-only",
    )), 200)["access_token"]

    def admin(path="", *, method=None, payload=None, bearer=admin_token):
        return request(admin_browser, origin + "/admin/realms" + path, method=method,
                       payload=payload, token=bearer)

    realm = json.loads(Path(__file__).with_name("audipp-realm.json").read_text(encoding="utf-8"))
    expect(admin(payload=realm), 201)
    imported = expect(admin("/audipp"), 200)
    for flag in ("registrationAllowed", "resetPasswordAllowed", "verifyEmail"):
        assert imported[flag] is False
    assert imported["sslRequired"] == "all"
    assert not imported.get("smtpServer")
    assert not imported.get("eventsListeners")

    def create_user(record):
        reply = admin("/audipp/users", payload=record)
        expect(reply, 201)
        return "/audipp/users/" + reply[1]["Location"].rsplit("/", 1)[-1]

    def app_tokens(opener, response, verifier, state, expected=200):
        code, _ = code_for(response, REDIRECT, state)
        return expect(request(opener, issuer + OIDC + "/token", form=dict(
            grant_type="authorization_code", client_id=CLIENT, redirect_uri=REDIRECT,
            code=code, code_verifier=verifier,
        )), expected)

    def login(opener, name, password):
        url, verifier, state = authorization(issuer)
        page = request(opener, url)
        reply = submit(opener, origin, page, "kc-form-login", username=name, password=password)
        return follow(opener, reply, origin, REDIRECT), verifier, state

    def business(path, token, expected=200, payload=None):
        return expect(request(admin_browser, api + path, token=token, payload=payload), expected)

    local = browser(context)
    google = expect(admin("/audipp/identity-provider/instances/google"), 200)
    assert google["enabled"] is False and google["trustEmail"] is False and google["storeToken"] is False
    assert "clientId" not in google["config"] and "clientSecret" not in google["config"]

    invite = create_user(user(USER, LOCAL_EMAIL, TEMPORARY, temporary=True))
    other = create_user(user("other-person", "other@example.test", PASSWORD))
    assert expect(admin(invite), 200)["emailVerified"] is False
    url, _, _ = authorization(issuer)
    screen = request(local, url)
    assert screen[0] == 200 and 'id="kc-registration"' not in screen[2]
    assert "reset-credentials?" not in screen[2] and 'id="social-google"' not in screen[2]
    registration = url.replace(OIDC + "/auth?", OIDC + "/registrations?")
    assert request(local, registration)[0] in (400, 403)
    reset = url.replace(OIDC + "/auth?", "/login-actions/reset-credentials?")
    assert request(local, reset)[0] in (400, 403)
    missing_pkce = request(local, url.replace("&code_challenge=", "&unused_challenge="))
    assert missing_pkce[0] == 302
    bad_callback = missing_pkce[1]["Location"]
    assert bad_callback.split("?", 1)[0] == REDIRECT
    assert parse_qs(urlsplit(bad_callback).query).get("error") == ["invalid_request"]

    change, verifier, state = login(local, LOCAL_EMAIL, TEMPORARY)
    reply = submit(local, origin, change, "kc-passwd-update-form",
                   **{"password-new": PASSWORD, "password-confirm": PASSWORD})
    tokens = app_tokens(local, follow(local, reply, origin, REDIRECT), verifier, state)
    access = tokens["access_token"]
    token_claims = claims(access)
    assert token_claims["email_verified"] is False
    assert token_claims["exp"] - token_claims["iat"] == 60
    assert token_claims["iss"] == issuer
    assert token_claims["typ"] == "Bearer"
    assert token_claims["sub"] == invite.rsplit("/", 1)[-1]
    audience = token_claims["aud"]
    assert audience == "audipp-api" or (isinstance(audience, list) and "audipp-api" in audience)
    business("/v1/me", tokens["id_token"], 401)
    owner = business("/v1/me", access)
    device = str(uuid4())
    assert business("/v1/auth/session", access, payload={"device_id": device})["user"] == owner
    assert "kc-form-login" in Forms(login(browser(context), LOCAL_EMAIL, TEMPORARY)[0][2]).actions
    bad_pkce, _, bad_state = login(local, LOCAL_EMAIL, PASSWORD)
    app_tokens(local, bad_pkce, secrets.token_urlsafe(48), bad_state, expected=400)
    print("PASS invite, temporary password, no automatic mail, PKCE, signed Keycloak token to #34 API")

    other_browser = browser(context)
    other_tokens = app_tokens(other_browser, *login(other_browser, "other@example.test", PASSWORD))
    assert business("/v1/me", other_tokens["access_token"])["user_id"] != owner["user_id"]
    business("/v1/me/devices/" + device, other_tokens["access_token"], 403)
    reset_path = invite + "/reset-password"
    reset_credential = dict(type="password", value=RECOVERY, temporary=True)
    unauthorized = admin(reset_path, method="PUT", payload=reset_credential,
                         bearer=other_tokens["access_token"])
    assert unauthorized[0] in (401, 403)
    assert expect(admin(invite), 200)["enabled"] is True

    expect(admin(invite, method="PUT", payload={"enabled": False}), 204)
    interrupted = build_opener(ProxyHandler({}), LoseResetReply(context=context), StopRedirects())
    try:
        request(interrupted, origin + "/admin/realms" + reset_path, method="PUT",
                payload=reset_credential, token=admin_token)
    except TimeoutError:
        pass
    else:
        raise AssertionError("Expected an interrupted reset response")
    pending = expect(admin(invite), 200)
    assert pending["enabled"] is False and "UPDATE_PASSWORD" in pending["requiredActions"]
    blocked, _, _ = login(browser(context), LOCAL_EMAIL, RECOVERY)
    assert "kc-form-login" in Forms(blocked[2]).actions
    expect(admin(reset_path, method="PUT", payload=reset_credential), 204)
    expect(admin(invite + "/logout", method="POST"), 204)
    expect(admin(invite + "/logout", method="POST"), 204)
    assert expect(admin(invite + "/sessions"), 200) == []
    expect(admin(invite, method="PUT", payload={"enabled": True}), 204)
    old_refresh = request(local, issuer + OIDC + "/token", form=dict(
        grant_type="refresh_token", client_id=CLIENT, refresh_token=tokens["refresh_token"],
    ))
    assert expect(old_refresh, 400)["error"] == "invalid_grant"
    assert "kc-form-login" in Forms(login(browser(context), LOCAL_EMAIL, PASSWORD)[0][2]).actions
    change, verifier, state = login(local, LOCAL_EMAIL, RECOVERY)
    reply = submit(local, origin, change, "kc-passwd-update-form",
                   **{"password-new": PASSWORD, "password-confirm": PASSWORD})
    recovered = app_tokens(local, follow(local, reply, origin, REDIRECT), verifier, state)
    assert business("/v1/me", recovered["access_token"]) == owner
    assert expect(admin(other + "/sessions"), 200), "Recovery signed out another user"
    print("PASS unauthorized reset, interruption and retry, session/refresh revocation, stable owner")

    upstream = origin + "/realms/fixture-provider"
    expect(admin(payload=dict(realm="fixture-provider", enabled=True, sslRequired="all",
                              clients=[dict(clientId="fixture-broker", secret="fixture-client-secret",
                                            standardFlowEnabled=True, protocol="openid-connect",
                                            redirectUris=[issuer + "/broker/fixture/endpoint"])],
                              users=[user("provider-user", LOCAL_EMAIL, UPSTREAM_PASSWORD)
                                     | {"emailVerified": True}])), 201)
    fixture = google | dict(alias="fixture", providerId="oidc", enabled=True, config=dict(
        syncMode="IMPORT", clientId="fixture-broker", clientSecret="fixture-client-secret",
        issuer=upstream, authorizationUrl=upstream + OIDC + "/auth",
        tokenUrl=upstream + OIDC + "/token", jwksUrl=upstream + OIDC + "/certs",
        userInfoUrl=upstream + OIDC + "/userinfo", useJwksUrl="true",
        validateSignature="true", defaultScope="openid email profile",
    ))
    fixture.pop("internalId", None)
    expect(admin("/audipp/identity-provider/instances", payload=fixture), 201)

    def provider_login(opener, page, callback):
        reply = submit(opener, origin, page, "kc-form-login",
                       username=LOCAL_EMAIL, password=UPSTREAM_PASSWORD)
        return follow(opener, reply, origin, callback)

    def links():
        return expect(admin(invite + "/federated-identity"), 200)

    outsider = browser(context)
    broker_url, _, _ = authorization(issuer, kc_idp_hint="fixture")
    provider_page = follow(outsider, request(outsider, broker_url), origin, REDIRECT)
    denied = provider_login(outsider, provider_page, REDIRECT)
    assert denied[0] in (400, 401, 403) and links() == []
    assert len(expect(admin("/audipp/users"), 200)) == 2

    account_redirect = issuer + "/account/"

    def link_prompt():
        action, _, state = authorization(issuer, client="account-console", redirect=account_redirect,
                                         kc_action="idp_link:fixture")
        # A local session cookie already exists. A fresh password form must still be shown.
        page = request(local, action)
        confirmation = submit(local, origin, page, "kc-form-login",
                              username=LOCAL_EMAIL, password=PASSWORD)
        return follow(local, confirmation, origin, account_redirect), state

    confirmation, state = link_prompt()
    cancel = submit(local, origin, confirmation, None, **{"cancel-aia": "true"})
    _, cancelled = code_for(follow(local, cancel, origin, account_redirect), account_redirect, state)
    assert cancelled.get("kc_action_status") == ["cancelled"] and links() == []
    confirmation, state = link_prompt()
    onward = submit(local, origin, confirmation, None, **{"continue": "Continue"})
    provider_page = follow(local, onward, origin, account_redirect)
    provider_reply = submit(local, origin, provider_page, "kc-form-login",
                            username=LOCAL_EMAIL, password=UPSTREAM_PASSWORD)
    assert provider_reply[0] == 302
    broker_callback = provider_reply[1]["Location"]
    assert broker_callback.startswith(issuer + "/broker/fixture/endpoint?")
    linked = follow(local, provider_reply, origin, account_redirect)
    _, result = code_for(linked, account_redirect, state)
    assert result.get("kc_action_status") == ["success"]
    [link] = links()
    assert link["identityProvider"] == "fixture"
    replay = follow(local, request(local, broker_callback), origin, account_redirect)
    if replay[0] == 302:
        target = replay[1]["Location"]
        query = parse_qs(urlsplit(target).query)
        assert target.split("?", 1)[0] == account_redirect and "error" in query and "code" not in query
    else:
        assert replay[0] in (400, 401, 403)
    assert links() == [link]

    new_browser = browser(context)
    broker_url, verifier, state = authorization(issuer, kc_idp_hint="fixture")
    provider_page = follow(new_browser, request(new_browser, broker_url), origin, REDIRECT)
    result = provider_login(new_browser, provider_page, REDIRECT)
    linked_tokens = app_tokens(new_browser, result, verifier, state)
    linked_token = linked_tokens["access_token"]
    assert claims(linked_token)["sub"] == token_claims["sub"]
    assert claims(linked_token)["email_verified"] is False
    assert business("/v1/me", linked_token) == owner
    print("PASS local OIDC stand-in: matching email denied, reauthentication, explicit link, cancellation and same owner")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keycloak", required=True)
    parser.add_argument("--api", required=True)
    parser.add_argument("--ca", required=True)
    args = parser.parse_args()
    verify_contract(args.keycloak, args.api, ssl.create_default_context(cafile=args.ca))


if __name__ == "__main__":
    main()
