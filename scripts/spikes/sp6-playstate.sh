#!/usr/bin/env bash
# Spike SP-6: can the app report playback to Jellyfin (dashboard shows the TV as playing) without resume-point
# noise, and mark an episode played only when the app says so (F1.5)?
#
# Usage:  scripts/spikes/sp6-playstate.sh <jellyfin-image> <workdir>
#   e.g.  scripts/spikes/sp6-playstate.sh ghcr.io/jellyfin/jellyfin@sha256:... /tmp/sp6-work
#
# Proposed reporting under test: POST /Sessions/Playing (ItemId + PositionTicks) at tune; positionless
# POST /Sessions/Playing/Progress every 30 s; POST /Sessions/Playing/Stopped with Failed=true on flip/exit;
# POST /UserPlayedItems/{id} at >= 90% watched.
#
# Needs docker, ffmpeg, curl, jq. Starts ONE throwaway container (sp6-<pid>) on 127.0.0.1 only, with a throwaway
# admin whose password is generated here and never printed, runs every case, writes a TSV result table to
# <workdir>/results.tsv and removes the container and its volumes. It never touches any other container and needs
# no credentials. Reporting uses the authenticated session's own device (DeviceId sp6); the dashboard view is GET /Sessions.
# Env: SP6_PORT (default 18097), SP6_IDLE=0 to skip the 12-minute idle case (g).
set -uo pipefail

IMAGE="${1:?usage: $0 <jellyfin-image> <workdir>}"
WORK="${2:?usage: $0 <jellyfin-image> <workdir>}"
PORT="${SP6_PORT:-18097}"
NAME="sp6-$$"
BASE="http://127.0.0.1:${PORT}"
MEDIA="$WORK/media"
PASS="$(head -c 18 /dev/urandom | base64 | tr -d '+/=')"
TOK=""
RESULTS="$WORK/results.tsv"

for tool in docker ffmpeg curl jq; do command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 2; }; done
mkdir -p "$WORK"; : > "$RESULTS"

cleanup() {
  docker rm -f -v "$NAME" >/dev/null 2>&1
  docker volume rm "${NAME}-config" "${NAME}-cache" >/dev/null 2>&1
}
trap cleanup EXIT

AUTHH='Authorization: MediaBrowser Client="sp6", Device="sp6", DeviceId="sp6", Version="1.0"'
api() { # method path [json-body]
  local m="$1" p="$2" b="${3:-}"
  if [ -n "$b" ]; then
    curl -sS -X "$m" "$BASE$p" -H "Authorization: MediaBrowser Token=\"$TOK\"" -H 'Content-Type: application/json' -d "$b"
  else
    curl -sS -X "$m" "$BASE$p" -H "Authorization: MediaBrowser Token=\"$TOK\"" -H 'Content-Length: 0'
  fi
}
settle() {
  sleep 3
  local i
  for i in $(seq 1 90); do
    api GET /ScheduledTasks | jq -e '[.[] | select(.State != "Idle")] | length == 0' >/dev/null && break
    sleep 2
  done
  sleep 6
}
rec() { printf '%s\t%s\t%s\n' "$1" "$2" "$3" | tee -a "$RESULTS"; }

# ---- media: 7-minute synthetic episodes (> the 300 s MinResumeDuration) --------------------------------
EPS=8
rm -rf "$MEDIA"
mkdir -p "$MEDIA/shows/Test Series (2020)/Season 01"
for n in $(seq 1 $EPS); do
  ffmpeg -loglevel error -y -f lavfi -i color=c=black:s=64x64:r=1:d=420 -f lavfi -i anullsrc=r=8000:cl=mono \
    -t 420 -c:v libx264 -preset ultrafast -g 30 -c:a aac -b:a 16k -shortest \
    "$MEDIA/shows/Test Series (2020)/Season 01/Test Series (2020) S01E0$n.mp4" || exit 1
