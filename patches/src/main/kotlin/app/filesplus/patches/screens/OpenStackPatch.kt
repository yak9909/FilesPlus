package app.filesplus.patches.screens

import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val HOOK = "${Constants.EXT}ui/OriginalScreens;"
private const val OBJECT = "Ljava/lang/Object;"

/** R.layout.advanced_browsing_activity of the supported version (resource ids are fixed per build). */
private const val BROWSER_LAYOUT = 0x7f0d0025L

/** The storage browser activity is not obfuscated; the layout literal is checked in the patch body. */
internal object AdvancedBrowsingOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/nbu/files/advancedbrowsing/browser/AdvancedBrowsingActivity;",
    name = "onCreate",
    parameters = listOf("Landroid/os/Bundle;"),
    returnType = "V",
)

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.methodReference() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

val openStackPatch = bytecodePatch(
    name = "Open folder stack",
    description = "Lets the storage browser open with a folder stack (volume root down to a folder) so that back and " +
        "the breadcrumb work. Used by the other features that open the app's own screens.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch)

    execute {
        val onCreate = AdvancedBrowsingOnCreateFingerprint.method
        val code = onCreate.implementation!!.instructions.toList()

        val layoutIndex = code.indexOfFirst { (it as? WideLiteralInstruction)?.wideLiteral == BROWSER_LAYOUT }
        check(layoutIndex >= 0) { "advanced_browsing_activity layout id not found in onCreate" }
        val setContentIndex = (layoutIndex + 1 until code.size).first {
            code[it].methodReference()?.let { ref -> ref.name == "setContentView" && ref.parameterTypes == listOf("I") } == true
        }

        // After setContentView the app places the first fragment: peer.<place>(hut) where peer is the type of one of
        // the activity's own fields, the parameter is the type of one of the peer's fields (the activity's hut) and
        // nothing else in that stretch has this shape.
        val activity = mutableClassDefBy(onCreate.definingClass)
        val fieldTypes = activity.fields.map { it.type }.toSet()
        val candidates = (setContentIndex + 1 until code.size).filter { index ->
            val call = code[index] as? Instruction35c ?: return@filter false
            val ref = call.methodReference() ?: return@filter false
            call.opcode == Opcode.INVOKE_VIRTUAL && call.registerCount == 2 &&
                ref.returnType == "V" && ref.parameterTypes.size == 1 && ref.definingClass in fieldTypes &&
                classDefBy(ref.definingClass).fields.any { it.type == ref.parameterTypes[0].toString() }
        }
        check(candidates.size == 1) { "Expected one initial-fragment call, found ${candidates.size}" }
        val callIndex = candidates.single()
        val call = code[callIndex] as Instruction35c
        val original = call.reference as MethodReference
        val peerType = original.definingClass
        val hutType = original.parameterTypes[0].toString()

        // Static helper in the activity (own registers, so onCreate's registers stay untouched): the extension decides
        // whether the stack has several levels; the original method is always called by the app's own instruction.
        val helper = MutableMethod(ImmutableMethod(
            onCreate.definingClass, "modKitPlaceInitial",
            listOf(peerType, hutType).map { ImmutableMethodParameter(it, emptySet(), null) }, "V",
            AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
            MutableMethodImplementation(6), // v0..v3 locals, p0 = peer (v4), p1 = hut (v5)
        ))
        check(activity.methods.none { it.name == helper.name })
        helper.addInstructions(0, """
            invoke-static {p1}, $HOOK->truncatedCopies($OBJECT)[$OBJECT
            move-result-object v0
            if-eqz v0, :single
            array-length v1, v0
            const/4 v2, 0x0
            :loop
            if-ge v2, v1, :done
            aget-object v3, v0, v2
            check-cast v3, $hutType
            invoke-virtual {p0, v3}, $original
            add-int/lit8 v2, v2, 0x1
            goto :loop
            :single
            invoke-virtual {p0, p1}, $original
            :done
            return-void
        """.trimIndent())
        activity.methods.add(helper)

        onCreate.replaceInstruction(callIndex, "invoke-static {v${call.registerC}, v${call.registerD}}, $helper")
    }
}
