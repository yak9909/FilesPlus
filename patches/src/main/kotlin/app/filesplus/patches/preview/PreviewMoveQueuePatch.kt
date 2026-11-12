package app.filesplus.patches.preview

import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PAGER_HOOK = "${Constants.EXT}preview/PreviewHooks;"
private const val PAGER_CONTROL = "${Constants.EXT}preview/PagerControl;"

/** Log messages of the app's "remove the current file and show the next one" method (stable strings). */
private const val ONLY_ONE_MESSAGE = "removeCurrentAndMoveToNextFile with only 1 file."
private const val INVALID_POSITION_MESSAGE = "removeCurrentAndMoveToNextFile from an invalid position item."

/** Message of the indexed file accessor; the no-argument "current file" accessor calls it with the current index. */
private const val INDEXED_MESSAGE = "File preview index is not valid."

private fun needMove(condition: Boolean, detail: () -> String) {
    if (!condition) throw PatchException("Move queue in preview: unexpected ${detail()}")
}

val previewMoveQueuePatch = bytecodePatch(
    name = "Move queue in preview",
    description = "Add Send to… to the preview screen's menu to move the current file (or the selected files) to a folder in the background. Single files that are not playing move on to the next file at once; playing videos and multi-file sends are moved without changing the page.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch, previewMultiSelectPatch)

    execute {
        // The preview controller: the only class that holds the app's "remove current and move to next" method.
        val controllers = mutableListOf<String>()
        classDefForEach { cls -> if (cls.methods.any { isRemoveCurrent(it) }) controllers.add(cls.type) }
        needMove(controllers.size == 1) { "controller classes: ${controllers.size}" }
        val controller = mutableClassDefBy(controllers.single())
        val remove = controller.methods.single { isRemoveCurrent(it) }
        val removeCode = remove.code()

        // The file list holder (first field it reads) and its total count (the long read next): the method
        // returns early when the count is at most 1.
        val holder = removeCode.mapNotNull { insn ->
            (insn.ref() as? FieldReference)?.takeIf {
                insn.opcode == Opcode.IGET_OBJECT && it.definingClass == controller.type
            }
        }.firstOrNull()
        needMove(holder != null) { "file list holder field" }
        val count = removeCode.mapNotNull { insn ->
            (insn.ref() as? FieldReference)?.takeIf {
                insn.opcode == Opcode.IGET_WIDE && it.definingClass == holder!!.type && it.type == "J"
            }
        }.firstOrNull()
        needMove(count != null) { "file count field" }
        needMove(removeCode.any { it.opcode == Opcode.CMP_LONG }) { "count comparison" }

        // Reload: the (int, boolean) method of the controller that "remove" itself calls for the big-list case.
        val reloads = remove.calls().filter {
            it.definingClass == controller.type && it.parameterTypes == listOf("I", "Z") && it.returnType == "V"
        }.distinctBy { it.toString() }
        needMove(reloads.size == 1) { "reload calls: ${reloads.size}" }
        val reload = reloads.single()

        // Current index: the int field read by the no-argument current-file accessor.
        val indexed = controller.methods.single { it.parameterTypes == listOf("I") && it.string(INDEXED_MESSAGE) }
        val current = controller.methods.single { method ->
            method.parameterTypes.isEmpty() && method.returnType == indexed.returnType &&
                method.calls().any { it.definingClass == controller.type && it.name == indexed.name && it.parameterTypes == listOf("I") }
        }
        val currentIndex = current.code().mapNotNull { insn ->
            (insn.ref() as? FieldReference)?.takeIf {
                insn.opcode == Opcode.IGET && it.definingClass == controller.type && it.type == "I"
            }
        }.distinctBy { it.toString() }
        needMove(currentIndex.size == 1) { "current index fields: ${currentIndex.size}" }
        val index = currentIndex.single()

        // Selected index: the other int field "remove" reads; callers set it to the current index first.
        val selectedIndex = removeCode.mapNotNull { insn ->
            (insn.ref() as? FieldReference)?.takeIf {
                insn.opcode == Opcode.IGET && it.definingClass == controller.type && it.type == "I" && it.name != index.name
            }
        }.distinctBy { it.toString() }
        needMove(selectedIndex.size == 1) { "selected index fields: ${selectedIndex.size}" }
        val selected = selectedIndex.single()

        needMove(PAGER_CONTROL !in controller.interfaces && controller.methods.none { it.name == "modKitIsLastEntry" }) {
            "controller already patched"
        }
        controller.interfaces.add(PAGER_CONTROL)

        controller.bridge("modKitIsLastEntry", emptyList(), "Z", 6, """
            iget-object v0, p0, $holder
            iget-wide v1, v0, $count
            const-wide/16 v3, 0x1
            cmp-long v1, v1, v3
            if-nez v1, :many
            const/4 v0, 0x1
            return v0
            :many
            const/4 v0, 0x0
            return v0
        """.trimIndent())

        // The app's method reads the "selected" index set by its callers; it replaces the list object when it
        // updates the pager at once, and only starts an asynchronous reload otherwise (returns true then).
        // The saved selected index (v2) is read before the guarded range, so the error path restores it too.
        controller.bridge("modKitRemoveCurrent", emptyList(), "Z", 5, """
            iget v2, p0, $selected
            iget-object v0, p0, $holder
            iget v1, p0, $index
            iput v1, p0, $selected
            invoke-virtual {p0}, $remove
            iput v2, p0, $selected
            iget-object v1, p0, $holder
            if-eq v0, v1, :reloading
            const/4 v0, 0x0
            return v0
            :reloading
            const/4 v0, 0x1
            return v0
        """.trimIndent(), guardFrom = 1, onError = "iput v2, p0, $selected")

        // The app reloads its list from the window start, max(0, index - 50), after its own moves.
        controller.bridge("modKitReload", emptyList(), "V", 3, """
            iget v0, p0, $index
            add-int/lit8 v0, v0, -0x32
            const/4 v1, 0x0
            invoke-static {v1, v0}, Ljava/lang/Math;->max(II)I
            move-result v0
            const/4 v1, 0x1
            invoke-virtual {p0, v0, v1}, $reload
            return-void
        """.trimIndent())

        // Registering from the constructor also switches "Send to…" on (see PreviewHooks.registerPager).
        val constructors = controller.methods.filter { it.name == "<init>" }
        needMove(constructors.isNotEmpty()) { "controller without constructor" }
        for (constructor in constructors) {
            val returns = constructor.code().withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
            needMove(returns.isNotEmpty()) { "controller constructor without return" }
            for ((position) in returns.reversed()) {
                (constructor as MutableMethod).addInstructions(
                    position, "invoke-static/range {p0 .. p0}, $PAGER_HOOK->registerPager(Ljava/lang/Object;)V",
                )
            }
        }
    }
}

private fun isRemoveCurrent(method: Method) =
    method.parameterTypes.isEmpty() && method.returnType == "V" &&
        method.string(ONLY_ONE_MESSAGE) && method.string(INVALID_POSITION_MESSAGE)
