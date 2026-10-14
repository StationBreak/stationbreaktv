# Bundled Let's Encrypt roots

The two certificates in `app/src/main/res/raw/` are extra trust anchors, listed beside the system store in
`app/src/main/res/xml/network_security_config.xml`. Fire OS 6 (API 25) ships ISRG Root X1 but not ISRG Root X2
(Android 14 and later do) or Root YE. Backlog S0.3.5. The policy: system store plus these two roots, never a user-installed CA, no debug-overrides, no pinning.

| File | Certificate | Key | Valid until | SHA-256 |
| --- | --- | --- | --- | --- |
| `isrg_root_x2.pem` | ISRG Root X2 (self-signed) | ECDSA P-384 | 2040-09-17 | `69:72:9B:8E:15:A8:6E:FC:17:7A:57:AF:B7:17:1D:FC:64:AD:D2:8C:2F:CA:8C:F1:50:7E:34:45:3C:CB:14:70` |
| `isrg_root_ye.pem` | ISRG Root YE (self-signed) | ECDSA P-384 | 2045-09-02 | `E1:4F:FC:AD:5B:00:25:73:10:06:CA:A4:3A:12:1A:22:D8:E9:70:0F:4F:B9:CF:85:2F:02:A7:08:AA:5D:56:66` |

`TrustAnchorsTest` holds the same two values and fails if a file, its signature, its CA flags or its validity drifts.

## Source

Both were downloaded on 2026-10-14 from Let's Encrypt's "Chains of Trust" page
(<https://letsencrypt.org/certificates/>):

- ISRG Root X2: <https://letsencrypt.org/certs/isrg-root-x2.pem>
- ISRG Root YE: <https://letsencrypt.org/certs/gen-y/root-ye.pem>

## Checking the fingerprints

Let's Encrypt does not publish SHA-256 fingerprints for these roots on its pages, so the check has three parts:

1. **Recompute:** `openssl x509 -in app/src/main/res/raw/<file>.pem -noout -fingerprint -sha256` for each file, and
   compare with the table (and with the constants in `TrustAnchorsTest`).
2. **X2 against an independent store:** ISRG Root X2's fingerprint above is the one in the Mozilla root program as
   shipped in a current Linux `ca-certificates` bundle.
3. **Root YE against X2 by signature:** Let's Encrypt's cross-signed Root YE
   (<https://letsencrypt.org/certs/gen-y/root-ye-by-x2.pem>, kept as a test fixture at
   `app/src/androidTest/assets/chains/isrg_root_ye_by_x2.pem`) carries the same public key as `isrg_root_ye.pem` and
   verifies under the X2 above. `TrustAnchorsTest` checks this on every build, which ties YE to the root that is in
   the trust stores.

## Decision: Root YE is bundled, Root YR is not

- **YE.** The default chain on Let's Encrypt's page is `EE ← YE1/YE2 ← Root YE ← ISRG Root X2 ← ISRG Root X1`.
  Root YE is "not yet included in Root Program Trust Stores", so a client with only system roots has to reach it
  through the cross-signs. Bundling the self-signed root lets a chain end at it (or at X2) without the X1
  cross-sign, which is the case the backlog asks to prove.
- **YR.** The page lists the RSA chain as `EE ← YR1/YR2 ← Root YR ← ISRG Root X1`, with Root YR cross-signed by
  X1. API 25 ships ISRG Root X1 (Let's Encrypt's compatibility page: "Android >= 7.1.1"), so that chain already
  ends at a system root and a bundled YR adds nothing. It is not bundled; add `isrg_root_yr.pem` (and its
  constants and config entry) only if a chain a device is served ends at YR with no X1 cross-sign.
- The page's own wording: "Chains which terminate at Root YE or Root YR are not expected to work with any of the
  major trust stores".

## Refresh

See "Bundled Let's Encrypt roots" in [CONTRIBUTING.md](../../CONTRIBUTING.md).
