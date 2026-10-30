#!/usr/bin/env bash
# Spike SP-7: pause and seek within a live-channel play on a Jellyfin HLS transcode, and paused playback reporting.
#
# Usage:  scripts/spikes/sp7-pause-seek.sh <jellyfin-image> <workdir>
#   e.g.  scripts/spikes/sp7-pause-seek.sh ghcr.io/jellyfin/jellyfin@sha256:... /tmp/sp7-work
#
# Q1  paused check-ins: Playing at a safe position (ADR-0018), POST /Sessions/Playing/Progress IsPaused=true with the
#     safe position every 30 s for SP7_PAUSE_S (180) s, resume (IsPaused=false), Stopped Failed=true. Three items:
#     unwatched, 50% resume point, played. Plus q1x: one paused check-in, then silence (does the idle stop fire?).
# Q2  idle transcode job while paused: force an HLS transcode, fetch segments 0-4, stop fetching. Two arms (paused
#     check-ins with the PlaySessionId every 30 s; none) x probe at 1, 5, 15 min x probe kind (next segment | playlist).
#     Run once on the server's default encoding config and once with "Throttle transcodes" on.
# Q3  seek on a transcode (and on an HLS direct stream): in-playlist (fetch the segment for the target) against a
#     restart (new PlaybackInfo with StartTimeTicks), 10 s back / 30 s forward / 120 s forward from 80 s, 3 repeats.
#
# Needs docker, ffmpeg, curl, jq. Starts ONE throwaway container (sb-sp7-<pid>) on 127.0.0.1 only, with a throwaway admin
# whose password is generated here and never printed, writes a TSV to <workdir>/results.tsv and removes the container,
# its volumes and the media. It never touches any other container and needs no credentials. Tokens and api_key values
# are never written to the results.
# Env: SP7_PORT (18107), SP7_ONLY=q1|q2|q3, SP7_FAST=1 (probes at 10/20/30 s, short pause; harness check only),
#      SP7_CFGS="default throttled", SP7_KEEP_MEDIA=1, SP7_CPUS (2) and SP7_MEM (5g) for the container,
#      SP7_SCENS (back10 fwd30 fwd120 fwd200) and SP7_REPS (3) for Q3.
# A full run takes about 47 minutes per image (Q2 waits 15 minutes twice). Q3 alone (SP7_ONLY=q3) takes about 10.
# `sp7-pause-seek.sh --summarize results.tsv` recomputes the Q3 median/max rows from a results file.
# Notes for re-running: Q2's A arm needs the PlaySessionId in the check-ins (that is what pings the transcode job);
# the HLS playlist is always full length and Jellyfin ignores/rejects StartTimeTicks on the HLS URL, so a "restart" seek
# is a new PlaybackInfo (new PlaySessionId) followed by a request for the segment at the target.
set -uo pipefail

# --- Q3 summary (also usable on its own: sp7-pause-seek.sh --summarize results.tsv) ---------------------------------
median_max() { # numbers on stdin -> "median=X max=Y n=N"
  sort -n | awk '{v[NR]=$1} END{if(NR==0){print "n=0";exit} m=(NR%2)?v[(NR+1)/2]:(v[NR/2]+v[NR/2+1])/2; printf "median=%.2f max=%.2f n=%d", m, v[NR], NR}'
}
summary_rows() { # tsv cfg mode : median / max of the Q3 rows, one TSV row per scenario and method
  local f="$1" cfg="$2" mode="$3" scen v
  for scen in back10 fwd30 fwd120 fwd200; do
    v="$(awk -F'\t' -v k="q3 [$cfg] $mode $scen inplaylist rep" 'index($1,k)==1{print $2}' "$f" | grep -oE '^http=200 time=[0-9.]+' | awk '{print substr($2,6)}' | median_max)"
    printf 'q3 SUMMARY [%s] %s %s inplaylist: segment request time (s)\t%s\t\n' "$cfg" "$mode" "$scen" "$v"
    v="$(awk -F'\t' -v k="q3 [$cfg] $mode $scen restart rep" 'index($1,k)==1{print $2}' "$f" | grep -oE '^total=[0-9.]+' | cut -d= -f2 | median_max)"
    printf 'q3 SUMMARY [%s] %s %s restart: total PlaybackInfo->first segment (s)\t%s\t\n' "$cfg" "$mode" "$scen" "$v"
    v="$(awk -F'\t' -v k="q3 [$cfg] $mode $scen restart rep" 'index($1,k)==1{print $2}' "$f" | grep -oE 'first segment [0-9.]+' | awk '{print $3}' | median_max)"
    printf 'q3 SUMMARY [%s] %s %s restart: first segment request only (s)\t%s\t\n' "$cfg" "$mode" "$scen" "$v"
  done
}
if [ "${1:-}" = --summarize ]; then
  f="${2:?usage: $0 --summarize results.tsv}"
  grep -oE '^q3 \[[a-z]+\] (transcode|directstream)' "$f" | sed -E 's/^q3 \[([a-z]+)\] /\1 /' | sort -u | while read -r cfg mode; do summary_rows "$f" "$cfg" "$mode"; done
  exit 0
fi

