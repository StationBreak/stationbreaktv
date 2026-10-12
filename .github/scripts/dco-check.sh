#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# DCO check. Reads a PR's commits on stdin, as a JSON array shaped like
# GET /repos/{owner}/{repo}/pulls/{n}/commits, and fails if any commit lacks a Signed-off-by: trailer.
#
# The only exemption is a renovate[bot] commit: the author must be the renovate[bot] account (login and
# type Bot) AND the commit must have a GitHub-verified signature. GitHub links a commit to an account by its
# author email, which anyone can set, so the login alone proves nothing.
#
# Usage: gh api --paginate --slurp "repos/OWNER/REPO/pulls/N/commits" | jq 'add' | bash dco-check.sh
set -euo pipefail

# The API returns at most 250 commits for a PR. Beyond that some commits can't be checked, so fail closed.
MAX_COMMITS=250

commits="$(cat)"

if ! jq -e 'type == "array"' >/dev/null 2>&1 <<<"$commits"; then
  echo "::error::DCO: expected a JSON array of commits on stdin"
  exit 1
fi

count="$(jq 'length' <<<"$commits")"
if [ "$count" -ge "$MAX_COMMITS" ]; then
  echo "::error::DCO: the PR has $count commits; the API returns at most $MAX_COMMITS, so not every commit can be checked. Squash or split the PR."
  exit 1
fi

failing="$(jq -r '
  .[]
  | select(
      ((.commit.message // "") | split("\n") | any(test("^Signed-off-by: .+ <.+>$"))) | not
    )
  | select(
      (.author.login? == "renovate[bot]"
        and .author.type? == "Bot"
        and .commit.verification.verified? == true) | not
    )
  | .sha
' <<<"$commits")"

if [ -n "$failing" ]; then
  while read -r sha; do
    echo "::error::DCO: commit $sha has no Signed-off-by: trailer. Sign off with 'git commit -s' (see CONTRIBUTING.md)."
  done <<<"$failing"
  exit 1
fi

echo "DCO: all $count commits are signed off (or are verified renovate[bot] commits)."
