# Remote keys: Back and Select

Back goes up one level. From the only screen on the stack, Back leaves the app. Nothing asks "are you sure?" first. The channel builder does ask before it throws away unsaved work.

Select is the remote's center button, Enter, and the numpad Enter. It presses whatever is focused. Live TV is the exception: center and Enter count, numpad Enter does not, and Back there runs when the key goes down rather than when it comes up.

From the picture, Back opens the guide. It does not leave. Back again, from the programme that is on now, opens the guide menu, and the next Back leaves the app. A menu opened with the Menu key closes and stays in the guide.

A banner, dialog, or sheet takes Back before the screen under it does. There is no long-press. Holding Select does not do a second thing.

The five charts below are the short version, one area each. The [full chart](back-select-flow.png) and the [state table](back-select-audit.md) are at the bottom.

## Live TV

```mermaid
%%{init: {"theme": "base", "themeVariables": {"fontSize": "22px"}}}%%
flowchart TD
  subgraph row1[" "]
    direction LR
    pictureBack["Picture"] -->|"Back"| guide["Guide"]
  end
  subgraph row2[" "]
    direction LR
    pictureSelect["Picture"] -->|"Select"| banner["Banner"]
  end
  subgraph row3[" "]
    direction LR
    bannerBack["Banner"] -->|"Back"| picture["Picture"]
  end
  subgraph row4[" "]
    direction LR
    bannerInfo["Banner"] -->|"Select Info"| info["Program info"]
  end
  subgraph row5[" "]
    direction LR
    infoBack["Program info"] -->|"Back"| bannerAgain["Banner"]
  end
  row1 ~~~ row2 ~~~ row3 ~~~ row4 ~~~ row5
```

![Live TV: Back opens the guide, Select opens the banner](live.png)

- Back from the picture or a pause opens the guide. It does not leave the app.
- Select on the picture opens the actions banner. While paused, Select plays.
- Back on the banner closes it. Back on program info returns to the banner. Back on a track picker returns to its chip.
- Select on the banner presses the focused button: Info, Last, Live, or a chip.
- Numpad Enter is not Select on this screen.

## Guide

```mermaid
%%{init: {"theme": "base", "themeVariables": {"fontSize": "22px"}}}%%
flowchart TD
  subgraph row1[" "]
    direction LR
    other["Another cell"] -->|"Back"| now["Cell on now"]
  end
  subgraph row2[" "]
    direction LR
    nowBack["Cell on now"] -->|"Back"| menuBack["Menu from Back"]
  end
  subgraph row3[" "]
    direction LR
    menuLeave["Menu from Back"] -->|"Back"| leave["Leaves the app"]
  end
  subgraph row4[" "]
    direction LR
    menuKey["Menu from Menu key"] -->|"Back"| grid["Back to the grid"]
  end
  subgraph row5[" "]
    direction LR
    nowSelect["Cell on now"] -->|"Select"| live["Live picture"]
  end
  row1 ~~~ row2 ~~~ row3 ~~~ row4 ~~~ row5
```

![Guide: Back walks to the playing cell, then the menu, then out](guide.png)

- Back from another cell jumps to the programme that is on now.
- Back from that cell opens the menu. Back from a menu that Back opened leaves the app.
- A menu opened with the Menu key closes on Back and stays in the guide.
- Select on a programme tunes that channel. Select on Settings, Channels, or Exit opens that screen, or leaves.
- While the guide is still loading, Back leaves the app.

## Channels and builder

```mermaid
%%{init: {"theme": "base", "themeVariables": {"fontSize": "22px"}}}%%
flowchart TD
  subgraph row1[" "]
    direction LR
    listBack["Channel list"] -->|"Back"| guide["Guide"]
  end
  subgraph row2[" "]
    direction LR
    listSelect["Channel list"] -->|"Select a row"| actions["Actions"]
  end
  subgraph row3[" "]
    direction LR
    actionsBack["Actions"] -->|"Back"| list["Channel list"]
  end
  subgraph row4[" "]
    direction LR
    builderBack["Builder, unsaved"] -->|"Back"| confirm["Leave?"]
  end
  subgraph row5[" "]
    direction LR
    typingBack["Typing"] -->|"Back"| builder["Builder row"]
  end
  row1 ~~~ row2 ~~~ row3 ~~~ row4 ~~~ row5
```