IMAGE="${1:?usage: $0 <jellyfin-image> <workdir>}"
WORK="${2:?usage: $0 <jellyfin-image> <workdir>}"
PORT="${SP7_PORT:-18107}"
NAME="sb-sp7-$$"
BASE="http://127.0.0.1:${PORT}"
MEDIA="$WORK/media"
PASS="$(head -c 18 /dev/urandom | base64 | tr -d '+/=')"
TOK=""
RESULTS="$WORK/results.tsv"
ONLY="${SP7_ONLY:-}"
CFGS="${SP7_CFGS:-default throttled}"
if [ "${SP7_FAST:-0}" = 1 ]; then
  PROBES="10 20 30"; PAUSE_S=60; SILENT_S=90
else
  PROBES="60 300 900"; PAUSE_S="${SP7_PAUSE_S:-180}"; SILENT_S=720
fi

for tool in docker ffmpeg curl jq awk; do command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 2; }; done
mkdir -p "$WORK"; : > "$RESULTS"

cleanup() {
  local p
  for p in $(jobs -p); do kill "$p" 2>/dev/null; done
  rec "# container state at exit (OOMKilled ExitCode Running)" "$(docker inspect -f '{{.State.OOMKilled}} {{.State.ExitCode}} {{.State.Running}}' "$NAME" 2>&1)" ""
  docker rm -f -v "$NAME" >/dev/null 2>&1
  docker volume rm "${NAME}-config" "${NAME}-cache" >/dev/null 2>&1
  [ "${SP7_KEEP_MEDIA:-0}" = 1 ] || rm -rf "$MEDIA"
  rm -f "$WORK"/body.* "$WORK"/out-*
}
trap cleanup EXIT

rec() { printf '%s\t%s\t%s\n' "$1" "$2" "${3:-}" | sed -E 's/(api_key|ApiKey|Token)=[^&" ]+/\1=REDACTED/g' | tee -a "$RESULTS"; }
now() { date +%s.%N; }
dt() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.2f", b-a}'; }

# ---- API helpers (token is an argument so concurrent cases keep their own device) -----------------------------
apit() { # token method path [json-body]
  local t="$1" m="$2" p="$3" b="${4:-}"
  if [ -n "$b" ]; then
    curl -sS --max-time 120 -X "$m" "$BASE$p" -H "Authorization: MediaBrowser Token=\"$t\"" -H 'Content-Type: application/json' -d "$b"
  else
    curl -sS --max-time 120 -X "$m" "$BASE$p" -H "Authorization: MediaBrowser Token=\"$t\"" -H 'Content-Length: 0'
  fi
}
api() { apit "$TOK" "$@"; }
login() { # deviceId -> token
  curl -sS -X POST "$BASE/Users/AuthenticateByName" \
    -H "Authorization: MediaBrowser Client=\"sp7\", Device=\"$1\", DeviceId=\"$1\", Version=\"1.0\"" \
    -H 'Content-Type: application/json' -d "{\"Username\":\"sp7admin\",\"Pw\":\"$PASS\"}" | jq -r .AccessToken
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
# timed GET: sets G_CODE G_TIME G_SIZE, body in $WORK/body.<pid>
G_CODE=""; G_TIME=""; G_SIZE=""; G_FILE=""
getb() { # token path-with-query
  local out f="$WORK/body.$BASHPID"
  G_FILE="$f"
  out="$(curl -s -o "$f" -w '%{http_code} %{time_total} %{size_download}' --max-time 240 \
    -H "Authorization: MediaBrowser Token=\"$1\"" "$BASE$2")"
  read -r G_CODE G_TIME G_SIZE <<<"$out"
}
body() { # id psid posTicks paused playMethod
  jq -nc --arg id "$1" --arg ps "$2" --argjson pos "$3" --argjson paused "$4" --arg m "$5" \
    '{ItemId:$id,MediaSourceId:$id,PlaySessionId:$ps,PositionTicks:$pos,CanSeek:true,PlayMethod:$m,IsPaused:$paused}'
}
stopbody() { jq -nc --arg id "$1" --arg ps "$2" --argjson pos "$3" '{ItemId:$id,MediaSourceId:$id,PlaySessionId:$ps,PositionTicks:$pos,Failed:true}'; }
ud() { api GET "/Items/$1?userId=$UID_" | jq -c '.UserData | {Played,PlaybackPositionTicks,PlayCount,LastPlayedDate,PlayedPercentage}'; }
inresume() { api GET "/UserItems/Resume?userId=$UID_&MediaTypes=Video" | jq -r '.Items[]?.Id' | grep -qx "$1" && echo yes || echo no; }
sess() { # token-free: our device's session (admin token sees all sessions)
  api GET /Sessions | jq -c --arg d "$1" '[.[] | select(.DeviceId==$d)][0] | {np:(.NowPlayingItem != null), pos:.PlayState.PositionTicks, paused:.PlayState.IsPaused, m:.PlayState.PlayMethod}'
}
sessc() { sess "$1" | jq -c '[.np,.pos,.paused]'; }

