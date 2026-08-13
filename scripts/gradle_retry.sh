#!/usr/bin/env bash
#
# Runs a Gradle command, retrying only when it failed to *reach* its dependencies.
#
# Maven Central rate-limits CI runners. On 2026-08-13 a wholly green commit was reported as a
# failure with eighteen identical causes, every one of them `429 Too Many Requests` against
# repo.maven.apache.org — Gradle never resolved the Android plugin, so it never compiled a line
# and not one of the 507 tests ran. The Release workflow resolved the same dependencies a minute
# later and went green, which is what makes it transient rather than ours. The mail said the
# build failed and there was nothing in it to act on: the same shape of false alarm as the
# coverage-badge race in ci.yml, and it deserves the same treatment.
#
# What is deliberately NOT retried is a test failure. Retrying one would treble the time to a
# real red and, worse, quietly turn a flaky test green — the retry is here to survive somebody
# else's rate limiter, not to have another go at our own bugs. So the exit status is handed back
# untouched unless the log carries a resolution failure.
#
# Usage: scripts/gradle_retry.sh test
#        scripts/gradle_retry.sh :app:assembleRelease
set -uo pipefail

MAX_ATTEMPTS=3

log=$(mktemp)
trap 'rm -f "$log"' EXIT

for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  ./gradlew "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}

  if [ "$status" -eq 0 ]; then
    exit 0
  fi

  # The signatures of not having reached a repository. Anything else — a failing test, a
  # compile error — is ours and is reported on the first attempt.
  if ! grep -qE 'Too Many Requests|Could not (resolve|GET|download|HEAD)|Read timed out|Connection (reset|timed out)|Network is unreachable|502 Bad Gateway|503 Service Unavailable' "$log"; then
    echo "gradle_retry: this failure is not dependency resolution, so it stands. Not retrying."
    exit "$status"
  fi

  if [ "$attempt" -eq "$MAX_ATTEMPTS" ]; then
    echo "gradle_retry: still could not reach the dependency repositories after $MAX_ATTEMPTS attempts."
    exit "$status"
  fi

  # A widening gap, as everywhere else that this project retries a network call: a rate limiter
  # that just said no is not going to say yes a second later.
  gap=$((attempt * 30))
  echo "gradle_retry: could not reach the dependency repositories (attempt $attempt of $MAX_ATTEMPTS); waiting ${gap}s."
  sleep "$gap"
done
