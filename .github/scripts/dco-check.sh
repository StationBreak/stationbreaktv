#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
#
# DCO check. Reads a PR's commits on stdin, as a JSON array shaped like
# GET /repos/{owner}/{repo}/pulls/{n}/commits, and fails if any commit lacks a Signed-off-by: trailer by its author.
#
# The only exemption is a Renovate commit, and only when all of these hold:
# - the PR was opened by renovate[bot] (PR_AUTHOR), which only the Renovate app can do;
# - the commit's author is the renovate[bot] account (login and type Bot);
# - its committer is web-flow, and its signature is GitHub-verified.
# GitHub links a commit to an account by its author email, which anyone can set, and "verified" attests the
# committer's signature, not the author's. Hosted Renovate creates commits through the API, so GitHub signs
# them as web-flow; a commit with a forged author signed by someone's own key fails that check. Anyone with
# write access can also make web-flow commits (web UI or API), which is accepted: they could merge anyway.
#
# Usage: gh api --paginate --slurp "repos/OWNER/REPO/pulls/N/commits" | jq 'add' \
#          | PR_AUTHOR=<login of the PR's author> bash dco-check.sh
set -euo pipefail

# The API returns at most 250 commits for a PR. Beyond that some commits can't be checked, so fail closed.
# Exactly 250 fails too: the list may have been cut off there.
MAX_COMMITS=250
pr_author="${PR_AUTHOR:-}"

commits="$(cat)"

if ! jq -e 'type == "array"' >/dev/null 2>&1 <<<"$commits"; then
  echo "::error::DCO: expected a JSON array of commits on stdin"
  exit 1
fi

count="$(jq 'length' <<<"$commits")"
# A PR always has at least one commit, so an empty list means the API call went wrong.
if [ "$count" -eq 0 ]; then
  echo "::error::DCO: no commits found for this PR"
  exit 1
fi
if [ "$count" -ge "$MAX_COMMITS" ]; then
  echo "::error::DCO: the PR has $count commits; the API returns at most $MAX_COMMITS, so not every commit can be checked. Squash or split the PR."
  exit 1
fi

# A commit is signed off when its final paragraph (git's trailer block) has a Signed-off-by: whose email is the commit
# author's, compared case-insensitively, as the DCO GitHub app requires: A can't sign off for B. CR is stripped first,
# so a CRLF message isn't failed for its line endings. Trailing blank lines don't start a new paragraph.
# shortcut: the last paragraph stands in for git's trailer rules (which also accept a mixed block); upgrade to
# `git interpret-trailers --parse` if a real sign-off is ever refused.
failing="$(jq -r --arg pr_author "$pr_author" '
  def trailer_lines:
    (.commit.message // "") | gsub("\r"; "") | sub("[ \t\n]+$"; "")
    | [splits("\n[ \t]*\n")] | last // "" | split("\n");
  def signoff_emails:
    [trailer_lines[] | capture("^Signed-off-by: .+ <(?<email>[^<>]+)>$") | .email | ascii_downcase];
  .[]
  | select(
      ($pr_author == "renovate[bot]"
        and .author.login == "renovate[bot]"
        and .author.type == "Bot"
        and .committer.login == "web-flow"
        and .commit.verification.verified == true) | not
    )
  | ((.commit.author.email // "") | ascii_downcase) as $author
  | signoff_emails as $emails
  | if ($emails | length) == 0 then "\(.sha)\tnone"
    elif ($author == "" or ($emails | index($author)) == null) then "\(.sha)\tmismatch"
    else empty end
' <<<"$commits")"

if [ -n "$failing" ]; then
  while IFS=$'\t' read -r sha why; do
    if [ "$why" = none ]; then
      echo "::error::DCO: commit $sha has no Signed-off-by: trailer. Sign off with 'git commit -s' (see CONTRIBUTING.md)."
    else
      echo "::error::DCO: commit $sha has a Signed-off-by: trailer, but its email doesn't match the commit author's. Sign off as the commit's author with 'git commit -s' (see CONTRIBUTING.md)."
    fi
  done <<<"$failing"
  exit 1
fi

echo "DCO: all $count commits are signed off (or are verified renovate[bot] commits)."
