#!/usr/bin/env bash
# Spike SP-5: which Jellyfin actions bump a BoxSet or Series in a MinDateLastSaved query (ADR-0015).
#
# Usage:  scripts/spikes/sp5-change-signals.sh <jellyfin-image> <workdir>
#   e.g.  scripts/spikes/sp5-change-signals.sh jellyfin/jellyfin:12.2.20261005-225228 /tmp/sp5-work
#
# Needs docker, ffmpeg, curl, jq. Starts ONE throwaway container (sp5-<pid>) on 127.0.0.1 only, with a
# throwaway admin whose password is generated here and never printed, runs every case, prints a TSV
# result table and removes the container and its volumes. It never touches any other container and
# needs no credentials or API keys. Re-run it against a newer Jellyfin image to re-check the findings.
# Env: SP5_PORT (default 18096), SP5_INTERNET=0 to skip the TMDB box-set case (e).
set -uo pipefail

IMAGE="${1:?usage: $0 <jellyfin-image> <workdir>}"
WORK="${2:?usage: $0 <jellyfin-image> <workdir>}"
PORT="${SP5_PORT:-18096}"
NAME="sp5-$$"
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

AUTHH='Authorization: MediaBrowser Client="sp5", Device="sp5", DeviceId="sp5", Version="1.0"'
api() { # method path [json-body]
  local m="$1" p="$2" b="${3:-}"
  if [ -n "$b" ]; then
    curl -sS -X "$m" "$BASE$p" -H "Authorization: MediaBrowser Token=\"$TOK\"" -H 'Content-Type: application/json' -d "$b"
  else
    curl -sS -X "$m" "$BASE$p" -H "Authorization: MediaBrowser Token=\"$TOK\"" -H 'Content-Length: 0'
  fi
}
postfile() { # path file
  curl -sS -X POST "$BASE$1" -H "Authorization: MediaBrowser Token=\"$TOK\"" -H 'Content-Type: application/json' -d @"$2" >/dev/null
}
now() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }
stamp() { sleep 1.2; now; sleep 1.2; }   # a timestamp with a clear gap either side
ids() { # types [since]  -> one id per line
  local q="Recursive=true&IncludeItemTypes=$1&collapseBoxSetItems=false"
  [ -n "${2:-}" ] && q="$q&MinDateLastSaved=$2"
  api GET "/Items?$q" | jq -r '.Items[]?.Id'
}
has() { grep -qx "$1"; }
members() { api GET "/Items?ParentId=$1&collapseBoxSetItems=false" | jq -r '.Items[]?.Id' | sort | tr '\n' ' '; }
settle() { # wait for the library scan task and the refresh queue to go quiet
  sleep 3
  local i
  for i in $(seq 1 90); do
    api GET /ScheduledTasks | jq -e '[.[] | select(.State != "Idle")] | length == 0' >/dev/null && break
    sleep 2
  done
  sleep 6
}
scan() { api POST /Library/Refresh >/dev/null; settle; }
rec() { printf '%s\t%s\t%s\n' "$1" "$2" "$3" | tee -a "$RESULTS"; }
yn() { [ "$1" = 1 ] && echo yes || echo no; }
byname() { api GET "/Items?Recursive=true&IncludeItemTypes=$1&collapseBoxSetItems=false" | jq -r --arg n "$2" '[.Items[]? | select(.Name | startswith($n))][0].Id // empty'; }
refresh() { api POST "/Items/$1/Refresh?$2" >/dev/null; settle; }
getitem() { api GET "/Items/$1?userId=$UID_"; }
inbump() { ids "$1" "$2" | has "$3" && echo 1 || echo 0; }   # type since id
count() { wc -l | tr -d ' '; }

# ---- media: tiny valid files -------------------------------------------------------------------
mk() {
  mkdir -p "$(dirname "$1")"
  ffmpeg -loglevel error -y -f lavfi -i color=c=black:s=64x64:r=5:d=2 -f lavfi -i anullsrc=r=8000:cl=mono \
    -t 2 -c:v libx264 -preset ultrafast -c:a aac -shortest "$1"
}
rm -rf "$MEDIA"
for m in "Alpha Movie (2001)" "Beta Movie (2002)" "Gamma Movie (2003)" "Delta Movie (2004)" "Epsilon Movie (2005)" "Zeta Movie (2006)"; do
  mk "$MEDIA/movies/$m/$m.mp4"