# ---- media ---------------------------------------------------------------------------------------------------
NCLIP=16   # Clip01-12 for Q2, Clip13-15 for Q3 (one per repeat), 16 spare
rm -rf "$MEDIA"; mkdir -p "$MEDIA/movies" "$WORK/src"
ffmpeg -nostdin -loglevel error -y -f lavfi -i "testsrc=size=640x360:rate=24:duration=300" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=300" -t 300 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 48 -b:v 900k -c:a aac -b:a 64k "$WORK/src/clip.mp4" || exit 1
ffmpeg -nostdin -loglevel error -y -f lavfi -i "testsrc=size=160x90:rate=1:duration=420" \
  -f lavfi -i "anullsrc=r=8000:cl=mono" -t 420 -c:v libx264 -preset ultrafast -g 30 -c:a aac -b:a 16k -shortest \
  "$WORK/src/live.mp4" || exit 1
for n in $(seq 1 $NCLIP); do
  d="$MEDIA/movies/$(printf 'Clip%02d' "$n") (2020)"; mkdir -p "$d"
  ln "$WORK/src/clip.mp4" "$d/$(printf 'Clip%02d' "$n") (2020).mp4" 2>/dev/null || cp "$WORK/src/clip.mp4" "$d/$(printf 'Clip%02d' "$n") (2020).mp4"
done
for n in 1 2 3 4; do
  d="$MEDIA/movies/Live$n (2020)"; mkdir -p "$d"
  ln "$WORK/src/live.mp4" "$d/Live$n (2020).mp4" 2>/dev/null || cp "$WORK/src/live.mp4" "$d/Live$n (2020).mp4"
done
chmod -R a+rX "$MEDIA"

# ---- container -----------------------------------------------------------------------------------------------
docker volume create "${NAME}-config" >/dev/null
docker volume create "${NAME}-cache" >/dev/null
nice -n 15 docker run -d --name "$NAME" --cpus "${SP7_CPUS:-2}" --cpu-shares 128 --memory "${SP7_MEM:-5g}" -p "127.0.0.1:${PORT}:8096" \
  -v "${NAME}-config:/config" -v "${NAME}-cache:/cache" -v "$MEDIA:/media:ro" "$IMAGE" >/dev/null || exit 1
for _ in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/Startup/Configuration")" = 200 ] && break
  sleep 3
done
VER="$(curl -sf "$BASE/System/Info/Public" | jq -r '.Version // .version')"
rec "# image" "$IMAGE" "server=$VER cpus=${SP7_CPUS:-2}"

curl -sS -X POST "$BASE/Startup/Configuration" -H 'Content-Type: application/json' \
  -d '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}' >/dev/null
curl -sS "$BASE/Startup/User" >/dev/null
curl -sS -X POST "$BASE/Startup/User" -H 'Content-Type: application/json' \
  -d "{\"Name\":\"sp7admin\",\"Password\":\"$PASS\"}" >/dev/null
curl -sS -X POST "$BASE/Startup/Complete" -H 'Content-Length: 0' >/dev/null
AUTH="$(curl -sS -X POST "$BASE/Users/AuthenticateByName" \
  -H 'Authorization: MediaBrowser Client="sp7", Device="sp7", DeviceId="sp7", Version="1.0"' \
  -H 'Content-Type: application/json' -d "{\"Username\":\"sp7admin\",\"Pw\":\"$PASS\"}")"
TOK="$(jq -r .AccessToken <<<"$AUTH")"
UID_="$(jq -r .User.Id <<<"$AUTH")"
if [ -z "$TOK" ] || [ "$TOK" = null ]; then echo "login failed" >&2; exit 1; fi

types='[{"Type":"Movie","MetadataFetchers":[],"ImageFetchers":[]}]'
api POST "/Library/VirtualFolders?name=Movies&collectionType=movies&refreshLibrary=false" \
  "{\"LibraryOptions\":{\"EnableInternetProviders\":false,\"EnableTrickplayImageExtraction\":false,\"ExtractTrickplayImagesDuringLibraryScan\":false,\"EnableChapterImageExtraction\":false,\"ExtractChapterImagesDuringLibraryScan\":false,\"EnableRealtimeMonitor\":false,\"PathInfos\":[{\"Path\":\"/media/movies\"}],\"TypeOptions\":$types}}" >/dev/null
api POST /Library/Refresh >/dev/null; settle

ITEMS="$(api GET "/Items?Recursive=true&IncludeItemTypes=Movie&userId=$UID_")"
C=(""); for n in $(seq 1 $NCLIP); do C[n]="$(jq -r --arg n "$(printf 'Clip%02d' "$n")" '.Items[]|select(.Name==($n + " (2020)"))|.Id' <<<"$ITEMS")"; [ -n "${C[n]}" ] || { echo "seeding failed (Clip$n)" >&2; exit 1; }; done
L=(""); for n in 1 2 3 4; do L[n]="$(jq -r --arg n "Live$n" '.Items[]|select(.Name==($n + " (2020)"))|.Id' <<<"$ITEMS")"; [ -n "${L[n]}" ] || { echo "seeding failed (Live$n)" >&2; exit 1; }; done
RT="$(api GET "/Items/${L[1]}?userId=$UID_" | jq -r .RunTimeTicks)"
CRT="$(api GET "/Items/${C[1]}?userId=$UID_" | jq -r .RunTimeTicks)"
CFG="$(api GET /System/Configuration | jq -c '{MinResumePct,MaxResumePct,MinResumeDurationSeconds}')"
rec "# setup" "live runtime=$((RT / 10000000)) s, clip runtime=$((CRT / 10000000)) s, clips=$NCLIP" "resume config=$CFG"
ENC0="$(api GET /System/Configuration/encoding | jq -c '{EnableThrottling,ThrottleDelaySeconds,EnableSegmentDeletion,SegmentKeepSeconds,HardwareAccelerationType,EncoderPreset,EnableHardwareEncoding}')"
rec "# encoding config at start (default)" "$ENC0" ""
pct() { echo $((RT * $1 / 100)); }

