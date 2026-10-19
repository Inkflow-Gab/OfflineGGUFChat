#!/usr/bin/env bash
# Post — or update — a single CI failure digest comment on the pull request that
# triggered the current run.
#
# Actions stores job logs on a host that is not always reachable (sandboxes with a
# narrow network allowlist, corporate proxies, expired artifacts). The comment is
# the reliable channel: it survives, it is readable from `gh api`, and it keeps the
# whole failure history in one place instead of a wall of bot comments.
#
# usage: scripts/ci-digest.sh [log-file] [heading]
#
# Needs GH_TOKEN, and reads GITHUB_REPOSITORY / GITHUB_RUN_ID / GITHUB_JOB /
# GITHUB_SERVER_URL / GITHUB_PR_NUMBER from the environment.

set -uo pipefail

log=${1:-build.log}
heading=${2:-CI failed}
marker='<!-- offlinegguf-ci-digest -->'

# The Kotlin compiler prefixes diagnostics with "e: " (errors) and "w: " (warnings).
ERROR_RE='^e: '
# Everything else worth showing: task failures, Gradle's summary, C/C++ compiler
# errors, JVM assertion output, and the native test harness.
CONTEXT_RE='error: |^> Task .*FAILED|FAILURE:|AssertionError|expected:|Caused by:|What went wrong|A problem occurred|\[ FAILED \]'

if [ ! -f "$log" ]; then
  echo "ci-digest: nothing to summarise, $log is missing" >&2
  exit 0
fi

stamp=$(date -u +'%Y-%m-%d %H:%M UTC')
run_url="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}"

{
  echo "$marker"
  echo "### $heading"
  echo
  echo "Job \`${GITHUB_JOB:-unknown}\` · [run ${GITHUB_RUN_ID:-?}](${run_url}) · $stamp"
  echo

  errors=$(grep -E "$ERROR_RE" "$log" | sed 's|file://||' | sort -u || true)
  if [ -n "$errors" ]; then
    total=$(printf '%s\n' "$errors" | wc -l | tr -d ' ')
    echo "**$total compiler error(s)** — the newest build overwrites this comment."
    echo
    echo '```text'
    printf '%s\n' "$errors" | head -250
    if [ "$total" -gt 250 ]; then
      echo "... and $((total - 250)) more"
    fi
    echo '```'
    echo
  fi

  context=$(grep -E "$CONTEXT_RE" "$log" | sed 's|file://||' | sort -u || true)
  if [ -n "$context" ]; then
    echo '<details><summary>Task output</summary>'
    echo
    echo '```text'
    printf '%s\n' "$context" | head -120
    echo '```'
    echo
    echo '</details>'
  fi
} > digest.md

pr="${GITHUB_PR_NUMBER:-}"
if [ -z "$pr" ]; then
  echo "ci-digest: no pull request number; wrote digest.md only"
  exit 0
fi

existing=$(gh api "repos/$GITHUB_REPOSITORY/issues/$pr/comments" --paginate \
  --jq '[.[] | select(.body | startswith("<!-- offlinegguf-ci-digest -->")) | .id] | .[-1] // empty' \
  2>/dev/null || true)

if [ -n "$existing" ]; then
  gh api -X PATCH "repos/$GITHUB_REPOSITORY/issues/comments/$existing" \
    -f body="$(cat digest.md)" --silent
  echo "ci-digest: updated comment $existing on PR #$pr"
else
  gh pr comment "$pr" --body-file digest.md
  echo "ci-digest: opened a new digest comment on PR #$pr"
fi