done
for s in "Show One (2005)" "Show Two (2010)"; do
  mk "$MEDIA/shows/$s/Season 01/$s S01E01.mp4"
  mk "$MEDIA/shows/$s/Season 01/$s S01E02.mp4"
  mk "$MEDIA/shows/$s/Season 02/$s S02E01.mp4"
done
if [ "${SP5_INTERNET:-1}" = 1 ]; then
  mk "$MEDIA/franchise/Toy Story (1995)/Toy Story (1995).mp4"
  mk "$MEDIA/franchise/Toy Story 2 (1999)/Toy Story 2 (1999).mp4"
fi
chmod -R a+rX "$MEDIA"

# ---- container -----------------------------------------------------------------------------------
docker volume create "${NAME}-config" >/dev/null
docker volume create "${NAME}-cache" >/dev/null
nice docker run -d --name "$NAME" --cpus 2 --memory 1.5g -p "127.0.0.1:${PORT}:8096" \
  -v "${NAME}-config:/config" -v "${NAME}-cache:/cache" -v "$MEDIA:/media" "$IMAGE" >/dev/null || exit 1
# Info/Public answers while the server is still starting; the startup wizard answers 503 until ready.
for _ in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/Startup/Configuration")" = 200 ] && break
  sleep 3
done
VER="$(curl -sf "$BASE/System/Info/Public" | jq -r '.Version // .version')"
echo "# image=$IMAGE server=$VER"

# startup wizard + throwaway admin
curl -sS -X POST "$BASE/Startup/Configuration" -H 'Content-Type: application/json' \
  -d '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}' >/dev/null
curl -sS "$BASE/Startup/User" >/dev/null
curl -sS -X POST "$BASE/Startup/User" -H 'Content-Type: application/json' \
  -d "{\"Name\":\"sp5admin\",\"Password\":\"$PASS\"}" >/dev/null
curl -sS -X POST "$BASE/Startup/Complete" -H 'Content-Length: 0' >/dev/null
AUTH="$(curl -sS -X POST "$BASE/Users/AuthenticateByName" -H "$AUTHH" -H 'Content-Type: application/json' \
  -d "{\"Username\":\"sp5admin\",\"Pw\":\"$PASS\"}")"
TOK="$(jq -r .AccessToken <<<"$AUTH")"
UID_="$(jq -r .User.Id <<<"$AUTH")"
if [ -z "$TOK" ] || [ "$TOK" = null ]; then echo "login failed" >&2; exit 1; fi

addlib() { # name type path [autoCollections] [internet]
  # Internet providers are off unless asked: TMDB otherwise renames the synthetic titles to real films.
  local types='[{"Type":"Movie","MetadataFetchers":[],"ImageFetchers":[]},{"Type":"Series","MetadataFetchers":[],"ImageFetchers":[]},{"Type":"Season","MetadataFetchers":[],"ImageFetchers":[]},{"Type":"Episode","MetadataFetchers":[],"ImageFetchers":[]}]'
  [ "${5:-false}" = true ] && types='[]'
  api POST "/Library/VirtualFolders?name=$1&collectionType=$2&refreshLibrary=false" \
    "{\"LibraryOptions\":{\"EnableInternetProviders\":${5:-false},\"AutomaticallyAddToCollection\":${4:-false},\"PathInfos\":[{\"Path\":\"$3\"}],\"TypeOptions\":$types}}" >/dev/null
}
addlib Movies movies /media/movies
addlib Shows tvshows /media/shows
scan

A=$(byname Movie "Alpha Movie"); B=$(byname Movie "Beta Movie"); G=$(byname Movie "Gamma Movie")
D=$(byname Movie "Delta Movie"); E=$(byname Movie "Epsilon Movie"); Z=$(byname Movie "Zeta Movie")
S1=$(byname Series "Show One"); S2=$(byname Series "Show Two")
api GET "/Items?Recursive=true&IncludeItemTypes=Movie,Series&collapseBoxSetItems=false" | jq -r '.Items[]? | "# item: \(.Type) \(.Name)"'
echo "# seeded: movies=$(ids Movie | count) series=$(ids Series | count) episodes=$(ids Episode | count)"
if [ -z "$A" ] || [ -z "$S1" ] || [ -z "$S2" ] || [ -z "$Z" ]; then echo "seeding failed" >&2; exit 1; fi

