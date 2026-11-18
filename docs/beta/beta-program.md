# Beta program (F3.7)

Status: **Plan for the owner (#31, #97).** Nothing here has been posted, sent or set up. It is the structure that makes
the Phase 3 gate measurable ([EPICS.md](../EPICS.md), E3):

> 5 to 10 outside beta testers recruited from r/jellyfin install the app, connect, and watch a channel without
> hand-holding. Each tester reaches first playback with zero support messages, or the friction they hit is logged and
> fixed. At least 3 distinct Fire TV models and 1 non-Amazon Android TV are represented among the testers.

The beta starts after the Phase 2 dogfood passes (owner, 2026-11-06: the listing waits for 14 days of use **and**
outside testers). Decisions only the owner can make are marked **Owner**.

## How testers get the app

| Testers on | Channel | Why |
| --- | --- | --- |
| Fire TV | Amazon **Live App Testing** (LAT) | The tester installs from the Appstore on their own TV, with no ADB, developer options or sideloading. That is the same path a real customer takes, so "unassisted" means the same thing. Free, up to 1,500 testers, invited by the email of their Amazon account. A LAT build can later be promoted to the live listing ([LAT FAQ](https://developer.amazon.com/docs/app-testing/live-app-testing-faq.html)) |
| A non-Amazon Android TV | **Owner** | LAT serves Amazon devices only. The options are a GitHub pre-release of the `github` flavor (the release workflow already builds and signs it, but GitHub Releases, #35, is planned after the listing), or the CI APK guide ([install-ci-apk.md](../testing/install-ci-apk.md)), which needs ADB and is not "unassisted". Recommended: a GitHub pre-release, so the gate's Android TV tester installs a normal APK |

LAT needs the app created in the Developer Console, with the fields drafted in
[the listing draft](../store/amazon-appstore-listing.md). On Fire OS 7 and later, testers can also manage the test in
Amazon's **Appstore Beta Hub** app. On Fire OS 6 they use the invitation email.

The beta build is a release build (the `amazon` flavor from a tag), not a CI debug build: release builds have the free
tier's gates closed (#808), as customers will see them.

## Recruiting

Target: 8 to 10 testers, so that 5 complete even with dropouts. Recruit for the device spread, not first come:
at least 3 different Fire TV models, older ones included (Fire OS 6 is the floor), and one non-Amazon Android TV.

Testers need their own Jellyfin 12.0 or later server with media on it. A tester on a Roku or another platform can't
take part: the app runs only on Fire TV and Android TV.

### Recruitment post

For r/jellyfin. **Owner**: check the subreddit's current rules on self-promotion and beta posts first, and post
from the owner's account.

```text
Title: Looking for 8-10 beta testers: a Fire TV app that plays your Jellyfin library as live TV channels

I'm building a free, open-source Fire TV app that turns your Jellyfin library into live TV: your shows and films play on a schedule across themed channels, with a program guide, and you flip channels like cable. It's nearly ready for the Amazon Appstore and I need a few people to try it before it goes public.

You'd need:
- Jellyfin 12.0 or later, with your own media on it
- A Fire TV device (any model on Fire OS 6 or later), or an Android TV device
- About 30 minutes to set it up and watch, and a few days of normal use if you can

What I'm testing: whether someone who has never seen the app can install it, connect to their server and get a channel playing with no help from me. So I won't explain anything up front, and anything that confuses you is exactly what I want to hear about.

The app talks only to your server. No ads, no analytics, no account.

If you're in, reply or message me with your TV model (Settings > My Fire TV > About) and your Jellyfin version. Don't send your server address or anything else about your server. Fire TV testers will get an invitation from Amazon at the email of their Amazon account, so I'll ask you for that address privately.

Source code: https://github.com/StationBreak/stationbreaktv
```

## What testers are told

The invitation carries only how to install and how to report, nothing about how the app works: the gate measures
whether the app explains itself. Draft for the invitation message:

```text
Thanks for testing.

1. Install: open the invitation email from Amazon on your phone or computer and accept it with the Amazon account your Fire TV uses. The app then appears on the TV under Your Apps & Channels, or in the Appstore Beta Hub app on newer Fire TVs. If it doesn't show up, go to Settings > My Account > Sync Amazon Content on the TV.
   Android TV: install the APK from the link I sent you.
2. Open the app and get a channel playing. Please try it without asking me first: if you get stuck, write down where and what you expected, then ask.
3. Report: open a "Beta report" issue at https://github.com/StationBreak/stationbreaktv/issues/new/choose, or email me if you'd rather not use GitHub. Please report even if everything went fine.

Please don't post your server address, username, password or screenshots that show them.
```

## Feedback form

A GitHub issue form, so reports are public, searchable and labelled. It lives in `.github/ISSUE_TEMPLATE/`, which is
held for the owner (ADR-0012), so this PR only drafts it. **Owner**: add it as
`.github/ISSUE_TEMPLATE/beta-report.yml` and create the `beta` label.

```yaml
name: Beta report
description: Tell us how installing, connecting and watching went.
title: "Beta: "
labels: ["beta"]
body:
  - type: markdown
    attributes:
      value: |
        Thanks for testing. Please don't include your server address, username, password, or screenshots that show them.
  - type: input
    id: device
    attributes:
      label: TV model and software version
      description: Settings > My Fire TV > About (or the Android TV equivalent)
      placeholder: "Fire TV Stick 4K, Fire OS 7.6.x"
    validations:
      required: true
  - type: input
    id: jellyfin
    attributes:
      label: Jellyfin server version
      placeholder: "12.0.3"
    validations:
      required: true
  - type: dropdown
    id: connection
    attributes:
      label: How the TV reaches your server
      options:
        - http on my home network
        - https with a public certificate (for example Let's Encrypt)
        - https with a self-signed certificate
        - Behind a reverse proxy at a path (for example /jellyfin)
        - Other or not sure
    validations:
      required: true
  - type: dropdown
    id: outcome
    attributes:
      label: Did a channel start playing?
      options:
        - Yes, without any help
        - Yes, after I asked for help or looked something up
        - "No"
    validations:
      required: true
  - type: input
    id: minutes
    attributes:
      label: About how many minutes from opening the app to the first channel playing?
  - type: textarea
    id: stuck
    attributes:
      label: Where did you hesitate or get stuck?
      description: The screen, what you expected, and what happened. "Nothing" is a useful answer.
    validations:
      required: true
  - type: textarea
    id: other
    attributes:
      label: Anything else (bugs, things you liked or didn't)
```

## Tracker

One row per tester, kept **private** by the owner (a local spreadsheet or the owner's notes), never in this repository:
it holds the tester's contact and invitation email. Columns:

| Column | Values |
| --- | --- |
| Tester | A label such as T01. The name and email stay in the private copy only |
| Device | Model and OS version |
| Jellyfin | Server version |
| Connection | As in the form |
| Channel | LAT or Android TV APK |
| Invited / installed / reported | Dates |
| Outcome | **Unassisted**, **Assisted** (any support message before first playback), **Blocked** |
| Friction | Links to the issues it raised |
| Fixed | Yes / no, with the fixing PR |

## Measuring the gate

- **Unassisted** counts toward the gate. **Assisted** counts only once each friction point it hit has an issue that is
  fixed (the gate's "or the friction they hit is logged and fixed"). **Blocked** doesn't count until fixed and
  retested.
- The gate passes with at least 5 countable testers and the device spread above.
- Every friction point becomes a GitHub issue labelled `beta` and `release:fire-v1`, even when the tester got past it.
- At the end, the owner records the result in the public repository the way Phase 0 was recorded
  ([docs/gates/phase-0.md](../gates/phase-0.md)): counts per outcome, the device models, and the issues, with tester
  labels only.

## Order of work

1. Phase 2 dogfood passes (earliest 2026-11-25).
2. **Owner:** create the app in the Developer Console and a live app test with a tagged release build; decide the
   Android TV channel; add the issue form and label.
3. **Owner:** post the recruitment message; pick testers for the device spread; send invitations.
4. Testers report; friction becomes issues; fixes ship as new LAT builds.
5. **Owner:** record the gate result.
