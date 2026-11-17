# LinkPods

[简体中文](README.md) | English

**LinkPods** is a native AirPods companion application for Android, built with a focus on Samsung Galaxy devices and One UI integration. Instead of spoofing AirPods as Galaxy Buds or requiring Root by default, it separates standalone AACP earbud control, exact-matched compatibility bridging, and cross-device audio handoff into modular layers for reliable daily use.

> **Current Status**: The current version is the **v1.1 / build102 (`lp.pods`) prerelease**, with APKs and checksums available from [GitHub Releases](https://github.com/NortonLi0202/LinkPods/releases/tag/v1.1). Git tracks source and required build resources only; legacy experimental history, APKs, private keys, and device logs are excluded. Phase 1 work for standalone earbud control (`CONTROL_ONLY`), routing side-effect isolation, and multi-generation model capability gating is implemented in source and covered by host tests; physical-device validation of actual earbud audio, bidirectional handoff, and cross-device stability (starting with the `SM-X810` baseline) is still pending. See [v1.1 Release Notes](docs/release/v1.1.md) and [Known Issues](docs/issues/README.md) for details.

## Features

- **Adaptive Native UI**: Switches between tablet split-pane and phone single-column layouts based on window width, with light/dark themes, custom draggable noise-control capsules, pill toggles, and live Android media volume controls—presenting **Earbud Control**, **System Audio**, and **Auto Handoff** states separately.
- **Decoupled Control & Handoff**: `CONTROL_ONLY` and `AUTO_HANDOFF` share a single AACP session. Users can connect, inspect status, and control noise modes without granting media-session notification access or enabling auto-handoff; switching or downgrading modes never pauses playback or drops an active A2DP link.
- **Direct-First Connection & On-Demand Bridging**: Connects directly first without checking or requesting Root when direct access works. Exact-matched system adapters and existing Root reuse are only engaged on explicit compatibility gaps or manual bridge preparation; temporary Root always requires separate user confirmation.
- **AACP Protocol Identification & Status Reporting**: Identifies supported AirPods generations from the dedicated AACP `INFORMATION` model field rather than Bluetooth display names and gates controls by each model's capability table. Unknown models (including AirPods Max, which is outside the pinned upstream registry) retain generic report-driven controls, and battery/setting states strictly distinguish *not yet read*, *pending confirmation*, and *unsupported*.

## Requirements

- **Platform**: Android 12L (API 32)+, ARM64
- **Compatibility Boundary**: Meeting the minimum SDK version or having Root access does not imply a matching Bluetooth adapter is available; the adapter registry currently contains one real system adapter. See [Architecture & Adapters](docs/adapters.md).

## Build & Checks

Source verification and host tests require no connected device:

```powershell
python tools/project.py check
python tools/project.py test
```

Building an APK additionally requires Android SDK/NDK/JDK, pinned local binary dependencies, and your own signing configuration (neither committed nor implicitly downloaded). See [CONTRIBUTING.md](CONTRIBUTING.md) for full steps:

```powershell
python tools/project.py check --with-local-inputs
& .\apps\android\build.ps1
```

## Repository Layout

| Path | Contents |
| --- | --- |
| `apps/android` | Primary Android application, native UI, resources, and tests |
| `native/bluetooth` | Native bridge templates, adapter definitions, and host tests |
| `compatibility` | Exact device/build matching rules and offline capability classification |
| `third_party/sources` | Third-party sources, patches, and pinned origins required for builds |
| `third_party/payloads` | SHA-256 manifests for local binary inputs (binaries are not tracked) |
| `tools` | Project verification, release snapshot, and build-input tooling |
| `docs` | Release notes, design plans, model boundaries, and known issues |

## Documentation

- [v1.1 Release Notes](docs/release/v1.1.md) — Changelog, installation notes, and current limitations
- [Known Issues & Acceptance Matrix](docs/issues/README.md) — Open items and the `SM-X810` test matrix
- [AirPods Models](docs/airpods-models.md) — AACP model registry and capability gating rules
- [Architecture & Adapters](docs/adapters.md) — Direct-first connection decisions and adapter matching
- [Contributing & Build Guide](CONTRIBUTING.md) — Local dependencies, signing, and workflow

## License & Third-Party Notices

See [apps/android/LICENSE](apps/android/LICENSE) for application licensing and [THIRD_PARTY.md](THIRD_PARTY.md) for third-party component origins and distribution review scope.
