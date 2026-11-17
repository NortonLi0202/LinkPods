# Multi-generation AirPods design

The user requested that Codex plan/review and DeepSeek Harness DeepSeek-V4.1-Flash implement multi-generation AirPods support using LibrePods, then commit and open a PR. This delegates ordinary implementation decisions; no device operations or release are authorized.

## Approach

Port the model-number registry and capability sets from LibrePods commit `89356c08cea3db0b6b54792d9329d09a87cc85fa`, `android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt`. Consume AACP INFORMATION (`0x1d`) notifications following the wire layout in `bluetooth/AACPManager.kt`. Translate only the needed logic to the existing Java app. Keep its native View UI and direct-first connection policy.

Alternatives: a user-selected model/name heuristic would misidentify renamed devices; importing the entire Kotlin application would replace the existing architecture. The selected approach is a small Java port of the upstream registry and protocol handling.

## Scope and behavior

- Include every model number in that upstream Android registry (AirPods 1/2/3/4 and ANC variant, AirPods 5 variants, Pro 1/2 variants/3). Do not invent AirPods Max entries: the pinned Android registry does not contain them. Document this distinction.
- Model identity comes from the earbud's information notification, never its Bluetooth display name. Unknown models retain generic identity and existing report-driven basic controls.
- Add immutable model metadata/capabilities to the existing session snapshot. Reset identity on new session, disconnect and target changes, so a previous earbud's capabilities cannot leak into another session.
- A known model restricts relevant controls by its upstream capability set. Model eligibility never substitutes for an actual setting report; writes still require current-session state, readiness, value validation and pending confirmation. Unknown models may use existing report-driven controls, but no unobserved advanced feature is enabled merely by a model default.
- Gates apply at the command queue as well as the UI. Listening modes must distinguish ANC/transparency/off from adaptive audio; receiving one mode must not grant unsupported adaptive mode. Preserve conservative off-mode behavior until observed.
- Keep battery parsing generic. A model may omit a component; do not fabricate values. Unknown or unsupported features must be distinguished from not-yet-read state in UI text.
- Remove misleading Pro 3 product artwork for other/unknown models using a simple generic native presentation; do not import new product images or add licensed assets. Existing Pro 3 images can remain for positively identified Pro 3.
- Do not implement every upstream feature: only gate existing LinkPods controls. No new spatial audio, hearing aid, HRM or firmware update claims.
- No change to Bluetooth bridge/Root/payload matching, routing ownership policy, package name, SDK floor or build101. Model coverage is protocol-level adaptation, not hardware validation or a larger phone compatibility list.
- Retain GPL attribution and record exact upstream source paths and commit for the port.

## Acceptance

Host tests cover the exact registry, packet parsing, model capability restrictions, unknown-model behavior, truncated/malformed information packets, repeated headers, missing battery components and session/target resets. Full project checks and host tests pass, Android APK builds and its existing signature/metadata/payload checks pass. No physical earbud result is claimed. Codex reviews the diff and opens a PR from this feature branch.
