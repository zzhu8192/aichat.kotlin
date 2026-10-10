---
name: scala-review
description: Review Scala 2.13 source for this project's conventions -- Either over thrown exceptions, no Scala 3 syntax, lenient wire decoders, and comments that say why. Use when asked to review, critique, or check Scala code or a diff.
license: MIT
allowed-tools: read_to_context, grep, glob, exec
metadata:
  author: kotllm
---

Review Scala for the conventions in `CHECKLIST.md`, which sits next to this file.

Do this in order, and do not skip to an opinion before step 3.

1. Read `CHECKLIST.md` from the directory named above. It is the actual rubric;
   this file only tells you how to apply it.

2. Work out what to review. The user asked for: $ARGUMENTS
   If that named files, a directory or a git ref, review exactly that.
   If it is blank, find the changed files yourself:

       exec: git diff --name-only HEAD~1 -- '*.scala'

   If that returns nothing, use `glob` with `src/main/**/*.scala` and review the
   three most recently modified.

3. For each file, `read_to_context` it. Then use `grep` for the specific things the
   checklist calls out rather than eyeballing -- for example
   `throw new` to find exceptions crossing a module boundary, or `=>` inside a
   `given`/`extension` to find Scala 3 syntax that will not compile here.

4. Report per file, with `path:line` citations, in three buckets: **must fix**,
   **worth considering**, and **fine**. Say "fine" when it is fine; padding a
   review with invented concerns is worse than a short one.

Cite every claim with a line. A review the user cannot verify is a review they
have to redo.
