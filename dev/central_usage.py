#!/usr/bin/env python3
"""Maven Central publishing usage for this calendar month, read from repo1.maven.org's public
listings: every version published this month under the organisation's namespace, its files, and
whether one more release fits the free tier's monthly file limit; exit 3 when it does not.

    python3 -I dev/central_usage.py

Central evaluates its limits per calendar month (taken here as UTC, as repo1's listing times are)
against the organisation's whole usage, so
every artifact under the namespace counts, not only Lathe's. The limit is LATHE_CENTRAL_FILE_LIMIT
(default 1000), copied from the Usage Center, as Sonatype may change it; Central has no usage API.
The next release is estimated from the newest Lathe release's files, without the SHA-256 and
SHA-512 checksums the release no longer sends.
"""
import functools
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone

REPO = "https://repo1.maven.org/maven2/"
NAMESPACE = "io/github/ag-libs/"
# The parent pom and Lathe's modules share the "lathe" prefix; the estimate counts them.
LATHE_PREFIX = "lathe"
ENTRY = re.compile(r'<a href="([^"]+)"[^>]*>[^<]*</a>\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2})?')
OPTIONAL_CHECKSUMS = (".sha256", ".sha512")


# Each listing is fetched once: this month's newest release is counted, then estimated from.
@functools.lru_cache(maxsize=None)
def listing(path):
    """(name, date or None) of each entry in a repo1 directory listing; directories end in '/'."""
    with urllib.request.urlopen(REPO + path, timeout=30) as response:
        page = response.read().decode("utf-8")
    return [(m.group(1), m.group(2)) for m in ENTRY.finditer(page) if m.group(1) != "../"]


def versions(group):
    """(artifact path, version, date) of every version under a group, its sub-groups included: an
    artifact directory holds maven-metadata.xml, a group directory does not."""
    found = []
    for name, _ in listing(group):
        if not name.endswith("/"):
            continue
        entries = listing(group + name)
        if any(entry == "maven-metadata.xml" for entry, _ in entries):
            found += [(group + name, entry.rstrip("/"), date) for entry, date in entries
                      if entry.endswith("/") and date]
        else:
            found += versions(group + name)
    return found


def files(artifact, version):
    return [name for name, _ in listing("%s%s/" % (artifact, version)) if not name.endswith("/")]


def version_key(version):
    return [int(part) if part.isdigit() else 0 for part in version.split(".")]


def main():
    limit = int(os.environ.get("LATHE_CENTRAL_FILE_LIMIT", "1000"))
    month = datetime.now(timezone.utc).strftime("%Y-%m")

    published = versions(NAMESPACE)
    this_month = [(a, v) for a, v, date in published if date.startswith(month)]
    used = 0
    for artifact, version in sorted(this_month):
        count = len(files(artifact, version))
        used += count
        print("%-60s %4d" % ("%s%s" % (artifact[len(NAMESPACE):], version), count))

    lathe = [(a, v) for a, v, _ in published if a[len(NAMESPACE):].startswith(LATHE_PREFIX)]
    newest = max((v for _, v in lathe), key=version_key)
    estimate = 0
    for artifact, version in lathe:
        if version == newest:
            estimate += sum(1 for name in files(artifact, version) if not name.endswith(OPTIONAL_CHECKSUMS))
    print("\n%s: %d file(s) published under %s this month; limit %d." % (month, used, NAMESPACE.rstrip("/"), limit))
    print("Next release: about %d file(s) (Lathe %s's, without SHA-256/SHA-512); %d after it."
          % (estimate, newest, used + estimate))
    if used + estimate > limit:
        print("OVER: one more release would exceed Central's monthly file limit; wait until next month "
              "or ask central-support@sonatype.com first.")
        sys.exit(3)


if __name__ == "__main__":
    main()