# ================= Q1 collections ================================================================
C1=$(api POST "/Collections?name=SP5%20Alpha%20Set&ids=$A" | jq -r .Id)
settle
# (a) add a member
T=$(stamp); api POST "/Collections/$C1/Items?ids=$B" >/dev/null; settle
r=$(inbump BoxSet "$T" "$C1"); mb=$(inbump Movie "$T" "$B")
case "$(members "$C1")" in *"$B"*) mm=1;; *) mm=0;; esac
rec "Q1a add member: BoxSet returned" "$(yn "$r")" "member listed=$(yn $mm); member item itself returned=$(yn "$mb")"
# (a) remove a member
T=$(stamp); api DELETE "/Collections/$C1/Items?ids=$B" >/dev/null; settle
r=$(inbump BoxSet "$T" "$C1"); mb=$(inbump Movie "$T" "$B")
case "$(members "$C1")" in *"$B"*) mm=1;; *) mm=0;; esac
rec "Q1a remove member: BoxSet returned" "$(yn "$r")" "member still listed=$(yn $mm); member item itself returned=$(yn "$mb")"
# (a2) a series as a member
T=$(stamp); api POST "/Collections/$C1/Items?ids=$S1" >/dev/null; settle
r=$(inbump BoxSet "$T" "$C1")
rec "Q1a add Series member: BoxSet returned" "$(yn "$r")" "members=$(members "$C1" | wc -w)"
api POST "/Collections/$C1/Items?ids=$B" >/dev/null; settle   # leave A, S1, B in

# (b) metadata refresh of the BoxSet
before=$(members "$C1")
for mode in "MetadataRefreshMode=FullRefresh&ImageRefreshMode=FullRefresh&ReplaceAllMetadata=true&ReplaceAllImages=true:replace-all" \
            "MetadataRefreshMode=Default&ImageRefreshMode=Default:defaults" \
            ":no-parameters"; do
  q="${mode%%:*}"; label="${mode##*:}"
  T=$(stamp); refresh "$C1" "$q"
  r=$(inbump BoxSet "$T" "$C1")
  after=$(members "$C1"); if [ "$before" = "$after" ]; then same=1; else same=0; fi
  rec "Q1b refresh $label: BoxSet returned again" "$(yn "$r")" "membership unchanged=$(yn $same)"
done
# does the add's bump survive a later refresh: old cursor, add, then replace-all refresh
T0=$(stamp); api POST "/Collections/$C1/Items?ids=$G" >/dev/null; settle
refresh "$C1" "MetadataRefreshMode=FullRefresh&ReplaceAllMetadata=true"
r=$(inbump BoxSet "$T0" "$C1")
case "$(members "$C1")" in *"$G"*) mm=1;; *) mm=0;; esac
rec "Q1b add, then replace-all refresh, cursor from before the add: BoxSet returned" "$(yn "$r")" "added member listed=$(yn $mm)"

# (c) full library scan with no change
T=$(stamp); scan
r=$(inbump BoxSet "$T" "$C1")
rec "Q1c scan with nothing changed: BoxSet bumped (false positive)" "$(yn "$r")" "movies bumped=$(ids Movie "$T" | count) series bumped=$(ids Series "$T" | count) episodes bumped=$(ids Episode "$T" | count)"

# (d) member file deleted from disk, then a scan
C2=$(api POST "/Collections?name=SP5%20Delta%20Set&ids=$D,$E" | jq -r .Id); settle
T=$(stamp)
rm -rf "$MEDIA/movies/Delta Movie (2004)"; scan
r=$(inbump BoxSet "$T" "$C2")
m=$(members "$C2"); case "$m" in *"$D"*) still=1;; *) still=0;; esac
ex=$(inbump Movie "" "$D")
rec "Q1d member file deleted + scan: BoxSet bumped" "$(yn "$r")" "deleted member still in member list=$(yn $still); deleted movie still in ID listing=$(yn "$ex"); members now=$(echo "$m" | wc -w)"
T=$(stamp); scan
r=$(inbump BoxSet "$T" "$C2")
rec "Q1d second scan after the delete: BoxSet bumped" "$(yn "$r")" ""
# (d2) member deleted through the API
C3=$(api POST "/Collections?name=SP5%20Zeta%20Set&ids=$Z,$G" | jq -r .Id); settle
T=$(stamp); api DELETE "/Items/$Z" >/dev/null; settle
r=$(inbump BoxSet "$T" "$C3")
case "$(members "$C3")" in *"$Z"*) still=1;; *) still=0;; esac
rec "Q1d2 member deleted via API (DELETE /Items): BoxSet bumped" "$(yn "$r")" "deleted member still listed=$(yn $still)"

