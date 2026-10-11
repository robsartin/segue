#!/bin/sh
# Export the Music app's per-artist listening to CSV on stdout (issue #350).
#
#   scripts/export-apple-music.sh > "$HOME/music.csv"
#
# macOS only. Reads the library through AppleScript and writes NOTHING back to it. The output is one
# row per artist, aggregated over tracks: artist,play_count,loved,favorited.
#
# THE OUTPUT IS PERSONAL DATA (ADR 33, issue #37): keep it outside the working tree. This repository
# is public. Nothing in it should ever be a real artist or a real count.
#
# Not exercised by CI, which has no Music app: the first run on a Mac is its test.
set -eu

if [ "$(uname -s)" != "Darwin" ]; then
  echo "export-apple-music.sh needs macOS and the Music app" >&2
  exit 1
fi

osascript <<'APPLESCRIPT' | awk -F '\t' '
  BEGIN { print "artist,play_count,loved,favorited" }
  NF >= 4 && $1 != "" {
    plays[$1] += $2
    if ($3 == "true") loved[$1] = 1
    if ($4 == "true") fav[$1] = 1
  }
  END {
    for (a in plays) {
      name = a
      gsub(/"/, "\"\"", name)
      printf "\"%s\",%d,%s,%s\n", name, plays[a], (a in loved) ? "true" : "false", (a in fav) ? "true" : "false"
    }
  }'
tell application "Music"
  set theTracks to every track of library playlist 1
  set artistNames to artist of theTracks
  set playCounts to played count of theTracks
  set lovedFlags to loved of theTracks
  set favFlags to lovedFlags
  try
    set favFlags to favorited of theTracks
  end try
  set out to ""
  repeat with i from 1 to count of artistNames
    set out to out & (item i of artistNames) & tab & (item i of playCounts) & tab & (item i of lovedFlags) & tab & (item i of favFlags) & linefeed
  end repeat
  return out
end tell
APPLESCRIPT
