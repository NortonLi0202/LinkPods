# Multi-generation AirPods Implementation Plan

> **For agentic workers:** Execute the following tasks in order using the project's existing Java/Python host tests. Codex is planner/reviewer; DeepSeek Harness DeepSeek-V4.1-Flash is implementer. Write tests before behavior changes and run them to see the intended failure.

**Goal:** Support the pinned LibrePods Android model registry and capability-aware existing controls across multiple AirPods generations.

**Architecture:** Small Java model registry and bounded INFORMATION packet parser feed the existing session state and immutable snapshot. UI and command queue share model-aware eligibility, while observed settings still decide whether a command is usable.

**Tech Stack:** Java 8 Android Views, existing Python/JDK/Node host runner.

**Spec:** `docs/superpowers/specs/2026-11-17-multi-generation-airpods-design.md`

## Global constraints

- Android API32+, ARM64, lp.pods, build101 unchanged.
- Read README.md, project.json, docs/release/v1.0.md, docs/issues/README.md, AGENTS.md first.
- No device operations, no credentials/logging secrets, no new binary dependencies or assets.
- Do not alter native Bluetooth adaptation, Root/payload policies or routing behavior.
- Use pinned LibrePods `89356c08cea3db0b6b54792d9329d09a87cc85fa`. Read provided upstream reference files, not model knowledge from memory.
- Codex handles commits, push, PR and signed APK build. Harness must not commit/push/merge/publish or touch other worktrees.

## Review focus

- Renamed Bluetooth devices do not identify a model.
- Known basic AirPods cannot receive ANC/adaptive/conversation/swipe commands even after an unexpected setting report.
- Unknown models retain conservative report-driven basic operation without being called supported.
- Model metadata never survives session/target replacement.
- Malformed INFORMATION packets cannot erase a valid model or manufacture an identity from a serial/name string.

### Task 1: Registry and information parser

Files: create `apps/android/src/local/airpods/handoffprobe/AirPodsModels.java`, `AirPodsInformation.java` (or equivalently focused names), matching tests under `apps/android/tests`; modify `tools/project.py` host test source/test lists.

- [ ] Read pinned upstream AirPods.kt, AACPManager.kt INFORMATION parser, and relevant AirPodsService.kt handling.
- [ ] Add a pure Java immutable registry keyed by exact upstream A-number; include all upstream-listed aliases and capability differences. No mutable public collections.
- [ ] Add a pure parser for normalized `04 00 04 00 1d 00` information frames, preserving actual wire field order. Establish the upstream prefix from code/reference before translating; do not find a random A-number substring in arbitrary packet data. Parse model number only from its defined field. Only expose necessary name/model/version metadata; do not collect/log serial numbers.
- [ ] Tests: all registry aliases; normal and repeated headers; realistic synthetic information layout; empty/truncated/wrong opcode/malformed data; unknown A-number; display name containing a known model must not spoof identity.
- [ ] Run tests red then implement minimal logic and run green.

### Task 2: Wire model metadata into live state and command eligibility

Files: `EarbudCommands.java`, `EarbudSnapshot.java`, `AapPackets.java` only if the upstream actually requests information, existing relevant host tests.

- [ ] INFORMATION notifications accepted by `EarbudCommands.State.receive` update the current snapshot without disrupting scalar setting and battery parsing.
- [ ] New session/disconnect/target replacement clears identity; malformed information leaves existing valid state intact.
- [ ] Share model-aware eligibility with snapshot/UI. Cover all existing scalar settings with a clear mapping; do not infer unrelated capabilities such as hearing aid from ANC.
- [ ] Known AirPods1/2/3/4-no-ANC reject listening modes; Pro1 rejects adaptive/conversation/swipe, Pro2/3 allow only observed existing settings. Model support alone does not permit a write. Unknown model behavior remains report-driven.
- [ ] Keep existing observed off-mode requirement and feature confirmation flow. Test queue rejection as well as UI-facing flags, reconnect and missing battery components.
- [ ] Add no speculative information request packet; reuse existing notifications if upstream does not send one.

### Task 3: UI and public documentation

Files: `LinkPodsUi.java`, `PodControls.java` if needed, source/packaging tests where appropriate, README.md, docs/release/v1.0.md, THIRD_PARTY.md or existing notices, and new `docs/airpods-models.md`.

- [ ] Show reported model identity with generic unknown fallback; do not use Bluetooth name to pick a registry entry.
- [ ] Gate/hide incompatible existing settings and mode choices, with unsupported distinguished from waiting-for-report. Preserve navigation and current pending/error feedback.
- [ ] Pro3 artwork is only shown for identified Pro3; use simple text/native presentation for other/unknown models. No new images/assets or UI redesign.
- [ ] Document exactly which upstream models are mapped, which feature differences are honored, and lack of device validation. Max remains unadapted unless pinned upstream source evidence is provided; do not silently label it supported.
- [ ] Add GPL/upstream attribution to ported Java code and source notices.
- [ ] Run `python tools/project.py check` and `python tools/project.py test`; report exact results and any environment failure separately. Set TEMP/TMP to this worktree's apps/android/build/temp if needed.
- [ ] Finish with changed files, test evidence, and any unresolved concern. Do not edit the plan/spec to weaken requirements.