done
chmod -R a+rX "$MEDIA"

# ---- container -----------------------------------------------------------------------------------
docker volume create "${NAME}-config" >/dev/null
docker volume create "${NAME}-cache" >/dev/null
nice docker run -d --name "$NAME" --cpus 2 --memory 1.5g -p "127.0.0.1:${PORT}:8096" \
  -v "${NAME}-config:/config" -v "${NAME}-cache:/cache" -v "$MEDIA:/media" "$IMAGE" >/dev/null || exit 1
for _ in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/Startup/Configuration")" = 200 ] && break
  sleep 3
done
VER="$(curl -sf "$BASE/System/Info/Public" | jq -r '.Version // .version')"
rec "# image" "$IMAGE" "server=$VER"

curl -sS -X POST "$BASE/Startup/Configuration" -H 'Content-Type: application/json' \
  -d '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}' >/dev/null
curl -sS "$BASE/Startup/User" >/dev/null
curl -sS -X POST "$BASE/Startup/User" -H 'Content-Type: application/json' \
  -d "{\"Name\":\"sp6admin\",\"Password\":\"$PASS\"}" >/dev/null
curl -sS -X POST "$BASE/Startup/Complete" -H 'Content-Length: 0' >/dev/null
AUTH="$(curl -sS -X POST "$BASE/Users/AuthenticateByName" -H "$AUTHH" -H 'Content-Type: application/json' \
  -d "{\"Username\":\"sp6admin\",\"Pw\":\"$PASS\"}")"
TOK="$(jq -r .AccessToken <<<"$AUTH")"
UID_="$(jq -r .User.Id <<<"$AUTH")"
if [ -z "$TOK" ] || [ "$TOK" = null ]; then echo "login failed" >&2; exit 1; fi

types='[{"Type":"Series","MetadataFetchers":[],"ImageFetchers":[]},{"Type":"Season","MetadataFetchers":[],"ImageFetchers":[]},{"Type":"Episode","MetadataFetchers":[],"ImageFetchers":[]}]'
api POST "/Library/VirtualFolders?name=Shows&collectionType=tvshows&refreshLibrary=false" \
  "{\"LibraryOptions\":{\"EnableInternetProviders\":false,\"PathInfos\":[{\"Path\":\"/media/shows\"}],\"TypeOptions\":$types}}" >/dev/null
api POST /Library/Refresh >/dev/null; settle

# episodes in file-name order: E[1]..E[8]
mapfile -t IDS < <(api GET "/Items?Recursive=true&IncludeItemTypes=Episode&SortBy=SortName&userId=$UID_" | jq -r '.Items[]?.Id')
E=("")
for n in $(seq 1 $EPS); do E[n]="${IDS[n-1]:-}"; [ -n "${E[n]}" ] || { echo "seeding failed (episode $n)" >&2; exit 1; }; done
SERIES=$(api GET "/Items?Recursive=true&IncludeItemTypes=Series" | jq -r '.Items[0].Id')
RT=$(api GET "/Items/${E[1]}?userId=$UID_" | jq -r .RunTimeTicks)
CFG=$(api GET /System/Configuration | jq -c '{MinResumePct,MaxResumePct,MinResumeDurationSeconds}')
rec "# setup" "runtime=$((RT / 10000000)) s ($RT ticks), episodes=$EPS" "server resume config=$CFG"