# ---- Q1: paused check-ins ----------------------------------------------------------------------------------------
q1case() { # name device item safeTicks
  local name="$1" dev="$2" id="$3" safe="$4" tok ps="ps-$2" t line="" last
  tok="$(login "$dev")"
  rec "$name before" "$(ud "$id")" "in Resume=$(inresume "$id")"
  apit "$tok" POST /Sessions/Playing "$(body "$id" "$ps" "$safe" false DirectPlay)" >/dev/null; sleep 1
  rec "$name session right after Playing at safe position" "$(sess "$dev")" ""
  for ((t = 0; t <= PAUSE_S; t += 10)); do
    if [ $((t % 30)) -eq 0 ]; then apit "$tok" POST /Sessions/Playing/Progress "$(body "$id" "$ps" "$safe" true DirectPlay)" >/dev/null; sleep 1; fi
    line="$line t=$t:$(sessc "$dev")"
    if [ "$t" -eq $((PAUSE_S / 2 / 10 * 10)) ]; then rec "$name userdata mid-pause (t=$t)" "$(ud "$id")" "in Resume=$(inresume "$id")"; fi
    [ "$t" -lt "$PAUSE_S" ] && sleep 9
  done
  rec "$name Sessions samples [nowPlaying,pos,paused] every ~10 s, IsPaused=true Progress at safe pos every 30 s" "$line" ""
  rec "$name session at end of pause (full)" "$(sess "$dev")" ""
  rec "$name userdata at end of pause" "$(ud "$id")" "in Resume=$(inresume "$id")"
  apit "$tok" POST /Sessions/Playing/Progress "$(body "$id" "$ps" "$safe" false DirectPlay)" >/dev/null; sleep 1
  rec "$name session after resume (IsPaused=false)" "$(sess "$dev")" ""
  sleep 29
  apit "$tok" POST /Sessions/Playing/Progress "$(body "$id" "$ps" "$safe" false DirectPlay)" >/dev/null; sleep 1
  rec "$name userdata while resumed" "$(ud "$id")" "in Resume=$(inresume "$id")"
  last="$(sessc "$dev")"
  apit "$tok" POST /Sessions/Playing/Stopped "$(stopbody "$id" "$ps" "$safe")" >/dev/null; sleep 2
  rec "$name session before/after Stopped Failed=true" "$last -> $(sessc "$dev")" ""
  rec "$name userdata after Stopped Failed=true" "$(ud "$id")" "in Resume=$(inresume "$id")"
}
q1x() { # one paused check-in, then silence
  local dev="dev-q1x" id="${L[4]}" tok ps="ps-dev-q1x" m line="" n=$((SILENT_S / 60))
  tok="$(login "$dev")"
  rec "q1x before" "$(ud "$id")" "in Resume=$(inresume "$id")"
  apit "$tok" POST /Sessions/Playing "$(body "$id" "$ps" 0 false DirectPlay)" >/dev/null; sleep 1
  apit "$tok" POST /Sessions/Playing/Progress "$(body "$id" "$ps" 0 true DirectPlay)" >/dev/null
  for m in $(seq 1 "$n"); do
    sleep 60
    line="$line m$m:$(sessc "$dev")/$(ud "$id" | jq -c '[.Played,.PlaybackPositionTicks,.PlayCount]')"
  done
  rec "q1x per-minute: Playing at 0, ONE IsPaused=true check-in, then silence [session [np,pos,paused] / userdata [played,posTicks,playCount]]" "$line" ""
  rec "q1x userdata at end" "$(ud "$id")" "in Resume=$(inresume "$id")"
}
q1() {
  rec "# Q1 paused check-ins (pause ${PAUSE_S} s, check-in every 30 s at the safe position)" "" ""
  local h50; h50="$(pct 50)"
  local tok; tok="$(login dev-q1-setup)"
  apit "$tok" POST /Sessions/Playing "$(body "${L[2]}" ps-setup 0 false DirectPlay)" >/dev/null; sleep 1
  apit "$tok" POST /Sessions/Playing/Stopped "$(jq -nc --arg id "${L[2]}" --argjson p "$h50" '{ItemId:$id,MediaSourceId:$id,PositionTicks:$p,Failed:false}')" >/dev/null; sleep 2
  api POST "/UserPlayedItems/${L[3]}?userId=$UID_" >/dev/null; sleep 1
  q1case q1-unwatched dev-q1a "${L[1]}" 0 & local a=$!
  q1case q1-resume50 dev-q1b "${L[2]}" "$h50" & local b=$!
  q1case q1-played dev-q1c "${L[3]}" 0 & local c=$!
  wait "$a" "$b" "$c"
}

