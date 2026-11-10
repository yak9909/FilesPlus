package app.moddedfiles.patches.misc

import app.moddedfiles.patches.shared.Constants.COMPATIBILITY_FILES
import app.moddedfiles.patches.shared.Constants.ORIGINAL_PACKAGE
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.listDecodableFiles
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import app.morphe.patcher.xml.XmlElement
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31c
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

val packageNamePatch = patch(
    name = "Change package name",
    description = "Install Files alongside the system app with a different package name and label.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_FILES)

    val packageName by stringOption(
        key = "packageName",
        default = "com.google.android.apps.nbu.files.mod",
        title = "Package name",
        description = "A unique Android package name for the patched app.",
        required = true,
        validator = {
            it != null && it != ORIGINAL_PACKAGE &&
                it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))
        },
    )
    val appLabel by stringOption(
        key = "appLabel",
        default = "Files Mod",
        title = "App label",
        description = "The application and launcher name in every language.",
        required = true,
        validator = { !it.isNullOrBlank() },
    )

    execute {
        val newPackage = packageName!!
        val newLabel = appLabel!!
        val replacements = linkedMapOf<String, String>()
        fun renameIdentifier(value: String): String = when {
            value == ORIGINAL_PACKAGE -> newPackage
            value.startsWith("$ORIGINAL_PACKAGE.") -> newPackage + value.removePrefix(ORIGINAL_PACKAGE)
            // Firebase uses its app ID as an authority, rather than the Android package.
            else -> "$newPackage.$value"
        }

        resourceXml("AndroidManifest.xml").use { manifest ->
            require(manifest.root.attribute("package") == ORIGINAL_PACKAGE) {
                "Expected the original Files package before renaming."
            }
            val elements = manifest.elements().toList()
            elements.filter { it.tag == "provider" }.forEach { provider ->
                provider.attribute("android:authorities")?.split(';')?.forEach { authority ->
                    require(authority.isNotBlank()) { "Empty provider authority." }
                    replacements[authority] = renameIdentifier(authority)
                }
            }
            elements.filter { it.tag in setOf("permission", "permission-tree", "permission-group") }
                .forEach { permission ->
                    permission.attribute("android:name")?.let { replacements[it] = renameIdentifier(it) }
                }
            elements.filter { it.tag in setOf("action", "category") }.forEach { element ->
                element.attribute("android:name")?.takeIf { it.startsWith("$ORIGINAL_PACKAGE.") }
                    ?.let { replacements[it] = renameIdentifier(it) }
            }

            // Resolve class names against the original package before changing the manifest package.
            // This version uses fully qualified names; also preserve relative names in future APKs.
            elements.forEach { element ->
                val classAttributes = when (element.tag) {
                    "application" -> listOf("name", "backupAgent", "appComponentFactory", "manageSpaceActivity")
                    "activity" -> listOf("name", "parentActivityName")
                    "activity-alias" -> listOf("name", "targetActivity", "parentActivityName")
                    "service", "receiver", "provider", "instrumentation" -> listOf("name")
                    else -> emptyList()
                }
                classAttributes.forEach { name ->
                    val attribute = "android:$name"
                    element.attribute(attribute)?.takeIf { it.isNotEmpty() }?.let { value ->
                        when {
                            value.startsWith('.') -> element.setAttribute(attribute, ORIGINAL_PACKAGE + value)
                            '.' !in value -> element.setAttribute(attribute, "$ORIGINAL_PACKAGE.$value")
                        }
                    }
                }
            }

            val application = elements.single { it.tag == "application" }
            val launchers = elements.filter { element ->
                element.tag in setOf("activity", "activity-alias") &&
                    element.elements("category").any {
                        it.attribute("android:name") == "android.intent.category.LAUNCHER"
                    }
            }
            val appLabels = (launchers + application).mapNotNull { it.attribute("android:label") }.toSet()
            elements.filter {
                it == application || it in launchers ||
                    (it.tag in setOf("activity", "activity-alias") && it.attribute("android:label") in appLabels)
            }.forEach { it.setAttribute("android:label", newLabel) }

            elements.forEach { rewritePackageAttributes(it, newPackage, replacements) }
            manifest.root.setAttribute("package", newPackage)
        }

        // Includes localized/qualified shortcuts XML; class names and FileProvider paths are preserved.
        listDecodableFiles("res/xml*/*.xml").forEach { path ->
            resourceXml(path).use { xml ->
                xml.elements().forEach { rewritePackageAttributes(it, newPackage, replacements) }
            }
        }

        // Longest first: .provider.cache must take precedence over .provider.
        val prefixes = replacements.entries.sortedByDescending { it.key.length }
        fun rewriteString(value: String): String {
            // All six exact literals in the target APK identify a package or deep-link authority:
            // tile ComponentName, caller validation, deep-link builder/parser, external_app_entrypoint,
            // and the AppSearch donation cap map. Worker names and the .device namespace are not packages.
            if (value == ORIGINAL_PACKAGE) return newPackage
            prefixes.firstOrNull { value.startsWith(it.key) }?.let {
                return it.value + value.removePrefix(it.key)
            }
            // Also support constants containing a complete URI, rather than just an authority.
            return rewritePackageUri(value, newPackage, replacements)
        }
        fun Instruction.rewrittenString(): String? {
            if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) return null
            val value = ((this as ReferenceInstruction).reference as StringReference).string
            return rewriteString(value).takeIf { it != value }
        }

        classDefForEach { classDef ->
            val hasStrings = classDef.methods.any { method ->
                method.implementation?.instructions?.any { it.rewrittenString() != null } == true
            }
            val hasFields = classDef.fields.any { field ->
                (field.initialValue as? StringEncodedValue)?.value?.let { rewriteString(it) != it } == true
            }
            if (hasStrings || hasFields) {
                val mutableClass = mutableClassDefBy(classDef)
                mutableClass.fields.forEach { field ->
                    (field.initialValue as? MutableStringEncodedValue)?.let { value ->
                        value.value = rewriteString(value.value)
                    }
                }
                mutableClass.methods.forEach { method ->
                    val implementation = method.implementation ?: return@forEach
                    val edits = implementation.instructions.mapIndexedNotNull { index, instruction ->
                        instruction.rewrittenString()?.let { replacement ->
                            index to BuilderInstruction31c(
                                Opcode.CONST_STRING_JUMBO,
                                (instruction as OneRegisterInstruction).registerA,
                                ImmutableStringReference(replacement),
                            )
                        }
                    }
                    edits.asReversed().forEach { (index, instruction) ->
                        implementation.replaceInstruction(index, instruction)
                    }
                }
            }
        }
    }
}

