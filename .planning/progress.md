# Progress

Current: released 1.0-beta17 (pairing identity, GATT ordering, bond diagnostics).
Completed:

- Unique per-install `Mobile NNNNNN` identity; rotation on unpair and on re-pair.
- GATT: respond before SAKE callbacks, reset CCCD per connection, keep advertising through bonding until SAKE completes, log bond transitions/reasons.
- Added PAIRING_FAILED fault and localized messages.
- Tests and assembleDebug pass on JBR21.
  Next: await user log from beta17 to confirm pairing on Realme/S24.
  Constraints: no physical pump available; Android 16 may still require manual removal of a stale OS bond because removeBond() is privileged.
