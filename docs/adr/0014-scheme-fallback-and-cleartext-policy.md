# ADR-0014: Scheme fallback and the cleartext policy

- **Status:** Accepted
- **Date:** 2026-10-13, amended 2026-10-14
- **Decided by:** the owner for decisions 3 and 7 (2026-10-14: a fallback to `http` always asks, and the remaining bare-name risk is accepted); the rest is a technical decision under the owner's standing instruction, from the F0.3 spec and Angua's security review (findings R5 to R9 and R11)
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
   (`PlaintextTlsMarkers`), filled from what a test observed: the JDK's on the JVM, Conscrypt's on the API 25
   emulator (`PlaintextTlsMarkerTest`, which passes on API 25 with the table as committed; the run does not record
   which of the Conscrypt entries matched, and "Unable to parse TLS packet header", Conscrypt's wording on Android 10
   and later, is taken from its source and still needs a device check). An unrecognised wording fails safe: it is a TLS
   error and does not fall back. Any other `SSLException` is a TLS error and never falls back: a handshake alert, a protocol or cipher
   mismatch, a reset or EOF mid-handshake, anything unrecognised. A certificate problem (untrusted, expired, hostname
   mismatch), a timeout and a DNS failure never fall back either.
2. **`discover` is setup-only.** It reports `resolvedByFallback`, so the screen can show a scheme the app chose
   differently from one the user typed. A caller that already holds an address calls `connect`, which probes that
   scheme and never downgrades. An explicit scheme in the input is honoured exactly and never probed the other way.
   Persisting the chosen scheme belongs to F0.4/F0.5 (`data:settings`); this ADR only guarantees the value carries it.
3. **The real guard against a downgrade is the cleartext acknowledgement, not the fallback rule.** `http` to a host
   that is not private, or `http` reached by falling back (decision 7), needs
   (`ServerAddress.cleartextNeedsConfirmation`) a `CleartextAcknowledgement` built from
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
   addresses are `InvalidAddress`. One trailing dot is stripped (a single label keeps it: `ai.` is an absolute name, public); a second is an empty label.
7. **`http` reached by fallback always asks, even for a private host** (owner decision, 2026-10-14). When `https`
   failed and the app chose `http` itself (`resolvedByFallback`, carried on the address as `viaFallback`), the one-time
   acknowledgement is required before any password or token is sent, on the home network too. An `http://` the user
   typed for a private host keeps the no-prompt behaviour. The rule is one term in
   `ServerAddress.cleartextNeedsConfirmation`, so it can be dropped by deleting that term. Without it, someone on the
   same Wi-Fi could reset connections to port 443 and push the app onto `http` without a prompt.

## Accepted risks (for the owner)

If you type a bare name such as `http://jellyfin:8096`, the app treats it as your home network and does not warn you.
Normally that is true. But a phone or TV can be set up, or a hostile network can arrange, so that `jellyfin` quietly
means a server on the internet. Your password and login token would then travel unencrypted to it, with no warning.
The app cannot check, because looking the name up would trust the same network that might be lying, and would fail
offline. Angua (Security) judged this acceptable on 2026-10-13, and the owner accepted it on 2026-10-14. The risk
covers an `http://` the user typed for a bare name, and for a name ending `.local`, which can be spoofed on the local
network. `https`, any name with a dot (other than `.local` or `.home.arpa`), and any `http` the app reached by falling
back from `https` still ask first.

One more thing to know: a typed `http://` to a private IP address, such as `http://192.168.1.10:8096`, never asks. Your
password and login token then travel where anyone else on the same Wi-Fi can read them. That is the normal state of a
home server without HTTPS, and it is accepted for the same reason.

What the fallback prompt closes: someone on the same Wi-Fi can no longer force the app from `https` onto `http` without
being noticed, because that downgrade now always asks. `.local` names can also be spoofed on the local network, which
is why a name ending `.local` typed with `http://` is still a trust decision the user makes.

## Consequences

- The most common LAN entry (`host:8096` over plain HTTP) works without a TLS error, and a broken or hostile
  handshake never turns into a cleartext request.
- Two TLS stacks, two sets of markers: a new Android version or JDK can word the plaintext case differently. The
  marker tests on the emulator and the JVM are the check, and an unrecognised wording fails safe (a TLS error, no
  fallback). On Android 7.1 the table is filled and tested on the emulator; a Fire TV on Android 10 or later still
  needs a device check of the Conscrypt engine wording, and until then a plain-HTTP LAN server there reports a TLS
  error instead of falling back.
- ADR-0006's note that the fallback triggers "only on connection refused" is amended to match, and so is S0.3.1 in the
  backlog.
