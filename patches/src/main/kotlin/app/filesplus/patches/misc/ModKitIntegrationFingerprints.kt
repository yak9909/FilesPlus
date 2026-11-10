package app.filesplus.patches.misc

import app.morphe.patcher.Fingerprint

internal object FilesApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/nbu/files/Files_Application;",
    name = "onCreate",
    parameters = emptyList(),
    returnType = "V",
)

internal object SettingsBuilderFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("ABOUT_FILES_PREF_KEY", "THEME_CHANGE_PREF_KEY"),
)
