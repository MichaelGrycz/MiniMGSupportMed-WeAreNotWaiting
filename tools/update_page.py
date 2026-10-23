#!/usr/bin/env python3
"""Updates the download page (Astro source) for a new release.

Usage: update_page.py <astro-file> <version> <notes>

Bumps the version shown on the page, follows it in the APK file name, and
prepends a new entry to the `releaseNotes` array. Historical entries keep their
own version numbers.
"""

import datetime
import io
import re
import sys

PATH, VERSION, NOTES = sys.argv[1], sys.argv[2], sys.argv[3]

# Notes may be passed as "@path" so multi-line text does not depend on shell quoting.
if NOTES.startswith("@"):
    with io.open(NOTES[1:], encoding="utf-8") as handle:
        NOTES = handle.read()

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

marker = "const releaseNotes = [\n"
if marker not in src:
    sys.exit("releaseNotes array not found in %s" % PATH)

# Only the page copy gets the new number: the header, the prose outside the changelog
# and the template body. Rewriting the whole file also changed version numbers
# quoted in older entries, which silently falsified the history.
head, _, rest = src.partition(marker)

# The releaseNotes array ends at the first line that closes it at column 0.
try:
    notes_end = rest.index("\n];\n") + len("\n];\n")
except ValueError:
    sys.exit("releaseNotes array is not closed in %s" % PATH)
notes, tail = rest[:notes_end], rest[notes_end:]


def rewrite_copy(text):
    text = re.sub(r"1\.0-beta\d+", VERSION, text)
    text = re.sub(r"MGLycemia-[0-9][^'\"]*\.apk", "MGLycemia-%s.apk" % VERSION, text)
    return re.sub(r"MGSupportCGM-[0-9][^'\"]*\.apk", "MGLycemia-%s.apk" % VERSION, text)


head = rewrite_copy(head)
tail = rewrite_copy(tail)

src = head + marker + notes + tail

# 3. Changelog entry on top.
first_entry = re.search(r"^\s*\{\s*version: '([^']+)'", notes, re.MULTILINE)
if first_entry and first_entry.group(1) == VERSION:
    with io.open(PATH, "w", encoding="utf-8") as handle:
        handle.write(src)
    print("page: %s already on top, only the copy was updated" % VERSION)
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
rest = rest.replace("    tag: 'Najnowsza',\n", "", 1)  # previous newest loses the badge

with io.open(PATH, "w", encoding="utf-8") as handle:
    handle.write(head + marker + entry + rest)

print("page: added %s" % VERSION)
