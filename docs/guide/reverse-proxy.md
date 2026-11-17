# Reverse proxies

Jellyfin often sits behind a reverse proxy such as Caddy, nginx or Traefik. The app works with one, including at a
path such as `https://media.example.com/jellyfin`. Jellyfin's own [networking
documentation](https://jellyfin.org/docs/general/post-install/networking/) covers the proxy configuration itself.

What the app expects:

- **Type the final address.** The app does not follow redirects. If the proxy redirects (for example from http to
  https, or from `/jellyfin` to `/jellyfin/`), the app stops and asks you to enter the address you use in a web
  browser. Copy it from the browser's address bar after the page has loaded, without the part after the path
  (`/web/...`).
- **Keep the path.** The app sends every request under the path you typed, so the proxy must pass the whole Jellyfin
  API through at that path, not only the web pages.
- **Changing the address may mean signing in again.** The sign-in is tied to the server and to the address (host,
  port and path) it was made at.
- **Certificates.** A proxy with a certificate from a public authority such as Let's Encrypt works with no extra
  steps. See [Certificates and http](certificates.md) for the other cases.
