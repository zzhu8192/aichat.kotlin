#!/usr/bin/env python3
"""Reconstruct a terminal screen from a captured escape stream.

    python scripts/vt.py <capture> [rows] [cols]

The REPL chrome is cursor positioning, a scroll region and erase sequences, and
none of that can be checked by asserting on strings: a test can pass with the
footer erased off the screen, which is exactly what happened once. Replaying the
bytes into a grid shows what a user would actually see.

Handles the subset kotllm and JLine emit: CUP/CUU/CUD/CUF/CUB, ED, EL, DECSTBM,
IL/DL, SU/SD, save/restore cursor, charset selection, and OSC strings. SGR
colours are parsed and discarded -- the question is always layout, not colour.

Captures come from scripts/wslrun.sh (a real pty under WSL) or from a test that
writes a JLine terminal's byte stream to a file.
"""

import io
import re
import sys

# The grid holds whatever the capture drew, which is now box-drawing characters
# rather than the ASCII the alternate charset used to reduce them to -- and a
# Windows console defaults to cp1252, so printing the screen raised
# UnicodeEncodeError and took the whole report with it. Same class of bug this
# tool exists to diagnose, so: encode what we can and mark the rest rather than
# dying, because a screen with a few substitutions still answers the question
# and a traceback answers nothing.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "target/analysis/screen.bin"
ROWS = int(sys.argv[2]) if len(sys.argv) > 2 else 24
COLS = int(sys.argv[3]) if len(sys.argv) > 3 else 80
# Optional 4th argument: stop replaying at the last occurrence of this text.
# The screen after the REPL exits is blank -- it clears the pinned rows on the
# way out -- so a mid-session checkpoint is usually what you want to look at.
STOP = sys.argv[4] if len(sys.argv) > 4 else None

data = io.open(path, "rb").read().decode("utf-8", "replace")

# script(1) wraps the session in its own header and footer lines.
if data.startswith("Script started"):
    data = data.split("\n", 1)[1]
cut = data.rfind("\nScript done")
if cut > 0:
    data = data[:cut]

if STOP:
    at = data.rfind(STOP)
    if at < 0:
        sys.exit("stop marker %r not found in the capture" % STOP)
    data = data[: at + len(STOP)]

grid = [[" "] * COLS for _ in range(ROWS)]
row = col = 0
top, bot = 0, ROWS - 1  # scroll region, 0-based inclusive
saved = (0, 0)


def scroll_up():
    del grid[top]
    grid.insert(bot, [" "] * COLS)


def line_feed():
    global row
    if row == bot:
        scroll_up()
    elif row < ROWS - 1:
        row += 1


i, n = 0, len(data)
while i < n:
    ch = data[i]

    if ch == "\x1b":
        m = re.match(r"\x1b\[([0-9;?]*)([A-Za-z])", data[i:])
        if m:
            params, cmd = m.group(1), m.group(2)
            nums = [int(x) for x in params.split(";") if x.isdigit()]
            a = nums[0] if nums else None
            if cmd == "H" or cmd == "f":
                row = (nums[0] - 1) if len(nums) > 0 else 0
                col = (nums[1] - 1) if len(nums) > 1 else 0
                row = max(0, min(ROWS - 1, row))
                col = max(0, min(COLS - 1, col))
            elif cmd == "A":
                row = max(0, row - (a or 1))
            elif cmd == "B":
                row = min(ROWS - 1, row + (a or 1))
            elif cmd == "C":
                col = min(COLS - 1, col + (a or 1))
            elif cmd == "D":
                col = max(0, col - (a or 1))
            elif cmd == "J":
                mode = a or 0
                if mode == 0:
                    for c in range(col, COLS):
                        grid[row][c] = " "
                    for rr in range(row + 1, ROWS):
                        grid[rr] = [" "] * COLS
                elif mode == 1:
                    for c in range(0, col + 1):
                        grid[row][c] = " "
                    for rr in range(0, row):
                        grid[rr] = [" "] * COLS
                else:
                    for rr in range(ROWS):
                        grid[rr] = [" "] * COLS
            elif cmd == "K":
                mode = a or 0
                if mode == 0:
                    for c in range(col, COLS):
                        grid[row][c] = " "
                elif mode == 1:
                    for c in range(0, col + 1):
                        grid[row][c] = " "
                else:
                    grid[row] = [" "] * COLS
            elif cmd == "r":
                top = (nums[0] - 1) if len(nums) > 0 else 0
                bot = (nums[1] - 1) if len(nums) > 1 else ROWS - 1
                top, bot = max(0, top), min(ROWS - 1, bot)
            elif cmd == "L":
                for _ in range(a or 1):
                    grid.insert(row, [" "] * COLS)
                    del grid[bot]
            elif cmd == "M":
                for _ in range(a or 1):
                    del grid[row]
                    grid.insert(bot, [" "] * COLS)
            elif cmd == "S":
                for _ in range(a or 1):
                    scroll_up()
            elif cmd == "T":
                for _ in range(a or 1):
                    grid.insert(top, [" "] * COLS)
                    del grid[bot]
            i += m.end()
            continue

        m = re.match(r"\x1b([78])", data[i:])
        if m:
            if m.group(1) == "7":
                saved = (row, col)
            else:
                row, col = saved
            i += m.end()
            continue

        m = re.match(r"\x1b\(.", data[i:])  # charset selection
        if m:
            i += m.end()
            continue

        m = re.match(r"\x1b\][^\x07\x1b]*(\x07|\x1b\\)", data[i:])  # OSC
        if m:
            i += m.end()
            continue

        i += 1
        continue

    if ch == "\n":
        line_feed()
        i += 1
        continue
    if ch == "\r":
        col = 0
        i += 1
        continue
    if ch == "\x08":
        col = max(0, col - 1)
        i += 1
        continue
    if ch == "\x07":
        i += 1
        continue

    if col >= COLS:
        col = 0
        line_feed()
    grid[row][col] = ch
    col += 1
    i += 1

print("    +" + "-" * COLS + "+")
for idx, line in enumerate(grid):
    text = "".join(line).rstrip()
    print("%3d |%-*s|%s" % (idx, COLS, text, "<" if idx == row else " "))
print("    +" + "-" * COLS + "+")
print("cursor row=%d col=%d   scroll region=%d..%d" % (row, col, top, bot))
