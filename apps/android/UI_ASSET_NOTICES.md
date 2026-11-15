# LinkPods UI resource sources

Source: https://github.com/librepods-org/librepods

Pinned source commit: `89356c08cea3db0b6b54792d9329d09a87cc85fa`.
The existing LinkPods application GPL-3.0 license is retained. Source artwork
is retained without conversion or modification. The source project contains
Apple-oriented artwork; copying the artwork here does not imply endorsement
by Apple or independently grant Apple trademark rights. Public distribution
review remains separate from this local implementation.

| Packaged resource | Upstream file |
| --- | --- |
| res/drawable/lp_earbuds.png | android/app/src/main/res-apple/drawable/airpods_pro_3_buds.png |
| res/drawable/lp_case.png | android/app/src/main/res-apple/drawable/airpods_pro_3_case.png |
| res/drawable/lp_noise_cancellation.png | android/app/src/main/res/drawable/noise_cancellation.png |
| res/drawable/lp_transparency.png | android/app/src/main/res/drawable/transparency.png |
| res/drawable/lp_adaptive.png | android/app/src/main/res/drawable/adaptive.png |

The product uses the platform sans-serif font. No proprietary font is bundled.
The AirPods Pro 3 illustration identifies the visual reference, and is not
model detection or a claim that the connected device has a given capability.

The native View/Canvas controls are a fresh Java implementation inspired by
LibrePods' Apple-style NoiseControlSettings.kt, StyledSwitch.kt and
StyledSlider.kt at the same commit. They use a translucent highlight and
spring-like interpolation; they do not implement true backdrop refraction.
