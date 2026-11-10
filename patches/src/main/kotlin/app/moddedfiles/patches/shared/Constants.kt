package app.moddedfiles.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    const val ORIGINAL_PACKAGE = "com.google.android.apps.nbu.files"

    /** Extension (ModKit) bundled into the patched app. */
    const val EXTENSION = "extensions/extension.mpe"

    /** Static hook entry points called from patched smali. */
    const val HOOKS = "Lapp/moddedfiles/extension/Hooks;"

    val COMPATIBILITY_FILES = Compatibility(
        name = "Files by Google",
        packageName = ORIGINAL_PACKAGE,
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x4484F4,
        targets = listOf(
            AppTarget(version = "1.13157.1011540316.0-release"),
        ),
    )
}
