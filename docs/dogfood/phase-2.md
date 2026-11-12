# Phase 2 dogfood log

Issues found, and checks not yet made, during the Phase 2 dogfood (EPICS E2). One entry per item; say what was seen,
where (device, build) and what is still open.

## Clock

- **Started 2026-11-12.** The gate is 14 days of daily use replacing NostalgiaTV, so it can pass on 2026-11-25 at the
  earliest.
- **Scope while it runs.** Owner requests are polish and tightening of the existing UI, plus fixes for what this log
  records. Release work for the listing (issues labelled `release:fire-v1`) carries on alongside.

## Untested

- **Version switch on direct play.** The version picker (#630, #717) was checked on a Firestick only on items whose every
  tune was a transcode. Choosing another version while a version plays by direct play was never measured: its cost
  (a re-tune, as `SwitchCost` says) and the picture's behaviour are unconfirmed.
- **Warm-flip cost with a remembered non-default version.** A remembered version that is not the server's default makes a
  pre-resolved handle cold (`Prepared.serves` refuses it). On transcode-only items that cannot be told apart from the
  transcode's own cost, so the extra cost of the cold flip is unmeasured. Needs a two-version item that direct-plays.