private fun rewritePackageAttributes(
    element: XmlElement,
    newPackage: String,
    replacements: Map<String, String>,
) {
    val attributes = mutableSetOf(
        "android:targetPackage", "android:permission", "android:readPermission", "android:writePermission",
        "android:permissionGroup", "android:host", "android:scheme", "android:data", "android:action",
    )
    if (element.tag in setOf("permission", "permission-tree", "permission-group", "uses-permission",
            "uses-permission-sdk-23", "action", "category", "package")) {
        attributes += "android:name"
    }
    attributes.forEach { name ->
        element.attribute(name)?.let { value ->
            val replacement = when {
                value == ORIGINAL_PACKAGE -> newPackage
                value in replacements -> replacements.getValue(value)
                else -> rewritePackageUri(value, newPackage, replacements)
            }
            if (replacement != value) element.setAttribute(name, replacement)
        }
    }
    element.attribute("android:authorities")?.let { authorities ->
        element.setAttribute("android:authorities", authorities.split(';').joinToString(";") {
            replacements[it] ?: it
        })
    }
}

private fun rewritePackageUri(value: String, newPackage: String, replacements: Map<String, String>): String {
    val separator = value.indexOf("://")
    if (separator < 0) return value
    val start = separator + 3
    val end = value.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) value.length else it }
    val authority = value.substring(start, end)
    val replacement = if (authority == ORIGINAL_PACKAGE) newPackage else replacements[authority] ?: return value
    return value.substring(0, start) + replacement + value.substring(end)
}