# ---- helpers -------------------------------------------------------------------------------------
pct() { echo $((RT * $1 / 100)); }   # percent -> ticks
play() { api POST /Sessions/Playing "{\"ItemId\":\"$1\",\"MediaSourceId\":\"$1\",\"PositionTicks\":$2,\"CanSeek\":true,\"PlayMethod\":\"DirectPlay\",\"IsPaused\":false}"; }
prog0() { api POST /Sessions/Playing/Progress "{\"ItemId\":\"$1\",\"MediaSourceId\":\"$1\",\"CanSeek\":true,\"PlayMethod\":\"DirectPlay\",\"IsPaused\":${2:-false}}"; }
progN() { api POST /Sessions/Playing/Progress "{\"ItemId\":\"$1\",\"MediaSourceId\":\"$1\",\"PositionTicks\":$2,\"CanSeek\":true,\"PlayMethod\":\"DirectPlay\",\"IsPaused\":false}"; }
stop() { # id positionOrEmpty failed(true|false)
  local pos=""; [ -n "$2" ] && pos=",\"PositionTicks\":$2"
  api POST /Sessions/Playing/Stopped "{\"ItemId\":\"$1\",\"MediaSourceId\":\"$1\"$pos,\"Failed\":$3}"
}
ud() { api GET "/Items/$1?userId=$UID_" | jq -c '.UserData | {Played,PlaybackPositionTicks,PlayCount,LastPlayedDate}'; }
inresume() { api GET "/UserItems/Resume?userId=$UID_&MediaTypes=Video" | jq -r '.Items[]?.Id' | grep -qx "$1" && echo yes || echo no; }
sess() { # our device's session: now playing present, reported position, paused
  api GET /Sessions | jq -c --arg d "${DEV:-sp6}" '[.[] | select(.DeviceId==$d)][0] | {np:(.NowPlayingItem != null), pos:.PlayState.PositionTicks, paused:.PlayState.IsPaused}'
}
actlog() { api GET "/System/ActivityLog/Entries?limit=200" | jq -c '[.Items[]?.Type] | group_by(.) | map({(.[0]): length}) | add'; }
report() { rec "$1 userdata after" "$(ud "$2")" "in Resume=$(inresume "$2")"; }
sequence() { # case id pct : Playing, positionless Progress every 30 s for 120 s sampling Sessions every 10 s, paused Progress, Stopped Failed
  local c="$1" id="$2" pc="$3" t line=""
  play "$id" "$(pct "$pc")"; sleep 1
  rec "$c session right after Playing at $pc%" "$(sess)" ""
  for t in 10 20 30 40 50 60 70 80 90 100 110 120; do
    sleep 10
    if [ $((t % 30)) -eq 0 ]; then prog0 "$id" false; fi
    line="$line t=$t:$(sess | jq -c '[.np,.pos,.paused]') "
  done
  rec "$c Sessions samples [nowPlaying,pos,paused] every 10 s; positionless Progress at t=30,60,90,120" "$line" ""
  prog0 "$id" true; sleep 2
  rec "$c session after positionless Progress IsPaused=true" "$(sess)" ""
  rec "$c userdata while playing" "$(ud "$id")" "in Resume=$(inresume "$id")"
  stop "$id" "" true; sleep 2
  rec "$c session after Stopped Failed=true (no PositionTicks)" "$(sess)" ""
}

if [ -z "${SP6_ONLY:-}" ]; then
rec "# activity log types at start" "$(actlog)" ""

# ---- a. unwatched episode ---------------------------------------------------------------------------
id="${E[1]}"
rec "a before" "$(ud "$id")" "in Resume=$(inresume "$id")"
sequence a "$id" 40
report a "$id"
rec "a activity log types after" "$(actlog)" ""

# ---- b. existing resume point (normal Playing + Stopped at 50%), then the Failed sequence at 20% ------------
id="${E[2]}"
play "$id" 0; sleep 1; stop "$id" "$(pct 50)" false; sleep 2
rec "b before (normal Playing + Stopped at 50%)" "$(ud "$id")" "in Resume=$(inresume "$id")"
sequence b "$id" 20
report b "$id"

# ---- c. already-played episode -----------------------------------------------------------------------
id="${E[3]}"
api POST "/UserPlayedItems/$id?userId=$UID_" >/dev/null; sleep 1
rec "c before (marked played)" "$(ud "$id")" "in Resume=$(inresume "$id")"
sequence c "$id" 40
report c "$id"

