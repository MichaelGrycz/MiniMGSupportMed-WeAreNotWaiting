#!/usr/bin/env python3
"""Updates the download page (Astro source) for a new release.

Usage: update_page.py <astro-file> <version> <notes>

Bumps the version shown on the page, follows it in the APK file name, and
prepends a new entry to the `releaseNotes` array. The entry that used to be
marked "Najnowsza" loses that badge.
"""

import datetime
import io
import re
import sys

PATH, VERSION, NOTES = sys.argv[1], sys.argv[2], sys.argv[3]

PL_MONTHS = {
    1: "stycznia",
    2: "lutego",
    3: "marca",
    4: "kwietnia",
    5: "maja",
    6: "czerwca",
    7: "lipca",
    8: "sierpnia",
    9: "września",
    10: "października",
    11: "listopada",
    12: "grudnia",
}

with io.open(PATH, encoding="utf-8") as handle:
    src = handle.read()

# 1. Version shown on the page.
src = re.sub(r"1\.0-beta\d+", VERSION, src)

# 2. APK file name follows the version.
src = re.sub(r"MGLycemia-[0-9][^'\"]*\.apk", "MGLycemia-%s.apk" % VERSION, src)
src = re.sub(r"MGSupportCGM-[0-9][^'\"]*\.apk", "MGLycemia-%s.apk" % VERSION, src)

# 3. Changelog entry on top.
marker = "const releaseNotes = [\n"
if marker not in src:
    sys.exit("releaseNotes array not found in %s" % PATH)

if ("version: '%s'," % VERSION) in src:
    with io.open(PATH, "w", encoding="utf-8") as handle:
        handle.write(src)
    print("page: %s already listed, only the copy was updated" % VERSION)
    sys.exit(0)

items = [
    line.strip().lstrip("-* ").replace("'", "’")
    for line in NOTES.splitlines()
    if line.strip()
] or ["Wydanie %s." % VERSION]

today = datetime.date.today()
entry = (
    "  {\n"
    "    version: '%s',\n"
    "    date: '%d %s %d',\n"
    "    tag: 'Najnowsza',\n"
    "    items: [\n%s\n    ],\n"
    "  },\n"
) % (
    VERSION,
    today.day,
    PL_MONTHS[today.month],
    today.year,
    "\n".join("      '%s'," % item for item in items),
)

head, rest = src.split(marker, 1)
# The previous newest entry loses its badge.
rest = rest.replace("    tag: 'Najnowsza',\n", "", 1)

with io.open(PATH, "w", encoding="utf-8") as handle:
    handle.write(head + marker + entry + rest)

print("page: added %s" % VERSION)
