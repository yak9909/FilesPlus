package app.filesplus.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    const val ORIGINAL_PACKAGE = "com.google.android.apps.nbu.files"

    /** Extension (ModKit) bundled into the patched app. */
    const val EXTENSION = "extensions/extension.mpe"

    /** Base JVM type prefix of extension classes, e.g. "${EXT}seek/SkipHooks;". */
    const val EXT = "Lapp/filesplus/extension/"

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
