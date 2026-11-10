package app.moddedfiles.patches.misc

import app.moddedfiles.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.w3c.dom.Element

private const val PREFERENCE = "Landroidx/preference/Preference;"
private const val CONTEXT = "Landroid/content/Context;"
private const val INTENT = "Landroid/content/Intent;"
private const val SETTINGS_ACTIVITY = "app.moddedfiles.extension.settings.ModSettingsActivity"
private const val HOOKS = "${Constants.EXT}settings/SettingsHooks;"

private val modKitManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activities = application.getElementsByTagName("activity")
            val settings = (0 until activities.length).map { activities.item(it) as Element }.single {
                it.getAttribute("android:name") in setOf(
                    "${Constants.ORIGINAL_PACKAGE}.settings.SettingsActivity", ".settings.SettingsActivity",
                )
            }
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", SETTINGS_ACTIVITY)
            activity.setAttribute("android:exported", "false")
            // An absent activity theme inherits the application theme, just like SettingsActivity.
            val theme = settings.getAttribute("android:theme")
            if (theme.isNotEmpty()) activity.setAttribute("android:theme", theme)
            application.appendChild(activity)
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
            0, "invoke-static/range {p0 .. p0}, ${Constants.EXT}ModKit;->init($CONTEXT)V",
        )

        val preference = mutableClassDefBy(PREFERENCE)
        val contextField = preference.fields.single { it.type == CONTEXT }
        val setIntent = preference.methods.singleOrNull {
            it.parameterTypes.map(CharSequence::toString) == listOf(INTENT) && it.returnType == "V"
        } ?: preference.restoreIntentSupport(contextField.toString())

        val builder = SettingsBuilderFingerprint.method
        val instructions = builder.implementation!!.instructions.toList()
        val keyIndex = instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "ABOUT_FILES_PREF_KEY"
        }
        check(keyIndex >= 0) { "About preference key not found" }
        val keyCallIndex = (keyIndex + 1 until instructions.size).first {
            instructions[it].methodReference()?.let { ref ->
                ref.definingClass == PREFERENCE && ref.parameterTypes == listOf("Ljava/lang/String;") && ref.returnType == "V"
            } == true
        }
        val keyCall = instructions[keyCallIndex] as Instruction35c
        val aboutRegister = keyCall.registerC
        val addIndex = (keyCallIndex + 1 until instructions.size).first {
            val call = instructions[it] as? Instruction35c
            val ref = instructions[it].methodReference()
            call != null && call.registerCount == 2 && call.registerD == aboutRegister &&
                ref?.parameterTypes == listOf(PREFERENCE) && ref.returnType in setOf("V", "Z") &&
                ref.definingClass.startsWith("Landroidx/preference/")
        }
        val addCall = instructions[addIndex] as Instruction35c
        val addReference = instructions[addIndex].methodReference()!!
        val titleReference = instructions.subList(keyCallIndex + 1, addIndex).mapNotNull { it.methodReference() }.first {
            it.definingClass == PREFERENCE && it.parameterTypes == listOf("Ljava/lang/CharSequence;") && it.returnType == "V"
        }
        val constructorIndex = (0 until keyIndex).last {
            instructions[it].methodReference()?.let { ref ->
                ref.definingClass == PREFERENCE && ref.name == "<init>"
            } == true
        }
        val prepare = instructions.subList(constructorIndex + 1, keyIndex).mapNotNull { it.methodReference() }
            .filter { it.definingClass == PREFERENCE && it.parameterTypes.isEmpty() && it.returnType == "V" }
            .joinToString("\n") { "invoke-virtual {v1}, $it" }

        // Use a helper so the large settings builder needs no extra registers or register shuffling.
        val owner = mutableClassDefBy(builder.definingClass)
        val helper = owner.addMethod(
            "modKitAddSettings", listOf(addReference.definingClass, PREFERENCE),
            AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, 5,
            """
                iget-object v0, p1, $contextField
                new-instance v1, $PREFERENCE
                const/4 v2, 0x0
                invoke-direct {v1, v0, v2}, $PREFERENCE-><init>(${CONTEXT}Landroid/util/AttributeSet;)V
                $prepare
                const-string v2, "MODKIT_PREF_KEY"
                invoke-virtual {v1, v2}, ${keyCall.reference}
                invoke-static {v0}, $HOOKS->entryTitle($CONTEXT)Ljava/lang/String;
                move-result-object v2
                invoke-virtual {v1, v2}, $titleReference
                invoke-static {v0}, $HOOKS->settingsIntent($CONTEXT)$INTENT
                move-result-object v2
                invoke-virtual {v1, v2}, $setIntent
                invoke-virtual {p0, v1}, $addReference
                return-void
            """.trimIndent(),
        )
        builder.addInstruction(addIndex, "invoke-static {v${addCall.registerC}, v$aboutRegister}, $helper")
    }
}

private fun Instruction.methodReference() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MutableClass.addMethod(
    name: String, parameters: List<String>, flags: Int, registers: Int, body: String,
): MutableMethod {
    check(methods.none { it.name == name }) { "ModKit method already exists: $type->$name" }
    val method = MutableMethod(ImmutableMethod(
        type, name, parameters.map { ImmutableMethodParameter(it, emptySet(), null) }, "V", flags,
        emptySet(), emptySet(), MutableMethodImplementation(registers),
    ))
    method.addInstructions(0, body)
    methods.add(method)
    return method
}

/** R8 removed the unused Intent API from the supported APK. Restore only the missing path. */
private fun MutableClass.restoreIntentSupport(contextField: String): MutableMethod {
    val fieldName = "modKitIntent"
    check(fields.none { it.name == fieldName })
    fields.add(MutableField(ImmutableField(
        type, fieldName, INTENT, AccessFlags.PRIVATE.value, null, emptySet(), emptySet(),
    )))
    val setter = addMethod("setIntent", listOf(INTENT), AccessFlags.PUBLIC.value, 2,
        "iput-object p1, p0, $type->$fieldName:$INTENT\nreturn-void")

    // Follow Preference.onClick(View) -> performClick() -> protected onClick(), all by shape.
    val viewClick = methods.single {
        it.parameterTypes == listOf("Landroid/view/View;") && it.returnType == "V"
    }
    val dispatchRef = viewClick.implementation!!.instructions.mapNotNull { it.methodReference() }.single {
        it.definingClass == type && it.parameterTypes.isEmpty() && it.returnType == "V"
    }
    val dispatch = methods.single { it.toString() == dispatchRef.toString() }
    val onClickRef = dispatch.implementation!!.instructions.mapNotNull { it.methodReference() }.first {
        it.definingClass == type && it.parameterTypes.isEmpty() && it.returnType == "V"
    }
    val onClick = methods.single { it.toString() == onClickRef.toString() }
    check(onClick.implementation!!.instructions.single().opcode == Opcode.RETURN_VOID) {
        "Cannot restore Preference Intent dispatch: onClick is not empty"
    }
    val launch = addMethod("modKitLaunchIntent", emptyList(), AccessFlags.PRIVATE.value, 3,
        """
            iget-object v0, p0, $contextField
            iget-object v1, p0, $type->$fieldName:$INTENT
            invoke-static {v0, v1}, $HOOKS->launchIntent($CONTEXT$INTENT)V
            return-void
        """.trimIndent())
    onClick.addInstruction(0, "invoke-direct {p0}, $launch")
    return setter
}