# ---- d. contrast with a positioned Progress (positionless behaviour is sampled inside every sequence) ---------
id="${E[5]}"
rec "d before (contrast: positioned Progress at 45%, then Stopped Failed=true)" "$(ud "$id")" "in Resume=$(inresume "$id")"
play "$id" "$(pct 40)"; sleep 1; progN "$id" "$(pct 45)"; sleep 2
rec "d session after positioned Progress at 45%" "$(sess)" ""
stop "$id" "" true; sleep 2
rec "d userdata after positioned Progress + Stopped Failed=true" "$(ud "$id")" "in Resume=$(inresume "$id")"

# ---- e. mark played ----------------------------------------------------------------------------------
id="${E[4]}"
rec "e before" "$(ud "$id")" "in Resume=$(inresume "$id")"
api POST "/UserPlayedItems/$id?userId=$UID_" >/dev/null; sleep 1
rec "e after POST /UserPlayedItems" "$(ud "$id")" "in Resume=$(inresume "$id")"
nu=$(api GET "/Shows/NextUp?userId=$UID_&seriesId=$SERIES" | jq -r '[.Items[]?.IndexNumber] | map(tostring) | join(",")')
rec "e NextUp episode numbers for the series" "$nu" "(E3 and E4 played, E2 has a resume point, E5 contrast data)"

# ---- f. controls --------------------------------------------------------------------------------------
id="${E[8]}"
rec "f1 before" "$(ud "$id")" "in Resume=$(inresume "$id")"
play "$id" "$(pct 40)"; sleep 1; stop "$id" "$(pct 40)" false; sleep 2
rec "f1 control: Stopped WITHOUT Failed at 40% -> userdata" "$(ud "$id")" "in Resume=$(inresume "$id")"
id="${E[6]}"
rec "f2 before" "$(ud "$id")" "in Resume=$(inresume "$id")"
play "$id" "$(pct 40)"; sleep 1; stop "$id" "" false; sleep 2
rec "f2 control: Stopped with no PositionTicks, no Failed -> userdata" "$(ud "$id")" "in Resume=$(inresume "$id")"
rec "# activity log types at end of a-f" "$(actlog)" ""

# ---- g. idle: Playing, then no check-ins for 12 minutes ------------------------------------------------
if [ "${SP6_IDLE:-1}" = 1 ]; then
  id="${E[7]}"
  rec "g before" "$(ud "$id")" "in Resume=$(inresume "$id")"
  play "$id" "$(pct 40)"
  line=""
  for m in $(seq 1 12); do
    sleep 60
    line="$line m$m:$(sess | jq -c '[.np,.pos,.paused]')/$(ud "$id" | jq -c '[.Played,.PlaybackPositionTicks,.PlayCount]') "
  done
  rec "g per-minute samples, no check-ins [session [nowPlaying,pos,paused] / userdata [played,posTicks,playCount]]" "$line" ""
  rec "g userdata after 12 idle minutes" "$(ud "$id")" "in Resume=$(inresume "$id")"
  rec "g activity log types after idle" "$(actlog)" ""
  docker logs "$NAME" 2>&1 | grep -i -E 'playback|stopp|idle' | tail -6 | while read -r l; do rec "g server log" "$l" ""; done
else
  rec "g idle" "skipped by SP6_IDLE=0" ""
