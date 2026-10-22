# BLE, durable backup and insulin chart

## Scope
- Fix code-proven pairing lifecycle defects; instrument Android bond/GATT failures. Hardware verification is required for Galaxy S24/Android 16; no universal pump compatibility claims.
- Opt-in automatic CSV backup in a SAF folder outside app-specific storage, surviving uninstall. Explicit privacy disclosure and recovery after reinstall.
- Full-screen chart with correctly timestamped, honestly classified delivered insulin events, never fabricated/inferred from IOB.

## Completion
- [x] Initial working tree clean; existing app unit tests pass on JBR 21.
- [ ] BLE lifecycle regression tests and focused fixes.
- [ ] Additive SQLite migration, raw history preservation/projection, alert backup, round-trip tests.
- [ ] Durable backup service + selection/status/recovery UI.
- [ ] Full-screen chart and distinct event lanes/details/accessibility.
- [ ] Controller integration and incremental history backfill.
- [ ] Unit tests, debug APK, lint, diff review; report hardware/emulator limitations.

No publish/push/commit, no dosing/control commands, no deletion of user data.
