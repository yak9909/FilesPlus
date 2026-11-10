# 🧩 yak9909 Files Patches

Morphe patches for Files by Google (`com.google.android.apps.nbu.files`).

## ❓ About

Patches that add features to Files by Google while keeping its UI: configurable skip duration,
folder-tab actions (delete folder, show empty folders, move selection to the current tab),
multi-select and move queue while previewing, SMB storage, and better high-resolution playback.

Design documents (Japanese): [docs/DESIGN.md](docs/DESIGN.md), [docs/BUILD_PROCEDURE.md](docs/BUILD_PROCEDURE.md)

### How to use these patches

Click the button below, or manually add this repo url to Morphe Manager: https://github.com/yak9909/FilesPlus

<a href="https://morphe.software/add-source?github=yak9909/FilesPlus">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0-dev.2](https://github.com/yak9909/FilesPlus/releases/tag/v1.0.0-dev.2)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;3 patches total
<details open>
<summary>📦 Files by Google&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**Package name:** `com.google.android.apps.nbu.files`

**🎯 Supported versions:**

| 1.13157.1011540316.0-release |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Change package name](#change-package-name) | Install Files alongside the system app with a different package name and label. | • Package name<br>• App label |
| [Custom skip duration](#custom-skip-duration) | Customize double-tap and button skip durations; long-press a skip button to change its duration. |  |
| [High-resolution playback](#high-resolution-playback) | Try videos beyond reported decoder limits, enable decoder fallback, and log playback diagnostics. |  |

</details>

<!-- PATCHES_END -->

### 🛠️ Building locally

- Run `./gradlew buildAndroid`
- The built patches .mpp file is found in `patches/build/libs/patches-*.mpp`
- Patch the mpp file using [Morphe-Desktop](https://github.com/MorpheApp/morphe-desktop)
  like any other patch bundle.

See the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation) for more information.

## 📜 License

yak9909 Files Patches are licensed under the [GNU General Public License v3.0](LICENSE)
