# channelmarks

The StationBreak channel marks. The generator is in `v2/` (see `v2/README.md`); the rules the marks follow
are in `docs/design/channel-identity.md`, and provenance and the app side in `core/ui/CHANNELMARKS.md`.

`fonts/` holds the typefaces the generator shares; `v2/fonts/` holds the seven added for the current marks. They
are used only to draw text, which is outlined into the masters: the app ships no font file from here. Each has
its licence in the `licenses/` folder beside it:

- `fonts/`: Abril Fatface, Alfa Slab One, Anton, Audiowide, Baloo 2, Barlow Condensed, Bebas Neue, Boogaloo,
  Bungee, Chewy, Cinzel, Creepster, Fredoka, Lilita One, Luckiest Guy, Orbitron, Oswald, Pacifico,
  Playfair Display, Righteous, Sniglet, Special Elite, Teko, Titan One, Ultra, Yeseva One and Zilla Slab.
  All SIL OFL 1.1 except Chewy, Luckiest Guy, Ultra and Special Elite (Apache 2.0).
- `v2/fonts/`: Corben, Limelight, Rammetto One, Rye, Shrikhand and Stardos Stencil (SIL OFL 1.1) and Yellowtail
  (Apache 2.0).
