# ADR-0014: Scheme fallback and the cleartext policy

- **Status:** Accepted (the residual risk below is shown to the owner on the PR that introduces this ADR)
- **Date:** 2026-10-13
- **Decided by:** a technical decision under the owner's standing instruction, from the F0.3 spec and review by Angua (Security) (R5 to R8)
- **Revisit:** if a user is caught out by a single-label hostname that resolved publicly, if Tailscale-style addresses (carrier-grade NAT) turn out to be the common home setup, or if the adapter gains a DNS-aware network layer
- **Informs:** F0.3 (S0.3.1), F0.5 (the connection screen), ADR-0006 (errors)

## Context

S0.3.1 says: with no scheme typed, try `https` first and fall back to `http` "only on connection refused or
unreachable". That wording breaks the most common home entry. `192.168.1.10:8096` is *open*: a plain-HTTP Jellyfin
accepts the TCP connection and answers the TLS hello with an HTTP error in plain text. The TLS layer reports an
`SSLException`, not a refusal. A strict reading would make the app say "TLS error" for a perfectly working LAN server.

The opposite mistake is worse. If any TLS failure fell back to `http`, an attacker who breaks the handshake (a
downgrade attack, or a hostile network that resets the connection) would get the user's password in cleartext.

The backlog also says the "private" set for allowing cleartext without friction is RFC 1918, link-local, `fd00::/8`
and `.local`. That list leaves out loopback, the rest of the IPv6 unique-local range, `.home.arpa` and bare names such
as `http://jellyfin:8096`, which is how most people address a server on their own network.

## Decision

1. **`https` first, then `http` only on one of three findings.** With no scheme typed, `discover` falls back to `http`
   only if the `https` attempt (a) was refused, (b) found the network unreachable, or (c) got a **positively identified
   plaintext answer**: an `SSLException` whose message is one the TLS stack uses when the bytes it received were not a
   TLS record at all. Each TLS stack words (c) differently, so the markers are a checked-in table
   (`PlaintextTlsMarkers`), each added only after a test observed it: the JDK's on the JVM, Conscrypt's on the API 25
   emulator. Any other `SSLException` is a TLS error and never falls back: a handshake alert, a protocol or cipher
   mismatch, a reset or EOF mid-handshake, anything unrecognised. A certificate problem (untrusted, expired, hostname
   mismatch), a timeout and a DNS failure never fall back either.
2. **`discover` is setup-only.** It reports `resolvedByFallback`, so the screen can show a scheme the app chose
   differently from one the user typed. A caller that already holds an address calls `connect`, which probes that
   scheme and never downgrades. An explicit scheme in the input is honoured exactly and never probed the other way.
   Persisting the chosen scheme belongs to F0.4/F0.5 (`data:settings`); this ADR only guarantees the value carries it.
3. **The real guard against a downgrade is the cleartext acknowledgement, not the fallback rule.** `http` to a host
   that is not private (`ServerAddress.cleartextNeedsConfirmation`) needs a `CleartextAcknowledgement` built from
   **that exact address**: host, port, scheme and subpath all have to match, or the adapter returns
   `CleartextNotConfirmed` without sending anything. The adapter enforces it before any request that carries a
   password, a Quick Connect secret or a token (and before `connect`), so it does not depend on a screen remembering.
   The screen that asks must say plainly that the password and token will travel unencrypted (requirement only; the
   wording is designed with Adorabelle).
4. **"Private" is decided from the address text only, never from DNS.** A DNS answer comes from a resolver an
   attacker may influence, which would make the policy depend on the attacker. Private is: IPv4 `10/8`, `172.16/12`,
   `192.168/16`, `169.254/16` and `127/8`; IPv6 `fc00::/7` (a superset of the backlog's `fd00::/8`), `fe80::/10` and
   `::1`; hostnames ending `.local` or `.home.arpa`; and **single-label hostnames**. An IPv4-mapped IPv6 literal is
   classified by its IPv4 part. Everything else is public.
5. **Carrier-grade NAT (`100.64.0.0/10`, the range Tailscale uses) is deliberately not private.** Calling it private
   would accept a risk the owner has not accepted: that range is shared between customers of an ISP. A user on
   Tailscale gets the confirmation prompt for `http`, or uses `https`.
6. **Numeric hosts are strict.** An IPv4 literal is recognised only as four decimal octets without leading zeros.
   `127.1`, `0x7f.1`, `2130706433` and octal spellings are `InvalidAddress`, because the URL parser, the resolver and the
   TLS stack can each read them differently. `0.0.0.0/8`, multicast, broadcast, and the IPv6 unspecified and multicast
   addresses are `InvalidAddress`. One trailing dot is stripped; a second is an empty label.

## Residual risk (for the owner)

A **single-label hostname** can in principle resolve to a public address: a search-domain suffix on the device or a
hostile resolver can turn `jellyfin` into `jellyfin.attacker.example`. Cleartext to a single-label name therefore skips
the confirmation prompt even though the traffic could leave the home network. Deciding "private" from text alone is a
deliberate trade-off. The alternative, resolving the name and checking the address, depends on a resolver an attacker
may influence, and would also fail offline. The trade-off was accepted in the security review (Angua (Security),
2026-10-13) and is recorded here so it is a known risk, not a surprise. The mitigation is that this applies to
`http` with a bare name only: `https` is unaffected, and anything with a dot that is not `.local` or `.home.arpa`
still asks first.

## Consequences

- The most common LAN entry (`host:8096` over plain HTTP) works without a TLS error, and a broken or hostile
  handshake never turns into a cleartext request.
- Two TLS stacks, two sets of markers: a new Android version or JDK can word the plaintext case differently. The
  marker tests on the emulator and the JVM are the check, and an unrecognised wording fails safe (a TLS error, no
  fallback).
- ADR-0006's note that the fallback triggers "only on connection refused" is amended to match, and so is S0.3.1 in the
  backlog.
