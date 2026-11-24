package app.filesplus.patches.misc

import app.morphe.patcher.Fingerprint

internal object FilesApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/nbu/files/Files_Application;",
    name = "onCreate",
    parameters = emptyList(),
    returnType = "V",
)
