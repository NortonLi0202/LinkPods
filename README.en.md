# LinkPods

[简体中文](README.md) | English

LinkPods is an AirPods companion application for Android providing a responsive tablet split-pane / phone single-column native UI, custom-drawn sliders, current Android media volume controls, and decoupled data paths for earbud control and optional cross-device handoff.

The current version is the **v1.1 / build102 / lp.pods prerelease**. APKs and checksums are available from [GitHub Releases](https://github.com/NortonLi0202/LinkPods/releases/tag/v1.1). Git tracks source and required build resources; legacy experimental history, APKs, private keys, and device logs are excluded. Phase 1 work for standalone earbud control (`CONTROL_ONLY`), routing side-effect isolation, and separated session status presentation is implemented in source and covered by host tests; physical-device validation of actual earbud behavior, bidirectional audio, and cross-device stability (starting with the `SM-X810` baseline matrix) is still pending. See [v1.1 Release Notes](docs/release/v1.1.md) and [Known Issues](docs/issues/README.md) for current limitations and acceptance criteria.

## Features & Requirements

- **Direct-first connection**: Connects directly to earbuds first without checking or requesting Root when direct access works. System adapter matching and existing Root reuse occur only when a known compatibility gap exists or when the user manually prepares the bridge; temporary Root always requires separate confirmation.
- **Adaptive native UI**: Switches layout by window width, supports light/dark themes, custom draggable noise-control capsules, and pill toggles, and displays **Earbud Control**, **System Audio**, and **Auto Handoff** states separately.
- **Honest status reporting**: Displays earbud-reported battery levels and settings; unqueried (`尚未读取` / not yet read), pending confirmation, and unsupported states are strictly separated—no placeholder data is fabricated.
- **Decoupled session modes (`CONTROL_ONLY` / `AUTO_HANDOFF`)**: Both modes share a single AACP session. Users can connect, query, and control earbuds without granting media-session notification access or enabling automatic handoff, and downgrading or running in `CONTROL_ONLY` will not pause media or disconnect A2DP. Some advanced settings and device validation are still in progress.
- **Multi-generation AirPods identification**: Identifies supported AirPods generations from the dedicated AACP `INFORMATION` model field rather than the Bluetooth display name. Known models expose only controls mapped from their capability table, while unknown models retain generic report-driven behavior. See [AirPods Models](docs/airpods-models.md) for the model table and boundaries.
- **Testing scope**: Session policies, side-effect isolation, and multi-generation model gating are covered by host tests, but physical-device and per-model acceptance testing have not yet been completed; AirPods Max is outside the pinned upstream Android registry and is treated as an unknown model.
- **Platform baseline**: Android 12L (API 32)+, ARM64. Meeting the minimum SDK version or having Root access does not imply a matching Bluetooth adapter is available.

See [Architecture & Adapters](docs/adapters.md) for connection decisions and adapter extension rules. The registry currently includes only one real system adapter; mock multi-adapter host tests do not imply support for additional devices.

## Build & Checks

```powershell
python tools/project.py check
python tools/project.py test
```

Source checks and host tests require no connected device. Building an APK additionally requires Android SDK/NDK/JDK, pinned local binary dependencies, and your own signing configuration; these inputs are neither committed nor implicitly downloaded.

```powershell
python tools/project.py check --with-local-inputs
& .\apps\android\build.ps1
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for full instructions.

## Repository Layout

| Path | Contents |
| --- | --- |
| apps/android | Primary Android application, native UI, resources, and tests |
| native/bluetooth | Native bridge templates, adapter definitions, and host tests |
| compatibility | Exact device/build matching rules and offline capability classification |
| third_party/sources | Third-party sources, patches, and pinned origins required for builds |
| third_party/payloads | SHA-256 manifests for local inputs; binaries are not tracked |
| tools | Project check, release snapshot, and build-input tooling |
| docs | Release notes, design plans, and known issues |

See [apps/android/LICENSE](apps/android/LICENSE) for application licensing and [THIRD_PARTY.md](THIRD_PARTY.md) for third-party component origins and distribution review scope.
