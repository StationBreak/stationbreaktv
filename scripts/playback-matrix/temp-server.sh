#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# A temporary Jellyfin server of synthetic test media for the playback matrix (spec 13). Laptop bridge only: it is run by
# hand next to the device, never from CI.
#   temp-server.sh [--dry-run] up [BIND_ADDRESS] [PORT]    start it, set it up, write items.local.json
#   temp-server.sh [--dry-run] down                        remove the container and its volume
# BIND_ADDRESS is the IPv4 address of the LAN interface the device can reach (or set PLAYBACK_MATRIX_BIND): a private
# address only (RFC 1918 or link-local), never guessed and never written into the repo. PORT defaults to 8096.
# --dry-run prints the steps and runs none of them.
#
# `up` runs the pinned image, first published on 127.0.0.1 only (an ephemeral port), so nothing on the LAN can reach the
# unconfigured server. Over loopback it completes the setup wizard through the API with a random admin password
# (printed once to the terminal, never written to a file), makes the synthetic clips with the image's own ffmpeg,
# creates a "Formats" movie library, waits for the scan and writes the clips' IDs to items.local.json (gitignored).
# Only then is the container recreated, on the same volumes, published on BIND_ADDRESS. Docker-published ports bypass
# the host firewall, so that last step is what exposes it: to a LAN you trust, for the length of a run.
# Sign the device in with the printed address and password; the app reaches it through the existing cleartext
# acknowledgement (ADR-0014).
# The admin gets the English track preferences the track-selection clips expect (as the contract suite's viewer has).
# It touches only the container and volume it names and labels itself; `down` refuses anything without that label.
# Needs docker, curl and jq.
set -euo pipefail

IMAGE='ghcr.io/jellyfin/jellyfin@sha256:c3efa69a22f2691fc88d6bb035e87a8560e395a6047f062fb381c61c3185e8f5'
NAME=stationbreak-playback-matrix
VOLUME="$NAME-config"
MEDIA_VOLUME="$NAME-media"
LABEL=tv.stationbreak.playback-matrix
FFMPEG=/usr/lib/jellyfin-ffmpeg/ffmpeg
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
items_file="$here/items.local.json"

usage() {
  echo "usage: $0 [--dry-run] up [BIND_ADDRESS] [PORT] | down   (BIND_ADDRESS or PLAYBACK_MATRIX_BIND)" >&2
  exit 2
}

dry=false
if [ "${1:-}" = "--dry-run" ]; then
  dry=true
  shift
fi
[ "$#" -ge 1 ] || usage
action=$1
shift

# step <description> <command...>: prints the step in a dry run, runs it otherwise.
step() {
  local description=$1
  shift
  if [ "$dry" = true ]; then
    echo "[dry-run] $description"
  else
    "$@"
  fi
}

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing tool: $1" >&2
    exit 1
  }
}