fi
fi
# ======================= follow-up round: Playing at PositionTicks=0 ===========================================
login() { # deviceId -> token (one session per device, so the idle cases can run side by side)
  curl -sS -X POST "$BASE/Users/AuthenticateByName" \
    -H "Authorization: MediaBrowser Client=\"sp6\", Device=\"$1\", DeviceId=\"$1\", Version=\"1.0\"" \
    -H 'Content-Type: application/json' -d "{\"Username\":\"sp6admin\",\"Pw\":\"$PASS\"}" | jq -r .AccessToken
}
idlerun() { # name deviceId episodeId mode(idle|full|min): Playing at 0, then 12 minutes with no / full-field / minimal-field positionless check-ins every 30 s
  local name="$1" dev="$2" id="$3" mode="$4" start="${5:-0}" m line="" i n=0
  DEV="$dev"; TOK="$(login "$dev")"
  api POST /Sessions/Playing "{\"ItemId\":\"$id\",\"MediaSourceId\":\"$id\",\"PlaySessionId\":\"ps-$dev\",\"PositionTicks\":$start,\"CanSeek\":true,\"PlayMethod\":\"DirectPlay\",\"IsPaused\":false}"
  for m in $(seq 1 12); do
    for i in 1 2; do
      sleep 30
      n=$((n + 1))
      case "$mode" in
        three) [ "$n" -le 3 ] && prog0 "$id" false ;;   # 3 positionless check-ins, then silence (the realistic crash)
        full) prog0 "$id" false ;;
        min) api POST /Sessions/Playing/Progress "{\"ItemId\":\"$id\",\"MediaSourceId\":\"$id\",\"PlaySessionId\":\"ps-$dev\"}" ;;
      esac
    done
    line="$line m$m:$(sess | jq -c '[.np,.pos,.paused]')/$(ud "$id" | jq -c '[.Played,.PlaybackPositionTicks,.PlayCount]') "
  done
  printf '%s' "$line" > "$WORK/out-$name"
}

if [ "${SP6_FOLLOWUP:-1}" = 1 ] && [ "${SP6_ONLY:-}" != crash ]; then
  rec "# follow-up: Playing at PositionTicks=0" "" ""
  g2="${E[1]}"; g3="${E[2]}"; g4="${E[3]}"; hh="${E[4]}"; i1="${E[5]}"; i2="${E[6]}"
  if [ -z "${SP6_ONLY:-}" ]; then
    # a full run already used these episodes: pick the ones the first round left alone
    g2="${E[7]}"; g3="${E[2]}"; g4="${E[3]}"; hh="${E[8]}"; i1="${E[5]}"; i2="${E[6]}"
  fi
  play "$g3" 0; sleep 1; stop "$g3" "$(pct 50)" false; sleep 2     # g3 setup: normal stop at 50%
  api POST "/UserPlayedItems/$g4?userId=$UID_" >/dev/null; sleep 1  # g4 setup: already played
  rec "g2 before" "$(ud "$g2")" "in Resume=$(inresume "$g2")"
  rec "g3 before (50% resume point)" "$(ud "$g3")" "in Resume=$(inresume "$g3")"
  rec "g4 before (played)" "$(ud "$g4")" "in Resume=$(inresume "$g4")"
  rec "i1 before" "$(ud "$i1")" "in Resume=$(inresume "$i1")"
  rec "i2 before" "$(ud "$i2")" "in Resume=$(inresume "$i2")"
  idlerun g2 dev-g2 "$g2" idle & p1=$!
  idlerun g3 dev-g3 "$g3" idle & p2=$!
  idlerun g4 dev-g4 "$g4" idle & p3=$!
  idlerun i1 dev-i1 "$i1" full & p4=$!
  idlerun i2 dev-i2 "$i2" min & p5=$!
  # h (main device sp6): normal path with Playing at 0
  rec "h before" "$(ud "$hh")" "in Resume=$(inresume "$hh")"
  sequence h "$hh" 0
  report h "$hh"
  wait "$p1" "$p2" "$p3" "$p4" "$p5"
  rec "g2 per-minute samples, Playing at 0, NO check-ins [session [nowPlaying,pos,paused] / userdata [played,posTicks,playCount]]" "$(cat "$WORK/out-g2")" ""
  rec "g2 userdata after 12 min" "$(ud "$g2")" "in Resume=$(inresume "$g2")"
  rec "g3 per-minute samples, Playing at 0, NO check-ins" "$(cat "$WORK/out-g3")" ""
  rec "g3 userdata after 12 min" "$(ud "$g3")" "in Resume=$(inresume "$g3")"
  rec "g4 per-minute samples, Playing at 0, NO check-ins" "$(cat "$WORK/out-g4")" ""
  rec "g4 userdata after 12 min" "$(ud "$g4")" "in Resume=$(inresume "$g4")"
  rec "i1 per-minute samples, positionless Progress (CanSeek, PlayMethod=DirectPlay, IsPaused=false) every 30 s" "$(cat "$WORK/out-i1")" ""
  rec "i1 userdata after 12 min" "$(ud "$i1")" "in Resume=$(inresume "$i1")"
  rec "i2 per-minute samples, positionless Progress with only ItemId/MediaSourceId/PlaySessionId every 30 s" "$(cat "$WORK/out-i2")" ""
  rec "i2 userdata after 12 min" "$(ud "$i2")" "in Resume=$(inresume "$i2")"
  rec "# activity log types after follow-up" "$(actlog)" ""
