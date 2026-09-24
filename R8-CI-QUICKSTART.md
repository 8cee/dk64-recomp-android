# DK64 Android r8 full-fidelity CI quickstart

This branch is intended to be built by the fork's GitHub Actions runner, which
provides the Android SDK/NDK that is unavailable in the audit sandbox.

## One-time setup

1. In your fork clone, authenticate `gh` (`gh auth login`).
2. Put your legally obtained NTSC-U 1.0 ROM somewhere local.
3. Create the private build-input release and token secret:

   `GH_TOKEN=<PAT-with-repo-access> ROM_ZIP=/path/to/DonkeyKong64.z64 ./scripts/setup-build-inputs-repo.sh`

4. Create the persistent Android signing key and secrets:

   `./scripts/setup-ci-signing.sh`

Keep the generated `$HOME/.dk64-recomp-android-signing` directory backed up.
Every test APK from this fork will then use the same signing identity, allowing
normal Android updates instead of requiring uninstall/reinstall each run.

## Build

Push `main` or a branch named `r8-*`. The `build` workflow produces the artifact
`dk64recomp-android-r8-full-fidelity` containing `app-release.apk`.

The workflow aborts if:

- RT64 is not `e437f2a5010b813cf5827b84fa249997ea910903`;
- N64ModernRuntime is not `8fe786b52177d087821cf3affb85296f663b508d`;
- an Android submodule patch no longer applies cleanly;
- the APK does not contain the adrenotools hook libraries;
- `renderer_compat.txt` / `DK64_RENDERER_COMPAT` are not compiled into `libgame.so`;
- the APK version is not `1.0.3-android-r8`; or
- the APK is not signed with the persistent DK64 signing certificate.

## First graphics test

Use the stock/system Vulkan driver first and do not create `renderer_compat.txt`.
Test the title/water scene and the Kong/cap artifact scenes. Then place a file
named `renderer_compat.txt` in the app's files directory containing `legacy`,
restart the app, and repeat. `full` can be used to force the normal path.
