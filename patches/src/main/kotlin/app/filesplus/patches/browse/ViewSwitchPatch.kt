package app.filesplus.patches.browse

import app.filesplus.patches.settings.settingsInjectionPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val SCREENS = "${Constants.EXT}ui/OriginalScreens;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val STRING_EQUALS = "$STRING->equals(${OBJECT})Z"

private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Method.hasString(value: String) = code().any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
}

private fun Iterable<Method>.unique(label: String, predicate: (Method) -> Boolean): Method {
    val hits = filter(predicate)
    check(hits.size == 1) { "Switch view $label: expected 1 match, got ${hits.size}" }
    return hits.single().also { println("Switch view fingerprint $label: 1 match ($it)") }
}

val viewSwitchPatch = bytecodePatch(
    name = "Switch view",
    description = "Switch between the app's storage and media browsers from the top overflow.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(browserHooksPatch, settingsInjectionPatch)

    execute {
        bindVolumeWatcher(takeBrowserBindings())
        mutableClassDefBy("${Constants.EXT}browse/ViewSwitch;").methods.single {
            it.name == "installed" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
    }
}

private fun BytecodePatchContext.bindVolumeWatcher(browser: BrowserBindings) {
    val peer = browser.storage
    val errors = mutableListOf<Method>()
    classDefForEach { definition ->
        definition.methods.filterTo(errors) { method ->
            method.parameterTypes == listOf("Ljava/lang/Throwable;") &&
                method.hasString("Unable to load root containers") &&
                method.code().any { op ->
                    ((op as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass == peer.type
                }
        }
    }
    val loadError = errors.unique("storage catalogue observer error") { true }
    val watcher = mutableClassDefBy(loadError.definingClass)
    val observer = watcher.methods.unique("storage catalogue observer comparison") { method ->
        method.parameterTypes == listOf(OBJECT) && method.returnType == "V" &&
            method.code().any { op ->
                op.opcode == Opcode.IPUT_OBJECT && ((op as? ReferenceInstruction)?.reference as? FieldReference)
                    ?.let { it.definingClass == peer.type && it.type == "Ljava/util/List;" } == true
            } && method.code().any { (it as? ReferenceInstruction)?.reference.toString() == STRING_EQUALS }
    } as MutableMethod
    val code = observer.code()
    val comparisonIndex = code.indices.single { index ->
        if (index < 3) return@single false
        val folderLoad = code[index - 3] as? Instruction22c ?: return@single false
        val candidateUri = code[index - 2] as? Instruction22c ?: return@single false
        val watchedUri = code[index - 1] as? Instruction22c ?: return@single false
        val comparison = code[index] as? Instruction35c ?: return@single false
        val folderField = folderLoad.reference as? FieldReference ?: return@single false
        val uriField = watchedUri.reference as? FieldReference ?: return@single false
        folderLoad.opcode == Opcode.IGET_OBJECT && folderField.definingClass == peer.type &&
            folderField.type == browser.folderType && watchedUri.opcode == Opcode.IGET_OBJECT &&
            uriField.definingClass == browser.folderType && uriField.type == STRING &&
            candidateUri.opcode == Opcode.IGET_OBJECT && candidateUri.reference == uriField &&
            watchedUri.registerB == folderLoad.registerA && candidateUri.registerA != folderLoad.registerA &&
            comparison.opcode == Opcode.INVOKE_VIRTUAL && comparison.reference.toString() == STRING_EQUALS &&
            comparison.registerCount == 2 && comparison.registerC == candidateUri.registerA &&
            comparison.registerD == watchedUri.registerA
    }
    val folderLoad = code[comparisonIndex - 3] as Instruction22c
    val comparison = code[comparisonIndex] as Instruction35c
    val peerRegister = folderLoad.registerB
    check(peerRegister != folderLoad.registerA && peerRegister != comparison.registerC &&
        peerRegister != comparison.registerD)
    check(listOf(peerRegister, comparison.registerC, comparison.registerD).all {
        it < 16 && it < observer.implementation!!.registerCount
    })

    val hierarchy = generateSequence(browser.storageFragment.type) { classDefByOrNull(it)?.superclass }.toSet()
    val host = hierarchy.mapNotNull { classDefByOrNull(it) }.flatMap { it.methods }
        .unique("storage fragment activity accessor") {
            it.parameterTypes.isEmpty() && it.hasString(" not attached to an activity.") &&
                ACTIVITY in generateSequence(it.returnType) { type -> classDefByOrNull(type)?.superclass }.toSet()
        }
    val peerFragment = peer.fields.single { it.type == host.definingClass }
    val compare = MutableMethod(ImmutableMethod(watcher.type, "modKitSwitchVolumeMatches",
        listOf(STRING, STRING, peer.type).map { ImmutableMethodParameter(it, emptySet(), null) }, "Z",
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, emptySet(), emptySet(), MutableMethodImplementation(5)))
    compare.addInstructionsWithLabels(0, """
        move-object v0, p1
        iget-object v1, p2, $peerFragment
        invoke-virtual {v1}, $host
        move-result-object v1
        invoke-static {v1, p1}, $SCREENS->watchedVolumeUri($ACTIVITY$STRING)$STRING
        move-result-object v0
        :compare
        invoke-virtual {p0, v0}, $STRING_EQUALS
        move-result v0
        return v0
        move-exception v1
        invoke-static {v1}, $SCREENS->volumeWatchError(Ljava/lang/Throwable;)V
        goto :compare
    """.trimIndent())
    compare.implementation!!.apply {
        val handler = compare.code().indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
        val nativeCompare = compare.code().indexOfFirst {
            (it as? ReferenceInstruction)?.reference.toString() == STRING_EQUALS
        }
        addCatch("Ljava/lang/Throwable;", newLabelForIndex(1), newLabelForIndex(nativeCompare), newLabelForIndex(handler))
        check(registerCount == 5)
    }
    watcher.methods.add(compare)
    observer.replaceInstruction(comparisonIndex,
        "invoke-static {v${comparison.registerC}, v${comparison.registerD}, v$peerRegister}, $compare")
}
