# DK64 Recompiled Android 1.0.3

Stable Android baseline promoted from the tested r16 build.

## Included

- Native arm64-v8a Android build (API 26+).
- Application ID: `com.eightcee.dk64recomp`.
- RT64/Vulkan renderer with Android Plume integration.
- Modern proprietary Qualcomm/Adreno full-fidelity renderer path, while
  retaining compatibility fallbacks for older/problematic drivers.
- Stable Auto resolution using the tested Android 2x safety cap.
- Authoritative Vulkan swapchain extent handling and Android surface lifecycle
  recovery.
- Online Mod Store through the Android HTTP bridge.
- Local `.nrm` / `.rtz` mods.
- Code-mod runtime fixes required by mods such as German Translation,
  Tag Anywhere and Fixed Beaver Bother.
- Save import/export for the DK64 2 KiB EEPROM save.
- Android diagnostics/crash logging.
- Optional custom Vulkan drivers through libadrenotools.

## Compatibility baseline

The Android game-side patches are synchronized with Donkey Kong 64 Recompiled
1.0.2 where applicable. Android-specific runtime, renderer, storage, HTTP,
input and lifecycle changes remain isolated in the Android port.

## Release identity

- versionName: `1.0.3-android`
- versionCode: `16`
- ABI: `arm64-v8a`
- minSdk: `26`
- package: `com.eightcee.dk64recomp`

The Java/JNI namespace intentionally remains
`com.deivid22srk.dk64recomp` for native ABI compatibility.
