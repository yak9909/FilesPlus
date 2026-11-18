package app.filesplus.patches.misc

import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val SMB_PROVIDER = "app.filesplus.extension.smb.SmbDocumentsProvider"
private const val SMB_AUTHORITY_SUFFIX = ".smb.documents"

private val modKitManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            // SMB DocumentsProvider. The authority follows the package attribute, so it stays right whether the
            // package rename ran before this patch (new package) or after it (rewritten like any other authority).
            val providers = application.getElementsByTagName("provider")
            val hasSmbProvider = (0 until providers.length).any {
                (providers.item(it) as Element).getAttribute("android:name") == SMB_PROVIDER
            }
            if (!hasSmbProvider) {
                val provider = manifest.createElement("provider")
                provider.setAttribute("android:name", SMB_PROVIDER)
                provider.setAttribute("android:authorities", manifest.documentElement.getAttribute("package") + SMB_AUTHORITY_SUFFIX)
                provider.setAttribute("android:exported", "true")
                provider.setAttribute("android:grantUriPermissions", "true")
                provider.setAttribute("android:permission", "android.permission.MANAGE_DOCUMENTS")
                val filter = manifest.createElement("intent-filter")
                val action = manifest.createElement("action")
                action.setAttribute("android:name", "android.content.action.DOCUMENTS_PROVIDER")
                filter.appendChild(action)
                provider.appendChild(filter)
                application.appendChild(provider)
            }
        }
    }
}

/** Hidden dependency shared by every feature patch. */
val modKitIntegrationPatch = bytecodePatch {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitManifestPatch)
    extendWith(Constants.EXTENSION)

    execute {
        FilesApplicationOnCreateFingerprint.method.addInstruction(
            0, "invoke-static/range {p0 .. p0}, ${Constants.EXT}ModKit;->init(Landroid/content/Context;)V",
        )
    }
}
