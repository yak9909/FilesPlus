# Worker brief (read first)

Project: Morphe patch bundle for **Files by Google** (`com.google.android.apps.nbu.files`, target version
`1.13157.1011540316.0-release`, versionCode 2081362). Goal: add features without changing the app's UI design.
Design (Japanese) is in `docs/DESIGN.md`; your task prompt quotes the parts you need.

## Layout
- Patches (Kotlin, Morphe patcher API): `patches/src/main/kotlin/app/moddedfiles/patches/<feature>/`
  - Use `Constants.COMPATIBILITY_FILES`, `Constants.EXTENSION` (`extendWith(...)`), `Constants.EXT` from `shared/Constants.kt`.
  - Morphe docs: https://github.com/MorpheApp/morphe-patcher/tree/main/docs (patch anatomy, fingerprinting, APIs).
    The patcher jar/sources are also in the Gradle cache (`~/.gradle/caches`, `app.morphe`), read them when unsure.
- Extension (Java, compiled to dex and merged into the app): `extensions/extension/src/main/java/app/moddedfiles/extension/<feature>/`
  - Shared core (do not rename; add keys/methods only if your task says so): `ModKit` (app context, `init`),
    `ModSettings` (SharedPreferences "modkit_prefs"), `DiagLog` (in-app diagnostic log; the user cannot use adb).
  - Put your hook entry points in a per-feature class, e.g. `app.moddedfiles.extension.seek.SkipHooks`.
    Every hook method must catch `Throwable`, log via `DiagLog.e`, and fall back to the original behavior.
  - The extension has **no Android resources** (no layouts/strings). Build UI programmatically.
    App resources can be looked up by name at runtime (`getIdentifier`).
  - Only Android SDK APIs are available to extension code. App classes are obfuscated; if you must touch them,
    do it from smali in the patch, or pass plain values (int/long/String/View/Context) across the boundary.

## Finding code in the app
- Decompiled app: `analysis/jadx/sources/` (Java, read-only); resources/manifest: `analysis/apktool/`.
- Obfuscated names (e.g. `defpackage.kwd`) are only valid for this version. **Fingerprints must not use obfuscated
  class/method names.** Use strings, resource IDs/literals, unobfuscated classes (`com.google.android.apps.nbu.files.*`
  view/activity classes), Android API calls, opcodes and parameter shapes.

## Build & rules
- Build: `bash scripts/build.sh` (prints errors only). It must pass before you finish.
- Do not commit. Do not edit generated files (`patches-list.json`, `patches-bundle.json`, `CHANGELOG.md`, README patch list).
- Do not edit files owned by other features unless the task says so.
- Code comments in English. Keep it simple: no speculative abstractions.
- Final reply (English, short): files changed, how each hook is located (fingerprint idea), what you could not verify, risks.
  No diffs, no file dumps.
