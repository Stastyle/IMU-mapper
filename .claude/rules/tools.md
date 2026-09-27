---
paths:
  - "tools/**"
---

# tools/ rules

- **Kotlin is the reference.** `replay.py` mirrors `pipeline/.../pdr` and `post` under the same
  class and function names. When they disagree, fix `replay.py`.
- **Keep the sequential filters as plain per-sample loops in Kotlin order:** Madgwick, the step
  band-pass, the barometer low-pass, the yaw-correction average and the carry-change low-pass.
  Vectorising them changes the rounding.
- **Known gaps** are listed under "Not covered" in `tools/README.md`. Update that list when you
  port something.
- **Windows:**
  - Run `python`, not `python3`, which is the Microsoft Store stub.
  - Add `-X utf8` when a log or a `--compare` JSON has non-ASCII annotation notes.
  - `--no-plots` avoids needing matplotlib.
- **Compare against a Kotlin result.** Two ways to get one:
  - Export a trip from the app (long-press it in the trip list, then Export as ZIP) and unzip it.
    By default the replay uses the recording-time `LogMeta` config, so pass the run's `config` from
    `results/run-<n>.json` with `--set`, except `headingAxis` and `baroStillGapS`, which it
    rejects.
  - Dump one from a temporary pipeline test that uses `log.meta!!.config` (see
    `.claude/rules/pipeline.md`), which already matches.

  For a VIO trip, compare with the Debug screen's "Run PDR only" result.
- **Output:** without `--out`, output goes to `<log>_replay/` next to the log, which is
  git-ignored.