fi

# ---- j. the realistic crash: Playing, a few positionless check-ins, then silence (SP6_ONLY=crash) -------------
if [ "${SP6_ONLY:-}" = crash ]; then
  rec "# crash cases: Playing, 3 positionless check-ins 30 s apart (or none), then silence" "" ""
  j1="${E[1]}"; j2="${E[2]}"; j3="${E[3]}"; j4="${E[4]}"
  play "$j2" 0; sleep 1; stop "$j2" "$(pct 50)" false; sleep 2     # j2 setup: normal stop at 50%
  play "$j3" 0; sleep 1; stop "$j3" "$(pct 50)" false; sleep 2     # j3 setup: same
  api POST "/UserPlayedItems/$j4?userId=$UID_" >/dev/null; sleep 1   # j4 setup: already played
  rec "j1 before" "$(ud "$j1")" "in Resume=$(inresume "$j1")"
  rec "j2 before (50% point)" "$(ud "$j2")" "in Resume=$(inresume "$j2")"
  rec "j3 before (50% point)" "$(ud "$j3")" "in Resume=$(inresume "$j3")"
  rec "j4 before (played)" "$(ud "$j4")" "in Resume=$(inresume "$j4")"
  idlerun j1 dev-j1 "$j1" three 0 & p1=$!
  idlerun j2 dev-j2 "$j2" three "$(pct 50)" & p2=$!
  idlerun j3 dev-j3 "$j3" idle "$(pct 50)" & p3=$!
  idlerun j4 dev-j4 "$j4" three 0 & p4=$!
  wait "$p1" "$p2" "$p3" "$p4"
  rec "j1 per-minute samples: Playing at 0, 3 positionless check-ins, silence [session / userdata [played,posTicks,playCount]]" "$(cat "$WORK/out-j1")" ""
  rec "j1 userdata after 12 min" "$(ud "$j1")" "in Resume=$(inresume "$j1")"
  rec "j2 per-minute samples: Playing at 50%, 3 positionless check-ins, silence" "$(cat "$WORK/out-j2")" ""
  rec "j2 userdata after 12 min" "$(ud "$j2")" "in Resume=$(inresume "$j2")"
  rec "j3 per-minute samples: Playing at 50%, NO check-ins" "$(cat "$WORK/out-j3")" ""
  rec "j3 userdata after 12 min" "$(ud "$j3")" "in Resume=$(inresume "$j3")"
  rec "j4 per-minute samples: Playing at 0, 3 positionless check-ins, silence (episode was played)" "$(cat "$WORK/out-j4")" ""
  rec "j4 userdata after 12 min" "$(ud "$j4")" "in Resume=$(inresume "$j4")"
  rec "# activity log types after crash cases" "$(actlog)" ""
fi


echo "# done: image=$IMAGE server=$VER"
