# Certificates and http

## Plain http at home

On your home network, `http://` works without a prompt. That covers private addresses (such as `192.168.x.x`,
`10.x.x.x` and `172.16.x.x` to `172.31.x.x`), names ending in `.local` or `.home.arpa`, and single names with no dots.

The app asks **Connect without encryption?** before using http:

- to any other address, including a public one and Tailscale's `100.x.x.x` addresses;
- whenever you typed no `http://` or `https://`, https failed, and the app fell back to http, even at home.

No password or sign-in token is sent until you choose **Connect anyway**. The home screen then shows **Not encrypted
(http)**.

## https with a public certificate

A certificate from a public authority works with no extra steps. That includes Let's Encrypt on older Fire TV
models, whose own list of authorities is out of date: the app carries Let's Encrypt's current roots itself.

## Self-signed certificates

If the server uses a certificate it signed itself, the app shows **Trust this server's certificate?** with the
certificate's **SPKI SHA-256 fingerprint**: 64 hexadecimal digits in groups of four. **Don't trust** is selected first,
and Back means don't trust. Choose **Trust this certificate** only if the fingerprint matches your server's.

The fingerprint is of the certificate's public key, so it won't match the fingerprint a browser shows. To print it the
way the TV shows it, from a computer that can reach the server:

```sh
HOST=jellyfin.local; PORT=8920
openssl s_client -connect "$HOST:$PORT" -servername "$HOST" </dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -r | cut -d' ' -f1 | tr a-f A-F | fold -w4 | paste -sd' '
```

Once trusted, the TV accepts that certificate for that server and no other. If the server gets a new certificate
with a new key, the app says **This server's certificate has changed** and asks again, and you sign in again. If you
didn't change the certificate, don't trust the new one: someone may be intercepting the connection.

An expired or not-yet-valid self-signed certificate gets the same question. Check the TV's date and time first.

## What doesn't work

- **Your own certificate authority.** A certificate signed by a private authority (one you made, or a router's or
  NAS's) is refused with "This TV doesn't trust the server's security certificate". The app doesn't use authorities
  installed on the TV, and it only offers to trust a certificate that signed itself. Use a self-signed certificate,
  a public one, or plain http at home instead.
- **A public certificate that fails** (expired, for a different name, or replaced) stops with a message saying which.
  Fix it on the server; there is no option to accept it anyway.
