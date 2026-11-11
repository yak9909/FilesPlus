package app.filesplus.patches.foldertabs

import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

private const val HOOK = "${Constants.EXT}foldertabs/FolderTabContext;"
private const val VIEW = "Landroid/view/View;"
private const val MENU = "Landroid/view/Menu;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val RUNNABLE = "Ljava/lang/Runnable;"

private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
private fun Method.string(text: String) = code().any { (it.ref() as? StringReference)?.string == text }
private fun Method.literal(value: Long) = code().any { (it as? WideLiteralInstruction)?.wideLiteral == value }
private fun Method.calls() = code().mapNotNull { it.ref() as? MethodReference }

val moveToCurrentTabPatch = bytecodePatch(
    name = "Move to current tab",
    description = "Add a selection-mode menu item that moves the selected files into the folder of the current category tab.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch, folderTabContextPatch)

    execute {
        // The tabbed screen's menu owner: the class that inflates tabbed_fragment_menu_v2 (same anchor as the delete patch).
        val owner = mutableClassDefBy(classDefBy { cls -> cls.methods.any { method ->
            method.parameterTypes == listOf(MENU) && method.literal(0x7f0f001b)
        } })
        val fragmentType = owner.methods.single { it.name == "<init>" }.parameterTypes.first().toString()
        val fragment = owner.fields.single { it.type == fragmentType }
        val rootView = classDefBy(fragmentType).methods.single { it.parameterTypes.isEmpty() && it.returnType == VIEW }

        // Selection action mode holder: its log strings are stable. It keeps the ActionMode in a field.
        val actionModes = classDefBy { cls -> cls.methods.any {
            it.string("setActionModeTitle should be called when action exists")
        } }
        val getMenu = actionModes.methods.single { it.string("updateMenuItem should be called when action exists") }
            .calls().first { it.returnType == MENU && it.parameterTypes.isEmpty() }
        val actionMode = actionModes.fields.single { it.type == getMenu.definingClass }
        val actionModesField = owner.fields.single { it.type == actionModes.type }

        // Selection model owner, found through its saved-state key, and its "clear selection" method.
        val selectionField = owner.fields.single { field ->
            classDefByOrNull(field.type)?.methods?.any { it.string("SELECTED_DATA_STATE_TAG") } == true
        }
        val clearSelection = classDefBy(selectionField.type).methods.single {
            it.parameterTypes.isEmpty() && it.returnType == "V" && it.string("SELECTION_DATA_SOURCE")
        }

        // The selection-changed method updates the action mode title through the holder.
        val onSelection = owner.methods.single { method ->
            method.parameterTypes.size == 1 && method.returnType == "V" &&
                method.calls().any { it.definingClass == actionModes.type && it.parameterTypes == listOf(STRING, STRING) }
        }
        val selectionType = onSelection.parameterTypes.first().toString()
        val selectedFiles = classDefBy(selectionType).methods.single {
            it.parameterTypes.isEmpty() && classDefByOrNull(it.returnType)?.let { type ->
                AccessFlags.INTERFACE.isSet(type.accessFlags) } == true
        }
        // The two accessors of that interface return lists of MediaStore ids and of app-private entries.
        val accessors = classDefBy(selectedFiles.returnType.toString()).methods.filter { it.parameterTypes.isEmpty() }
        check(accessors.size == 2) { "Unexpected selected-files accessors: ${accessors.size}" }

        check(RUNNABLE !in owner.interfaces && owner.methods.none { it.name == "run" })
        owner.interfaces.add(RUNNABLE)
        owner.bridge("run", emptyList(), 2, """
            iget-object v0, p0, $selectionField
            invoke-virtual {v0}, $clearSelection
        """.trimIndent())

        val menuBridge: MutableMethod = owner.bridge("modKitMoveSelection", listOf(selectionType), 8, """
            iget-object v0, p0, $actionModesField
            iget-object v0, v0, $actionMode
            if-eqz v0, :done
            invoke-virtual {v0}, $getMenu
            move-result-object v1
            iget-object v2, p0, $fragment
            invoke-virtual {v2}, $rootView
            move-result-object v2
            invoke-interface {p1}, $selectedFiles
            move-result-object v3
            invoke-interface {v3}, ${accessors[0]}
            move-result-object v4
            invoke-interface {v3}, ${accessors[1]}
            move-result-object v5
            invoke-static {v2, v1, v4, v5, p0}, $HOOK->onSelectionMenu(${VIEW}${MENU}${LIST}${LIST}${RUNNABLE})V
            :done
        """.trimIndent())
        val returns = onSelection.code().withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
        check(returns.isNotEmpty())
        for ((index) in returns.reversed()) {
            (onSelection as MutableMethod).addInstructions(index, "invoke-virtual/range {p0 .. p1}, $menuBridge")
        }
    }
}