# ---- HLS helpers ----------------------------------------------------------------------------------------------------
PROFILE_TMPL='{"Name":"sp7","MaxStreamingBitrate":MAXB,"DirectPlayProfiles":[],"TranscodingProfiles":[{"Container":"ts","Type":"Video","AudioCodec":"aac","VideoCodec":"h264","Context":"Streaming","Protocol":"hls","MaxAudioChannels":"2","MinSegments":1,"BreakOnNonKeyFrames":true}],"CodecProfiles":[],"ContainerProfiles":[],"SubtitleProfiles":[]}'
PBI=""   # last PlaybackInfo response
pbinfo() { # token item startTicks mode(transcode|directstream) -> PBI, TURL, PSID
  local t="$1" id="$2" st="$3" mode="$4" ds=false maxb=300000
  [ "$mode" = directstream ] && { ds=true; maxb=100000000; }
  local prof="${PROFILE_TMPL/MAXB/$maxb}"
  PBI="$(apit "$t" POST "/Items/$id/PlaybackInfo?startTimeTicks=$st&maxStreamingBitrate=$maxb" "{\"UserId\":\"$UID_\",\"StartTimeTicks\":$st,\"MaxStreamingBitrate\":$maxb,\"EnableDirectPlay\":false,\"EnableDirectStream\":$ds,\"EnableTranscoding\":true,\"AutoOpenLiveStream\":false,\"MediaSourceId\":\"$id\",\"DeviceProfile\":$prof}")"
  TURL="$(jq -r '.MediaSources[0].TranscodingUrl // empty' <<<"$PBI")"
  PSID="$(jq -r '.PlaySessionId // empty' <<<"$PBI")"
}
MAIN=""; MAINDIR=""; SEGU=(); SEGD=()
T_MASTER=""; T_MAIN=""
open_hls() { # token transcodingUrl -> MAIN, MAINDIR, SEGU, SEGD, T_MASTER, T_MAIN ; returns 1 on failure
  local t="$1" u="$2" base mu f="$WORK/body.$BASHPID"
  base="${u%%\?*}"; base="${base%/*}/"
  getb "$t" "$u"; T_MASTER="$G_TIME"
  [ "$G_CODE" = 200 ] || { OPEN_ERR="master $G_CODE"; return 1; }
  mu="$(grep -v '^#' "$f" | head -1)"
  case "$mu" in /*) MAIN="$mu" ;; *) MAIN="$base$mu" ;; esac
  MAINDIR="${MAIN%%\?*}"; MAINDIR="${MAINDIR%/*}/"
  getb "$t" "$MAIN"; T_MAIN="$G_TIME"
  [ "$G_CODE" = 200 ] || { OPEN_ERR="main $G_CODE"; return 1; }
  mapfile -t SEGU < <(grep -v '^#' "$f")
  mapfile -t SEGD < <(grep '^#EXTINF' "$f" | sed -E 's/#EXTINF:([0-9.]+).*/\1/')
  [ "${#SEGU[@]}" -gt 0 ] || { OPEN_ERR="empty playlist"; return 1; }
}
segpath() { case "${SEGU[$1]}" in /*) echo "${SEGU[$1]}" ;; *) echo "$MAINDIR${SEGU[$1]}" ;; esac; }
seg_for() { # seconds -> segment index containing it
  local i=0 c=0 d
  for d in "${SEGD[@]}"; do
    c="$(awk -v c="$c" -v d="$d" 'BEGIN{print c+d}')"
    if awk -v c="$c" -v t="$1" 'BEGIN{exit !(c>t)}'; then echo "$i"; return; fi
    i=$((i + 1))
  done
  echo $((i - 1))
}
ffps() { docker top "$NAME" -eo pid,args 2>/dev/null | grep -E '/ffmpeg ' | grep -v grep; }
ffpids() { ffps | grep -F "$1" | awk '{print $1}' | sort | tr '\n' ',' ; }   # $1 = tag e.g. "Clip03 (2020)"
ffdesc() { # tag -> pids with their seek / start-number / video codec args
  ffps | grep -F "$1" | while read -r pid rest; do
    printf 'pid=%s ss=%s start_number=%s vcodec=%s;' "$pid" "$(grep -oE -- '-ss [0-9:.]+' <<<"$rest" | head -1 | cut -d' ' -f2)" \
      "$(grep -oE -- '-start_number [0-9]+' <<<"$rest" | head -1 | cut -d' ' -f2)" \
      "$(grep -oE -- '-(codec:v:0|c:v:0|c:v) [a-z0-9_]+' <<<"$rest" | head -1 | cut -d' ' -f2)"
  done
}
stopjob() { # token device psid item psid
  apit "$1" DELETE "/Videos/ActiveEncodings?deviceId=$2&playSessionId=$3" >/dev/null
}
tsfiles() { docker exec "$NAME" sh -c 'find /cache/transcodes /config/transcodes -name "*.ts" 2>/dev/null | wc -l' 2>/dev/null | tr -d ' '; }

# ---- Q2: idle transcode job while paused ---------------------------------------------------------------------------
declare -A J_ser J_arm J_min J_probe J_tok J_dev J_item J_tag J_ps J_t0 J_done J_prev J_next J_pid0 J_lab
q2pass() { # cfg-label
  local cfg="$1" i=0 tick=0 ssnap min probe arm n jobs=() pstart now_ t age alive snap tag out
  pstart="$(date +%s)"
  rec "# Q2 pass: encoding config = $cfg" "$(api GET /System/Configuration/encoding | jq -c '{EnableThrottling,ThrottleDelaySeconds,EnableSegmentDeletion}')" ""
  for min in $(tr ' ' '\n' <<<"$PROBES" | sort -rn); do for probe in seg pl; do for arm in A B; do
    i=$((i + 1)); n=$i
    J_arm[$n]="$arm"; J_min[$n]="$min"; J_probe[$n]="$probe"; J_done[$n]=0; J_prev[$n]=1; J_next[$n]=0; J_ser[$n]=""
    J_lab[$n]="$cfg/$arm/${min}s/$probe"
    J_item[$n]="${C[$n]}"; J_tag[$n]="$(printf 'Clip%02d (2020)' "$n")"
    J_dev[$n]="dev-q2-$n"; J_tok[$n]="$(login "${J_dev[$n]}")"
    pbinfo "${J_tok[$n]}" "${J_item[$n]}" 0 transcode
    J_ps[$n]="$PSID"
    if [ -z "$TURL" ]; then rec "q2 ${J_lab[$n]}" "no TranscodingUrl" "$(jq -c '.MediaSources[0]|{SupportsTranscoding,SupportsDirectStream,SupportsDirectPlay}' <<<"$PBI")"; J_done[$n]=1; continue; fi
    [ "$n" -eq 1 ] && rec "q2 transcode reasons / url params" "$(grep -oE 'TranscodeReasons=[^&]*|VideoCodec=[^&]*|SegmentContainer=[^&]*|VideoBitrate=[^&]*' <<<"$TURL" | tr '\n' ' ')" ""
    apit "${J_tok[$n]}" POST /Sessions/Playing "$(body "${J_item[$n]}" "$PSID" 0 false Transcode)" >/dev/null
    if ! open_hls "${J_tok[$n]}" "$TURL"; then rec "q2 ${J_lab[$n]} setup failed" "$OPEN_ERR" ""; J_done[$n]=1; continue; fi
    J_mainurl[$n]="$MAIN"; J_dir[$n]="$MAINDIR"
    J_seg5[$n]="$(segpath 5)"
    for t in 0 1 2 3 4; do getb "${J_tok[$n]}" "$(segpath $t)"; [ "$G_CODE" = 200 ] || rec "q2 ${J_lab[$n]} warm segment $t" "$G_CODE" ""; done
    sleep 1
    J_pid0[$n]="$(ffpids "${J_tag[$n]}")"
    J_t0[$n]="$(now)"
    rec "q2 ${J_lab[$n]} start" "segments=${#SEGU[@]} item=${J_tag[$n]} ffmpeg=$(ffdesc "${J_tag[$n]}")" "main.m3u8 ${T_MAIN}s master ${T_MASTER}s"
  done; done; done
  local total=$i active=1
  while [ "$active" -gt 0 ]; do
    active=0; snap="$(ffps)"; now_="$(now)"; tick=$((tick + 1)); ssnap=""
    [ $((tick % 6)) -eq 1 ] && ssnap="$(api GET /Sessions)"
    for n in $(seq 1 "$total"); do
      [ "${J_done[$n]}" = 1 ] && continue
      active=$((active + 1))
      age="$(dt "${J_t0[$n]}" "$now_")"
      tag="${J_tag[$n]}"
      if [ -n "$ssnap" ]; then
        J_ser[$n]="${J_ser[$n]} ${age%.*}s:$(jq -c --arg d "${J_dev[$n]}" '[.[]|select(.DeviceId==$d)][0]|[(.NowPlayingItem!=null),.PlayState.PositionTicks,.PlayState.IsPaused,.PlayState.PlayMethod]' <<<"$ssnap")"
      fi
      alive=0; grep -qF "$tag" <<<"$snap" && alive=1
      if [ "$alive" != "${J_prev[$n]}" ]; then
        rec "q2 ${J_lab[$n]} ffmpeg ${J_prev[$n]}->$alive at age (s, +-5)" "$age" "pids now: $(ffpids "$tag")"
        J_prev[$n]="$alive"
      fi
      if [ "${J_arm[$n]}" = A ] && awk -v a="$age" -v nx="${J_next[$n]}" 'BEGIN{exit !(a>=nx)}'; then
        apit "${J_tok[$n]}" POST /Sessions/Playing/Progress "$(body "${J_item[$n]}" "${J_ps[$n]}" 0 true Transcode)" >/dev/null
        J_next[$n]=$((J_next[$n] + 30))
      fi
      if awk -v a="$age" -v m="${J_min[$n]}" 'BEGIN{exit !(a>=m)}'; then
        q2probe "$n" "$age"
        J_done[$n]=1
      fi
    done
    [ "$active" -gt 0 ] && sleep 5
  done
  docker logs --since "$pstart" "$NAME" 2>&1 | grep -i -E 'kill|idle|stopp|transcod|exited|delet|ffmpeg' | grep -v -E 'ffmpeg .* -i |MediaInfoHelper|User policy' \
    | cut -c1-260 | head -80 | while read -r l; do rec "q2 server log ($cfg)" "$l" ""; done
  for n in $(seq 1 "$total"); do
    [ "${J_arm[$n]}" = B ] && [ "${J_probe[$n]}" = seg ] && rec "q2 ${J_lab[$n]} userdata at end" "$(ud "${J_item[$n]}")" "in Resume=$(inresume "${J_item[$n]}")"
  done
}
declare -A J_mainurl J_dir J_seg5
q2probe() { # n age
  local n="$1" age="$2" tok="${J_tok[$n]}" lab="${J_lab[$n]}" tag="${J_tag[$n]}" before after sbefore
  before="$(ffdesc "$tag")"; sbefore="$(sessc "${J_dev[$n]}")"
  if [ "${J_probe[$n]}" = seg ]; then
    getb "$tok" "${J_seg5[$n]}"
    rec "q2 $lab PROBE next segment (5) at age ${age}s" "http=$G_CODE time=${G_TIME}s bytes=$G_SIZE" "session before probe=$sbefore; ffmpeg before=[${before}] after(3s)=[$(sleep 3; ffdesc "$tag")]"
  else
    getb "$tok" "${J_mainurl[$n]}"
    local c1="$G_CODE" t1="$G_TIME" s1="$G_SIZE" mid
    mid="$(ffdesc "$tag")"
    getb "$tok" "${J_seg5[$n]}"
    rec "q2 $lab PROBE playlist at age ${age}s" "http=$c1 time=${t1}s bytes=$s1" "session before probe=$sbefore; ffmpeg before=[${before}] right after playlist=[${mid}]; then segment 5: http=$G_CODE time=${G_TIME}s; ffmpeg after=[$(ffdesc "$tag")]"
  fi
  rec "q2 $lab session after probe" "$(sess "${J_dev[$n]}")" "series every ~30 s [np,posTicks,paused,method]:${J_ser[$n]}"
  apit "$tok" POST /Sessions/Playing/Stopped "$(stopbody "${J_item[$n]}" "${J_ps[$n]}" 0)" >/dev/null
  stopjob "$tok" "${J_dev[$n]}" "${J_ps[$n]}"
}

# ---- Q3: seek on a transcode -----------------------------------------------------------------------------------------
P_S=80
q3() { # mode
  local mode="$1" rep scen method tok dev="dev-q3-$1" id tag tgt idx k t0 t1 ts_before pids_b pids_a off
  tok="$(login "$dev")"
  rec "# Q3 [$CFG_NOW] mode=$mode (playing from 0, warm to ${P_S} s, then seek)" "" ""
  if [ "$mode" = transcode ]; then
    pbinfo "$tok" "${C[16]}" 0 "$mode"
    if open_hls "$tok" "$TURL&StartTimeTicks=$((P_S * 10000000))"; then
      getb "$tok" "$(segpath 0)"
      rec "q3 [$CFG_NOW] $mode probe: StartTimeTicks=${P_S}s appended to the HLS master URL, then segment 0" "http=$G_CODE bytes=$G_SIZE" "body: $(head -c 200 "$G_FILE" | tr -d '\n\t')"
    else
      rec "q3 [$CFG_NOW] $mode probe: StartTimeTicks appended to the HLS master URL" "open failed: $OPEN_ERR" ""
    fi
    stopjob "$tok" "$dev" "$PSID"
  fi
  for scen in ${SP7_SCENS:-back10 fwd30 fwd120 fwd200}; do
    case "$scen" in back10) off=-10 ;; fwd30) off=30 ;; fwd120) off=120 ;; fwd200) off=200 ;; esac
    tgt=$((P_S + off))
    for method in inplaylist restart; do
      for rep in $(seq 1 "${SP7_REPS:-3}"); do
        id="${C[$((12 + rep))]}"; tag="$(printf 'Clip%02d (2020)' $((12 + rep)))"
        pbinfo "$tok" "$id" 0 "$mode"; local ps1="$PSID"
        [ -n "$TURL" ] || { rec "q3 [$CFG_NOW] $mode $scen $method rep$rep" "no TranscodingUrl" "$(jq -c '.MediaSources[0]|{SupportsTranscoding,SupportsDirectStream}' <<<"$PBI")"; continue; }
        [ "$rep" = 1 ] && [ "$scen" = back10 ] && [ "$method" = inplaylist ] && rec "q3 [$CFG_NOW] $mode url params" "$(grep -oE 'TranscodeReasons=[^&]*|VideoCodec=[^&]*|SegmentContainer=[^&]*' <<<"$TURL" | tr '\n' ' ')" ""
        open_hls "$tok" "$TURL" || { rec "q3 [$CFG_NOW] $mode $scen $method rep$rep" "open failed: $OPEN_ERR" ""; stopjob "$tok" "$dev" "$ps1"; continue; }
        idx="$(seg_for "$P_S")"
        t0="$(now)"; for k in $(seq 0 "$idx"); do getb "$tok" "$(segpath $k)"; done; t1="$(now)"
        sleep 2
        pids_b="$(ffpids "$tag")"; ts_before="$(tsfiles)"
        if [ "$method" = inplaylist ]; then
          local si; si="$(seg_for "$tgt")"
          getb "$tok" "$(segpath "$si")"
          local c="$G_CODE" tm="$G_TIME" sz="$G_SIZE" stp
          stp="$(ffprobe -v error -show_entries format=start_time -of csv=p=0 "$G_FILE" 2>/dev/null)"
          local ffnow; ffnow="$(ffdesc "$tag")"
          sleep 2; pids_a="$(ffpids "$tag")"
          rec "q3 [$CFG_NOW] $mode $scen inplaylist rep$rep -> seg $si (target ${tgt}s)" "http=$c time=$tm bytes=$sz" "segment pts start_time=${stp:-?}s; warmup ${P_S}s took $(dt "$t0" "$t1")s; ts files before=$ts_before; ffmpeg pids before=$pids_b after=$pids_a restarted=$([ "$pids_b" != "$pids_a" ] && echo yes || echo no) ffmpeg immediately after=[$ffnow]; 2s later=[$(ffdesc "$tag")]"
          apit "$tok" POST /Sessions/Playing/Stopped "$(stopbody "$id" "$ps1" 0)" >/dev/null
          stopjob "$tok" "$dev" "$ps1"
        else
          stopjob "$tok" "$dev" "$ps1"; sleep 2
          local ta tb tc td te
          ta="$(now)"
          pbinfo "$tok" "$id" $((tgt * 10000000)) "$mode"; local ps2="$PSID"
          tb="$(now)"
          if ! open_hls "$tok" "$TURL"; then rec "q3 [$CFG_NOW] $mode $scen restart rep$rep" "open failed: $OPEN_ERR" ""; stopjob "$tok" "$dev" "$ps2"; continue; fi
          tc="$(now)"
          local ri; ri="$(seg_for "$tgt")"
          getb "$tok" "$(segpath "$ri")"
          td="$(now)"
          local c="$G_CODE" tm="$G_TIME" sz="$G_SIZE" n_seg="${#SEGU[@]}" st0 urlst ffnow
          ffnow="$(ffdesc "$tag")"
          st0="$(ffprobe -v error -show_entries format=start_time -of csv=p=0 "$G_FILE" 2>/dev/null)"
          urlst="$(grep -oiE 'StartTimeTicks=[^&]*' <<<"$TURL" | head -1)"
          sleep 2; pids_a="$(ffpids "$tag")"
          rec "q3 [$CFG_NOW] $mode $scen restart rep$rep (new PlaybackInfo StartTimeTicks=${tgt}s, then segment for ${tgt}s)" "total=$(dt "$ta" "$td")s [playbackinfo $(dt "$ta" "$tb")s, master+main $(dt "$tb" "$tc")s, first segment ${tm}s http=$c bytes=$sz]" "url ${urlst:-no StartTimeTicks in url} [params: $(sed -E "s/^[^?]*\?//; s/=[^&]*(&|$)/ /g" <<<"$TURL")]; first segment pts start_time=${st0:-?}s; playlist segments=$n_seg; first requested segment=$ri uri=$(segpath "$ri" | sed -E 's/\?.*//'); ffmpeg right after first segment=[$ffnow]; 2s later=[$(sleep 2; ffdesc "$tag")]"
          apit "$tok" POST /Sessions/Playing/Stopped "$(stopbody "$id" "$ps2" 0)" >/dev/null
          stopjob "$tok" "$dev" "$ps2"
        fi
        sleep 2
      done
    done
  done
}