# (e) automatically add movies to collections (needs TMDB through the internet)
if [ "${SP5_INTERNET:-1}" = 1 ]; then
  addlib Franchise movies /media/franchise true true
  T=$(stamp); scan
  sleep 20; settle
  n=$(ids BoxSet "$T" | count)
  if [ "$n" -gt 0 ]; then
    rec "Q1e auto-add to collections (TMDB box sets): BoxSet created and returned" "yes" "boxsets created=$n; members=$(members "$(ids BoxSet "$T" | head -1)" | wc -w)"
  else
    nets=$(docker logs "$NAME" 2>&1 | grep -ci -E 'tmdb|themoviedb|HttpRequestException|name resolution|timed out' || true)
    rec "Q1e auto-add to collections (TMDB box sets)" "untested" "no BoxSet created; provider/network log lines=$nets"
  fi
else
  rec "Q1e auto-add to collections (TMDB box sets)" "untested" "skipped by SP5_INTERNET=0"
fi

# (g) rename a BoxSet through the full item update
T=$(stamp)
getitem "$C2" | jq '.Name = "SP5 Delta Set Renamed"' > "$WORK/item.json"
postfile "/Items/$C2" "$WORK/item.json"; settle
r=$(inbump BoxSet "$T" "$C2")
rec "Q1g rename BoxSet (POST /Items/{id}): BoxSet returned" "$(yn "$r")" "name now=$(getitem "$C2" | jq -r .Name)"

# (f) delete a BoxSet
api DELETE "/Items/$C3" >/dev/null; settle
r=$(inbump BoxSet "" "$C3")
rec "Q1f deleted BoxSet is missing from the ID listing" "$(yn $((1 - r)))" "other BoxSets still listed=$(ids BoxSet | count)"

# ================= Q2 series =====================================================================
orig=$(getitem "$S1")
rec "Q2 baseline" "info" "series genres=$(jq -c .Genres <<<"$orig") tags=$(jq -c .Tags <<<"$orig") rating=$(jq -c .OfficialRating <<<"$orig")"
# (a) edit genres, tags and rating
T=$(stamp)
jq '.Genres=["SP5Drama","SP5Comedy"] | .Tags=["sp5tag"] | .OfficialRating="TV-14"' <<<"$orig" > "$WORK/item.json"
postfile "/Items/$S1" "$WORK/item.json"; settle
r=$(inbump Series "$T" "$S1"); now_=$(getitem "$S1")
rec "Q2a edit genres/tags/rating: Series returned" "$(yn "$r")" "stored genres=$(jq -c .Genres <<<"$now_") tags=$(jq -c .Tags <<<"$now_") rating=$(jq -c .OfficialRating <<<"$now_")"
n=$(ids Episode "$T" | count)
rec "Q2c series edit also bumps its episodes" "$([ "$n" -gt 0 ] && echo yes || echo no)" "episodes returned=$n"
ep=$(ids Episode | head -1)
rec "Q2 episode view after series edit" "info" "first episode genres=$(getitem "$ep" | jq -c .Genres)"
# (b) metadata refresh of the series
for mode in "MetadataRefreshMode=FullRefresh&ImageRefreshMode=FullRefresh&ReplaceAllMetadata=true&ReplaceAllImages=true:replace-all" ":no-parameters"; do
  q="${mode%%:*}"; label="${mode##*:}"
  T=$(stamp); refresh "$S1" "$q"
  r=$(inbump Series "$T" "$S1")
  rec "Q2b series refresh $label: Series returned" "$(yn "$r")" "episodes returned=$(ids Episode "$T" | count); genres after=$(getitem "$S1" | jq -c .Genres)"
done
# (d) a new episode on disk plus a scan
mk "$MEDIA/shows/Show One (2005)/Season 01/Show One (2005) S01E03.mp4"; chmod -R a+rX "$MEDIA"
T=$(stamp); scan
r=$(inbump Series "$T" "$S1")
rec "Q2d new episode + scan: Series returned" "$(yn "$r")" "episodes returned=$(ids Episode "$T" | count)"
# (e) a series deleted from disk plus a scan
T=$(stamp); rm -rf "$MEDIA/shows/Show Two (2010)"; scan
r=$(inbump Series "" "$S2"); rb=$(inbump Series "$T" "$S2")
rec "Q2e series deleted from disk + scan: gone from the ID listing" "$(yn $((1 - r)))" "returned by MinDateLastSaved=$(yn "$rb"); episodes listed now=$(ids Episode | count)"

echo "# done: image=$IMAGE server=$VER"
