# StationBreak

![StationBreak: a TV set showing a pause symbol, and "We'll be right back"](art/placeholder/repo-banner.png)

Turn your Jellyfin library into always-on, flip-through broadcast channels on Fire TV and Android TV.

> **Status:** the Phase 0 (Foundations) gate passed on 2026-10-18. Phase 1 (live channels) is built: the scheduler,
> the starter lineup, the player, offset playback, channel flipping and playback reporting. Its gate is still open on
> the Firestick flip speed ([#392](https://github.com/StationBreak/stationbreaktv/issues/392)). Phase 2 work is under
> way: the guide and banner, kid channels, pause and seek, channel management and the channel builder are built.
> Device checks for some of these are still pending.

## Docs

| Doc | What's in it |
| --- | --- |
| [Spec](docs/SPEC.md) | Vision, goals, architecture, MVP scope (source of truth) |
| [Epics](docs/EPICS.md) | One epic per roadmap phase, with its gate as acceptance criteria |
| [Backlog](docs/BACKLOG.md) | Features and user stories with testable acceptance criteria |
| [Roadmap](docs/ROADMAP.md) | Phase sequencing, dependencies, spikes, risks |
| [Modules](docs/MODULES.md) | Gradle module layout |
| [Rules format](docs/rules-format.md) | The channel rules file: schema, examples and the exact ordering algorithms, for other clients |
| [Assumptions & questions](docs/ASSUMPTIONS.md) | What's been assumed, decided, and still open |
| [ADRs](docs/adr/) | Architecture decision records |
| [Playback matrix](docs/compatibility/playback-matrix.md) | What the player did with a spread of files on real Fire TV devices, measured on device |

The playback matrix is produced by the tooling in `scripts/playback-matrix/`.

## Assets

The TV banner, the launcher icons and the banner at the top of this README are **placeholders**, not final brand art.
They come from the "StationBreak Logo" design canvas: the sign-off test card is the app icon and, widened to 16:9, the
TV banner; the TV mark and wordmark are the README banner. The SVG sources are in `art/placeholder/`, and
`scripts/render-placeholder-art.sh <font-dir>` renders every PNG at its exact size. The text is set in Archivo Black
and Space Mono Bold (both SIL OFL 1.1, from `github.com/google/fonts`). Both fonts are bundled in `core:ui` for the live channel's
on-air look; their licences are in `core/ui/src/main/assets/licenses/` and their provenance is in
`core/ui/FONTS.md`.

## Third-party software

The player and its captions use [AndroidX Media3](https://github.com/androidx/media) (ExoPlayer, the HLS module, the
OkHttp data source and the UI module's `SubtitleView`), which is licensed under the Apache License 2.0 (the text is at
<https://www.apache.org/licenses/LICENSE-2.0>). Every other library the app ships is checked by Licensee against the
allowlist in `build-logic` ([ADR-0003](docs/adr/0003-licensing-and-contributions.md)).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Contributions require a DCO sign-off (`git commit -s`). Everyone taking
part follows the [Code of Conduct](CODE_OF_CONDUCT.md). Report security vulnerabilities privately as described in
[SECURITY.md](SECURITY.md).

## License

GPL-3.0-or-later with an app-store additional permission: see [LICENSE](LICENSE) and
[ADR-0003](docs/adr/0003-licensing-and-contributions.md).