owned() { # true when the named container carries our label
  [ "$(docker container inspect --format "{{ index .Config.Labels \"$LABEL\" }}" "$NAME" 2>/dev/null || true)" = 1 ]
}

# ---------------------------------------------------------------- down

if [ "$action" = down ]; then
  [ "$#" -eq 0 ] || usage
  if [ "$dry" = true ]; then
    echo "[dry-run] check that $NAME carries the label $LABEL=1, else refuse"
    echo "[dry-run] docker rm -f -v $NAME"
    echo "[dry-run] docker volume rm $VOLUME $MEDIA_VOLUME (each only if it carries the same label)"
    exit 0
  fi
  need docker
  if docker ps -a --filter "name=^/$NAME\$" --format '{{.Names}}' | grep -qx "$NAME"; then
    owned || {
      echo "refusing: a container named $NAME exists but this script did not create it" >&2
      exit 1
    }
    docker rm -f -v "$NAME"
  else
    echo "no container named $NAME"
  fi
  for volume in "$VOLUME" "$MEDIA_VOLUME"; do
    if [ "$(docker volume inspect --format "{{ index .Labels \"$LABEL\" }}" "$volume" 2>/dev/null || true)" = 1 ]; then
      docker volume rm "$volume"
    fi
  done
  exit 0
fi

# ---------------------------------------------------------------- up

[ "$action" = up ] || usage
bind=${1:-${PLAYBACK_MATRIX_BIND:-}}
port=${2:-8096}
[ "$#" -le 2 ] || usage
if [ -z "$bind" ]; then
  echo "give the LAN address to bind (an argument, or PLAYBACK_MATRIX_BIND)" >&2
  usage
fi
octet='(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])'
if ! [[ $bind =~ ^$octet\.$octet\.$octet\.$octet$ ]]; then
  echo "BIND_ADDRESS must be an IPv4 address" >&2
  exit 2
fi
# The server is open to whoever can reach the port (a wizard-fresh admin, then a throwaway password): a private LAN
# address only. Not every interface, not loopback (the device could not reach it), not a public or shared address.
IFS=. read -r o1 o2 _ _ <<<"$bind"
o1=$((10#$o1))
o2=$((10#$o2))
if ! { [ "$o1" -eq 10 ] ||
  { [ "$o1" -eq 172 ] && [ "$o2" -ge 16 ] && [ "$o2" -le 31 ]; } ||
  { [ "$o1" -eq 192 ] && [ "$o2" -eq 168 ]; } ||
  { [ "$o1" -eq 169 ] && [ "$o2" -eq 254 ]; }; }; then
  echo "BIND_ADDRESS must be a private LAN address: 10/8, 172.16/12, 192.168/16 or link-local 169.254/16" >&2
  exit 2
fi
[[ $port =~ ^[0-9]{2,5}$ ]] || {
  echo "PORT must be a number" >&2
  exit 2
}

lan_base="http://$bind:$port"
base='' # the loopback address while the server is set up, then $lan_base
password=''
token=''
user_id=''

# The Authorization header lives in a 0600 file that curl reads (--config), never in curl's arguments, where the admin
# token would show in the process list. The file is removed when the script ends.
auth_cfg=$(mktemp)
trap 'rm -f "$auth_cfg"' EXIT
set_auth() { # <token, or empty before sign-in>: the modern MediaBrowser header the app and the contract suite use
  local header='MediaBrowser Client=\"Playback matrix\", Device=\"Laptop\", DeviceId=\"playback-matrix\", Version=\"1\"'
  [ -z "$1" ] || header="$header, Token=\\\"$1\\\""
  printf 'header = "Authorization: %s"\n' "$header" >"$auth_cfg"
}
set_auth ''

# api <method> <path> [json-body-on-stdin]: prints the answer. The body goes by stdin so a password never shows in the
# process list (jq gets the password from its environment for the same reason).
api() {
  local method=$1 path=$2
  curl --silent --show-error --fail --max-time 30 --config "$auth_cfg" -X "$method" \
    -H 'Content-Type: application/json' --data-binary @- "$base/$path"
}
call() { # api with an empty body
  api "$1" "$2" </dev/null
}

wait_for() { # <description> <attempts> <command...>: polls every 2 s
  local description=$1 attempts=$2 n=0
  shift 2
  until "$@" >/dev/null 2>&1; do
    n=$((n + 1))
    [ "$n" -lt "$attempts" ] || {
      echo "timed out waiting for $description" >&2
      return 1
    }
    sleep 2
  done
}

run_container() { # <published port, as docker -p reads it>: the media is a volume, so a recreated container keeps it
  docker run -d --name "$NAME" --label "$LABEL=1" -p "$1" -v "$VOLUME:/config" -v "$MEDIA_VOLUME:/media/formats" \
    "$IMAGE" >/dev/null
}

start_container() { # on loopback only, on a port docker picks (the home server may hold 8096)
  need docker
  if docker ps -a --filter "name=^/$NAME\$" --format '{{.Names}}' | grep -qx "$NAME"; then
    echo "a container named $NAME already exists; run '$0 down' first" >&2
    exit 1
  fi
  docker volume create --label "$LABEL=1" "$VOLUME" >/dev/null
  docker volume create --label "$LABEL=1" "$MEDIA_VOLUME" >/dev/null
  run_container '127.0.0.1::8096'
  local published
  published=$(docker port "$NAME" 8096/tcp | head -n 1)
  [[ $published =~ ^127\.0\.0\.1:([0-9]+)$ ]] || {
    echo "could not read the loopback port docker published" >&2
    return 1
  }
  base="http://127.0.0.1:${BASH_REMATCH[1]}"
}

# The setup is done and the admin has a password: only now is the server published on the LAN address.
publish_on_lan() {
  # A clean stop first, so Jellyfin closes its database before the container is recreated on the same volumes.
  docker stop "$NAME" >/dev/null
  docker rm -f -v "$NAME" >/dev/null
  run_container "$bind:$port:8096"
  base=$lan_base
  wait_for 'the server on its LAN address' 90 call GET System/Info/Public
}

complete_wizard() {
  need curl
  need jq
  password=$(head -c 18 /dev/urandom | base64 | tr -d '+/=\n')
  wait_for 'the startup wizard' 90 call GET Startup/Configuration
  echo '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}' | api POST Startup/Configuration >/dev/null
  call GET Startup/User >/dev/null
  PW=$password jq -n '{Name: "admin", Password: env.PW}' | api POST Startup/User >/dev/null
  echo '{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}' | api POST Startup/RemoteAccess >/dev/null
  call POST Startup/Complete >/dev/null
  token=$(PW=$password jq -n '{Username: "admin", Pw: env.PW}' | api POST Users/AuthenticateByName | jq -r .AccessToken)
  [ -n "$token" ] && [ "$token" != null ] || {
    echo "could not sign in as the new admin" >&2
    return 1
  }
  set_auth "$token"
  user_id=$(call GET Users/Me | jq -r .Id)
}

make_media() {
  docker cp "$here/synthetic-media.sh" "$NAME:/tmp/synthetic-media.sh"
  docker exec "$NAME" bash /tmp/synthetic-media.sh "$FFMPEG" /media/formats
}

# The admin is the account the device signs in as; the track-selection clips expect English preferred.
set_track_preferences() {
  call GET "Users/$user_id" | jq '.Configuration + {AudioLanguagePreference: "eng", SubtitleLanguagePreference: "eng",
    SubtitleMode: "Always", PlayDefaultAudioTrack: false}' | api POST "Users/Configuration?userId=$user_id" >/dev/null
}

create_library() {
  echo '{"LibraryOptions":{"PathInfos":[{"Path":"/media/formats"}]}}' |
    api POST 'Library/VirtualFolders?name=Formats&collectionType=movies&refreshLibrary=false' >/dev/null
  call POST Library/Refresh >/dev/null
}

formats_ready() { # every clip folder is a probed movie
  local want have
  want=$(docker exec "$NAME" ls /media/formats | grep -c .)
  have=$(call GET 'Items?recursive=true&includeItemTypes=Movie&fields=Path,MediaSources' |
    jq '[.Items[] | select((.Path | startswith("/media/formats/")) and ((.MediaSources // []) | length > 0))] | length')
  [ "$want" -gt 0 ] && [ "$want" = "$have" ]
}

write_items() {
  local synthetic
  synthetic=$(call GET 'Items?recursive=true&includeItemTypes=Movie&fields=Path,MediaSources' |
    jq '[.Items[] | select(.Path | startswith("/media/formats/"))
      | {id: .Id, name: (.Path | ltrimstr("/media/formats/") | split("/")[0]), runtimeMs: ((.RunTimeTicks // 0) / 10000 | floor)}]
      | sort_by(.name)')
  umask 077
  if [ -f "$items_file" ]; then
    # mktemp in the ignored directory (.gitignore covers items.local.json*), mode 0600
    local next
    next=$(mktemp "$items_file.XXXXXX")
    jq --argjson s "$synthetic" '.synthetic = $s' "$items_file" >"$next" && mv "$next" "$items_file"
  else
    jq -n --argjson s "$synthetic" '{synthetic: $s, real: []}' >"$items_file"
  fi
}

step "check that no container named $NAME exists" true
step "docker volume create --label $LABEL=1 $VOLUME and $MEDIA_VOLUME" true
step "docker run -d --name $NAME --label $LABEL=1 -p 127.0.0.1::8096 -v $VOLUME:/config -v $MEDIA_VOLUME:/media/formats $IMAGE" true
if [ "$dry" = true ]; then
  step "wait for the startup wizard on the loopback port, then complete it through the API as admin with a random password" true
  step "sign in as admin and set English track preferences (audio, subtitles always, play-default-audio off)" true
  step "docker cp synthetic-media.sh into $NAME and run it with $FFMPEG, writing /media/formats" true
  step "create the \"Formats\" movie library over /media/formats and start a scan" true
  step "wait until every clip is a probed movie" true
  step "write the clip IDs to $items_file (gitignored)" true
  step "recreate the container on the same volumes, published on $lan_base only now that the wizard is done" true
  step "print $lan_base, the user name admin and the password, once, to this terminal only" true
  exit 0
fi

start_container
complete_wizard
set_track_preferences
make_media
create_library
wait_for 'the Formats scan' 150 formats_ready
write_items
publish_on_lan

clips=$(jq '.synthetic | length' "$items_file")
echo
echo "Ready: $clips synthetic clips in $items_file"
echo "Server: $lan_base"
echo "User:   admin"
echo "Password (shown once, kept nowhere): $password"
echo "Remove it with: $0 down"