q3summary() { summary_rows "$RESULTS" "$CFG_NOW" "$1" | while IFS=$'\t' read -r k v n; do rec "$k" "$v" "$n"; done; }

set_throttle() {
  local cfgj; cfgj="$(api GET /System/Configuration/encoding | jq -c '.EnableThrottling=true')"
  api POST /System/Configuration/encoding "$cfgj" >/dev/null
}

# ---- run ------------------------------------------------------------------------------------------------------------
CFG_NOW=""
for cfg in $CFGS; do
  CFG_NOW="$cfg"
  [ "$cfg" = throttled ] && set_throttle
  rec "# ===== encoding config: $cfg =====" "$(api GET /System/Configuration/encoding | jq -c '{EnableThrottling,ThrottleDelaySeconds,EnableSegmentDeletion}')" ""
  if [ -z "$ONLY" ] || [ "$ONLY" = q3 ]; then
    q3 transcode; q3summary transcode
    [ "$cfg" = default ] && { q3 directstream; q3summary directstream; }
  fi
  if [ -z "$ONLY" ] || [ "$ONLY" = q1 ] || [ "$ONLY" = q2 ]; then
    q1pids=()
    if [ "$cfg" = default ] && { [ -z "$ONLY" ] || [ "$ONLY" = q1 ]; }; then
      q1 & q1pids+=($!)
      q1x & q1pids+=($!)
    fi
    if [ -z "$ONLY" ] || [ "$ONLY" = q2 ]; then q2pass "$cfg"; fi
    [ "${#q1pids[@]}" -gt 0 ] && wait "${q1pids[@]}"
  fi
done
echo "# done: image=$IMAGE server=$VER"