![Channels: Back closes a panel or the builder before it returns to the guide](channels.png)

- Back from the channel list returns to the guide.
- Select on a row opens its actions. Select on New opens the builder.
- Back closes a panel, the number picker, or a question before it leaves.
- A builder with unsaved work asks before it closes. Back while typing only puts the keyboard away.
- The properties sheet uses this same path. Watch tunes that channel and closes the sheet.

## Settings

```mermaid
%%{init: {"theme": "base", "themeVariables": {"fontSize": "22px"}}}%%
flowchart TD
  subgraph row1[" "]
    direction LR
    sheetBack["Settings"] -->|"Back"| guide["Guide"]
  end
  subgraph row2[" "]
    direction LR
    sheetPin["Settings"] -->|"Select"| pin["PIN pad"]
  end
  subgraph row3[" "]
    direction LR
    pinBack["PIN pad"] -->|"Back"| sheet["Settings"]
  end
  subgraph row4[" "]
    direction LR
    askBack["Sign out?"] -->|"Back or Stay"| stay["Settings"]
  end
  subgraph row5[" "]
    direction LR
    askGo["Sign out?"] -->|"Select Sign out"| server["Server screen"]
  end
  row1 ~~~ row2 ~~~ row3 ~~~ row4 ~~~ row5
```

![Settings: Back closes the sheet, or the screen that was opened from it](settings.png)

- Back closes the sheet and returns to the guide.
- Select on Kids Mode or Change PIN opens the PIN pad. Back there cancels it.
- Select on Hours, Libraries, or Pro opens that screen. Back returns to the sheet.
- Sign out asks first. Back, or Stay, keeps the sign-in. Sign out goes to the server screen.
- While sign-out is running, Back and Select do nothing.

## Onboarding

```mermaid
%%{init: {"theme": "base", "themeVariables": {"fontSize": "22px"}}}%%
flowchart TD
  subgraph row1[" "]
    direction LR
    welcomeBack["Welcome"] -->|"Back"| leave["Leaves the app"]
  end
  subgraph row2[" "]
    direction LR
    welcomeGo["Welcome"] -->|"Select"| server["Server"]
  end
  subgraph row3[" "]
    direction LR
    qcBack["Quick Connect"] -->|"Back"| signin["Sign in"]
  end
  subgraph row4[" "]
    direction LR
    signedBack["Signed in as"] -->|"Back"| leaveAgain["Leaves the app"]
  end
  subgraph row5[" "]
    direction LR
    cheerGo["Cheer"] -->|"Any key"| live["Live TV"]
  end
  row1 ~~~ row2 ~~~ row3 ~~~ row4 ~~~ row5
```

![Onboarding: Back goes to the previous step, or leaves when that step is the only screen](onboarding.png)

- Welcome's Back leaves the app. Get started opens the server screen.
- Back from the server returns to Welcome when Welcome is underneath. When the server screen is the only one, Back leaves the app.
- Back from sign-in returns to the server. During Quick Connect, Back only cancels Quick Connect.
- Back from the library picker opens Signed in as. Back there leaves the app. Continue returns to the picker.
- Back during first sync leaves the app. On the cheer, any key continues to live TV.

## Full chart

The phone charts drop conditions. The full map is one chart, and the audit is the table with file and line for every state.

- [Full chart, PNG](back-select-flow.png)
- [Full chart, SVG](back-select-flow.svg)
- [Full chart source](back-select-flow.mmd)
- [Audit: table and findings](back-select-audit.md)

![Full Back and Select chart](back-select-flow.png)
