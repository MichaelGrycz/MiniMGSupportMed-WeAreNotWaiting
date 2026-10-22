#!/usr/bin/env python3
"""Creates (or reuses) a GitHub release and uploads the APK asset.

Usage: github_release.py <owner/repo> <tag> <name> <notes> <apk-path>

The token is taken from GITHUB_TOKEN when set, otherwise from the git
credential helper. It is never printed.
"""

import json
import subprocess
import sys
import urllib.error
import urllib.request

REPO, TAG, NAME, NOTES, APK = sys.argv[1:6]


def token() -> str:
    helper = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True,
        text=True,
    )
    for line in helper.stdout.splitlines():
        if line.startswith("password="):
            return line[len("password=") :]
    sys.exit("no GitHub credential available; run gh auth login once")


TOKEN = token()
HEADERS = {
    "Authorization": "Bearer " + TOKEN,
    "Accept": "application/vnd.github+json",
    "User-Agent": "mglycemia-release",
}


def api(method: str, url: str, payload=None, raw: bytes = None, ctype=None):
    data = (
        raw if raw is not None else (json.dumps(payload).encode() if payload else None)
    )
    headers = dict(HEADERS)
    if ctype:
        headers["Content-Type"] = ctype
    elif payload:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


status, release = api(
    "GET", "https://api.github.com/repos/%s/releases/tags/%s" % (REPO, TAG)
)

if status == 200:
    print("release: already exists (%s)" % release.get("html_url"))
elif status == 404:
    status, release = api(
        "POST",
        "https://api.github.com/repos/%s/releases" % REPO,
        payload={
            "tag_name": TAG,
            "name": NAME,
            "body": NOTES,
            "draft": False,
            "prerelease": False,
        },
    )
    if status != 201:
        sys.exit("release create failed: %s %s" % (status, release))
    print("release: created (%s)" % release.get("html_url"))
else:
    sys.exit("release lookup failed: %s" % status)

release_id = release["id"]
asset_name = APK.split("/")[-1].split("\\")[-1]

with open(APK, "rb") as handle:
    payload = handle.read()

status, asset = api(
    "POST",
    "https://uploads.github.com/repos/%s/releases/%d/assets?name=%s"
    % (REPO, release_id, asset_name),
    raw=payload,
    ctype="application/vnd.android.package-archive",
)
if status not in (200, 201):
    sys.exit("asset upload failed: %s %s" % (status, asset))

print("asset: %s (%d bytes)" % (asset.get("name"), asset.get("size", 0)))
print("download: %s" % asset.get("browser_download_url"))
