#!/bin/bash
# Run kotllm under a real pty and capture the escape stream it emits.
#
#   wsl -- bash scripts/wslrun.sh <rows> <cols> <out-file> [line]...
#   python scripts/vt.py <out-file> <rows> <cols> ["stop marker"]
#
# The REPL chrome is cursor positioning, a scroll region and erase sequences,
# and none of that is exercised by a unit test: the test JVM has no tty, and a
# JLine terminal over byte streams answers differently than a real one. Every
# chrome bug so far -- the footer erased off the screen, the cursor parked below
# the scroll region so nothing scrolled -- passed the whole suite and showed up
# here in one run.
#
# script(1) gives the child a pty, so JLine takes the same code path it takes on
# a live console; stty sizes that pty deterministically rather than inheriting
# whatever the caller has.
#
# Type commands with a *leading space* (" /help"): still parsed as commands, but
# the `/` does not land in an empty buffer, so the completion menu stays shut
# and does not fill the capture with candidate lists.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ROWS=$1
COLS=$2
OUT=$3
shift 3

INPUT=$(mktemp)
for line in "$@"; do
    printf '%s\n' "$line" >> "$INPUT"
done

cd "$ROOT" || exit 1
rm -f "$OUT"

timeout 180 script -q -e \
    -c "stty rows $ROWS cols $COLS; KOTLLM_CONFIG_DIR=$ROOT/config java -jar $ROOT/target/kotllm.jar" \
    "$OUT" < "$INPUT" > /tmp/wslrun.stdout 2>/tmp/wslrun.err
code=$?

rm -f "$INPUT"
echo "exit=$code bytes=$(wc -c < "$OUT" 2>/dev/null || echo 0)"
head -3 /tmp/wslrun.err
