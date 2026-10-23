# Security policy

## Supported versions

Only the latest release published in
[GitHub Releases](https://github.com/MichaelGrycz/MiniMGSupportMed-WeAreNotWaiting/releases)
receives security fixes.

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Send an email to **kontakt@mgsupport.pl** with:

- the app version (`Settings` → installed version),
- the Android version and phone model,
- steps to reproduce,
- the impact you observed,
- a log if useful — but **remove** pairing codes, Bluetooth addresses, names,
  medical data and anything else that identifies a person.

We aim to acknowledge reports within 7 days.

## Scope

- The app is **read-only**: it never sends boluses, changes basal rates or
  modifies pump settings.
- Family sync is **optional and self-hosted**. The person running that server is
  responsible for its access control, backups and retention.
- The app has no default sync server, no analytics and no ads.

## Please do not include

- pairing codes or family codes,
- Bluetooth MAC addresses or device serial numbers,
- names, e-mail addresses or other personal data,
- unredacted `minimed.db` files or CSV exports.

## Safe harbour

Good-faith research that respects this policy will not be pursued. Do not test
against other people's devices, servers or data.
