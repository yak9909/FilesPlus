package app.filesplus.patches.preview

import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val HOOK = "${Constants.EXT}preview/PreviewHooks;"
private const val VIEW = "Landroid/view/View;"
private const val MENU = "Landroid/view/Menu;"
private const val MENU_ITEM = "Landroid/view/MenuItem;"
private const val MENU_INFLATER = "Landroid/view/MenuInflater;"

/** R.menu.file_preview_menu: the preview screen's top-bar menu. */
private const val PREVIEW_MENU_RES = 0x7f0f000eL

/** Presence bit of the MediaStore id in the app's file proto (not a name: it is the literal the app tests). */
private const val MEDIA_ID_BIT = 131072L

private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
private fun Method.string(text: String) = code().any { (it.ref() as? StringReference)?.string == text }
private fun Method.literal(value: Long) = code().any { (it as? WideLiteralInstruction)?.wideLiteral == value }
private fun Method.calls() = code().mapNotNull { it.ref() as? MethodReference }

private fun need(condition: Boolean, detail: () -> String) {
    if (!condition) throw PatchException("Multi-select in preview: unexpected ${detail()}")
}

val previewMultiSelectPatch = bytecodePatch(
    name = "Multi-select in preview",
    description = "Add Select / Deselect items to the preview screen's menu to collect files while previewing.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch)

    execute {
        // The gallery fragment: the only class whose (Menu, MenuInflater) method inflates file_preview_menu.
        val fragment = mutableClassDefBy(classDefBy { cls -> cls.methods.any { isPreviewMenuMethod(it) } })
        val createMenu = fragment.methods.single { isPreviewMenuMethod(it) }
        val onItem = fragment.methods.single {
            it.parameterTypes == listOf(MENU_ITEM) && it.returnType == "Z" &&
                it.string("Fragment:onOptionsItemSelected")
        }

        // The fragment's peer accessor: the first no-argument call on the fragment itself after super.
        val menuCode = createMenu.code()
        val peerCall = menuCode.firstOrNull { insn ->
            insn.opcode == Opcode.INVOKE_VIRTUAL && (insn.ref() as? MethodReference)?.let {
                it.definingClass == fragment.type && it.parameterTypes.isEmpty() && it.returnType.startsWith("L")
            } == true
        }
        need(peerCall != null) { "menu method without peer accessor" }
        val peerAccessor = peerCall!!.ref() as MethodReference
        val peerType = peerAccessor.returnType
        val peer = mutableClassDefBy(peerType)
        val fragmentField = peer.fields.single { it.type == fragment.type }

        // Current file: peer.c() delegates to the indexed accessor that carries the "invalid index" message.
        val indexed = peer.methods.single {
            it.parameterTypes == listOf("I") && it.string("File preview index is not valid.")
        }
        val entryType = indexed.returnType
        val current = peer.methods.single { method ->
            method.parameterTypes.isEmpty() && method.returnType == entryType &&
                method.calls().any { it.definingClass == peer.type && it.name == indexed.name && it.parameterTypes == listOf("I") }
        }

        // MediaStore id: the app's own "has id" test on the file proto reads the presence bits, then the long id.
        val idChecker = classDefBy { cls -> cls.methods.any { isMediaIdCheck(it, entryType) } }
            .methods.first { isMediaIdCheck(it, entryType) }
        val idCode = idChecker.code()
        val bitsRef = idCode.mapNotNull { insn -> (insn.ref() as? FieldReference)?.takeIf {
            insn.opcode == Opcode.IGET && it.definingClass == entryType && it.type == "I" } }.firstOrNull()
        val idRef = idCode.mapNotNull { insn -> (insn.ref() as? FieldReference)?.takeIf {
            insn.opcode == Opcode.IGET_WIDE && it.definingClass == entryType && it.type == "J" } }.firstOrNull()
        need(bitsRef != null && idRef != null) { "file proto id fields" }

        // The fragment root view (Fragment.mView): the only View-typed field the gallery fragment reads.
        val superTypes = generateSequence(fragment.type) { classDefByOrNull(it)?.superclass }.toSet()
        val viewRefs = fragment.methods.flatMap { method ->
            method.code().filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.ref() as? FieldReference }
        }.filter { it.type == VIEW && it.definingClass in superTypes }.distinctBy { it.toString() }
        need(viewRefs.size == 1) { "fragment view fields: ${viewRefs.size}" }
        val viewField = viewRefs.single()

        // Registers at the inflate call: the peer (kept in a local) and the menu argument, both live afterwards.
        val peerCallIndex = menuCode.indexOf(peerCall)
        val moveResult = menuCode[peerCallIndex + 1]
        need(moveResult.opcode == Opcode.MOVE_RESULT_OBJECT) { "peer result move" }
        val peerRegister = (moveResult as OneRegisterInstruction).registerA
        val inflateIndex = menuCode.indexOfFirst { insn ->
            insn.opcode == Opcode.INVOKE_VIRTUAL && (insn.ref() as? MethodReference)?.let {
                it.definingClass == MENU_INFLATER && it.name == "inflate" && it.parameterTypes == listOf("I", MENU)
            } == true
        }
        need(inflateIndex > peerCallIndex) { "inflate call position" }
        need(menuCode.withIndex().any { (i, insn) ->
            i in peerCallIndex..inflateIndex && (insn as? WideLiteralInstruction)?.wideLiteral == PREVIEW_MENU_RES
        }) { "menu resource before inflate" }
        val inflate = menuCode[inflateIndex] as? FiveRegisterInstruction
        need(inflate != null && inflate.registerCount == 3) { "inflate call form" }
        val menuRegister = inflate!!.registerE
        need(peerRegister <= 15 && menuRegister <= 15) { "register numbers $peerRegister/$menuRegister" }
        for (register in listOf(peerRegister, menuRegister)) {
            need(menuCode.subList(0, inflateIndex).withIndex().none { (i, insn) ->
                i > peerCallIndex + 1 && insn is OneRegisterInstruction && insn.registerA == register &&
                    insn.opcode.setsRegister() && insn.opcode != Opcode.CHECK_CAST
            }) { "register v$register is overwritten before inflate" }
        }
        need(menuRegister != peerRegister) { "peer and menu share a register" }

        // Reads the current file for both bridges: v0 root view, v2/v3 id, v4 has-id, v5 entry.
        val loadCurrent = """
            iget-object v0, p0, $fragmentField
            iget-object v0, v0, $viewField
            invoke-virtual {p0}, $current
            move-result-object v5
            iget v4, v5, $bitsRef
            const v2, $MEDIA_ID_BIT
            and-int/2addr v4, v2
            const-wide/16 v2, 0x0
            if-eqz v4, :call
            iget-wide v2, v5, $idRef
            const/4 v4, 0x1
            :call
        """.trimIndent()

        val menuBridge = peer.bridge("modKitPreviewMenu", listOf(MENU), "V", 8, """
            $loadCurrent
            move-object v1, p1
            invoke-static/range {v0 .. v5}, $HOOK->onMenuCreated(${VIEW}${MENU}JZLjava/lang/Object;)V
            return-void
        """.trimIndent())
        val itemBridge = peer.bridge("modKitPreviewItem", listOf(MENU_ITEM), "Z", 8, """
            invoke-interface {p1}, $MENU_ITEM->getItemId()I
            move-result v1
            invoke-static {v1}, $HOOK->handles(I)Z
            move-result v0
            if-nez v0, :ours
            const/4 v0, 0x0
            return v0
            :ours
            $loadCurrent
            invoke-static/range {v0 .. v5}, $HOOK->onMenuItem(${VIEW}IJZLjava/lang/Object;)Z
            move-result v0
            return v0
        """.trimIndent())
        // Page change: the same current-file read, handed to the overlay. Registers: v0 root, v2/v3 id, v4 has-id.
        val pageBridge = peer.bridge("modKitPreviewPage", emptyList(), "V", 7, """
            $loadCurrent
            invoke-static {v0, v2, v3, v4}, $HOOK->onPageSelected(${VIEW}JZ)V
            return-void
        """.trimIndent())
        val fragmentBridge = fragment.bridge("modKitPreviewItem", listOf(MENU_ITEM), "Z", 3, """
            invoke-virtual {p0}, $peerAccessor
            move-result-object v0
            invoke-virtual {v0, p1}, $itemBridge
            move-result v0
            return v0
        """.trimIndent())

        // Items are added right after the app inflated its menu, so entries the app skips never get them.
        (createMenu as MutableMethod).addInstructions(
            inflateIndex + 1, "invoke-virtual {v$peerRegister, v$menuRegister}, $menuBridge",
        )
        // Clicks: let the extension handle only its own item ids, before the app's if-chain.
        val first = onItem.code().first()
        (onItem as MutableMethod).addInstructionsWithLabels(0, """
            invoke-virtual/range {p0 .. p1}, $fragmentBridge
            move-result v0
            if-eqz v0, :original
            return v0
        """.trimIndent(), ExternalLabel("original", first))

        // ViewPager2 page-change callback: the (I)V method that closes the delete / trash dialogs (their fragment
        // tags are stable strings) and reads the current file through the peer, behind the peer's validity check.
        val pageCallbackClass = mutableClassDefBy(classDefBy { cls -> cls.methods.any { isPageSelected(it, peer.type, current.name) } })
        val onPage = pageCallbackClass.methods.single { isPageSelected(it, peer.type, current.name) }
        val pageCode = onPage.code()
        val currentIndex = pageCode.indexOfFirst { insn ->
            (insn.ref() as? MethodReference)?.let {
                insn.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == peer.type && it.name == current.name && it.parameterTypes.isEmpty()
            } == true
        }
        need(currentIndex > 0) { "current-file read in page callback" }
        need(pageCode.subList(0, currentIndex).any { insn ->
            (insn.ref() as? MethodReference)?.let {
                it.definingClass == peer.type && it.parameterTypes.isEmpty() && it.returnType == "Z"
            } == true
        }) { "validity check before the current-file read" }
        val currentCall = pageCode[currentIndex] as FiveRegisterInstruction
        val currentResult = pageCode[currentIndex + 1]
        need(currentResult.opcode == Opcode.MOVE_RESULT_OBJECT) { "page callback result move" }
        val pagePeerRegister = currentCall.registerC
        need((currentResult as OneRegisterInstruction).registerA != pagePeerRegister) { "page callback peer register reuse" }
        // After the app's own read (so only valid pages notify, and the early-return paths stay untouched).
        (onPage as MutableMethod).addInstructions(
            currentIndex + 2, "invoke-virtual/range {v$pagePeerRegister .. v$pagePeerRegister}, $pageBridge",
        )
    }
}

private fun isPreviewMenuMethod(method: Method) =
    method.parameterTypes == listOf(MENU, MENU_INFLATER) && method.returnType == "V" && method.literal(PREVIEW_MENU_RES)

private fun isPageSelected(method: Method, peerType: String, currentName: String) =
    method.parameterTypes == listOf("I") && method.returnType == "V" &&
        method.string("DELETE_DIALOG") && method.string("MOVE_TO_TRASH_DIALOG_TAG") &&
        method.calls().any { it.definingClass == peerType && it.name == currentName && it.parameterTypes.isEmpty() }

/** `boolean f(Entry)` that tests the id presence bit, else falls back to the content URI. */
private fun isMediaIdCheck(method: Method, entryType: String) =
    method.parameterTypes == listOf(entryType) && method.returnType == "Z" && method.literal(MEDIA_ID_BIT) &&
        method.calls().any { it.definingClass == "Landroid/net/Uri;" && it.name == "parse" }

/** Low-register helper with a Throwable guard: a failing hook logs and falls back to the original behavior. */
private fun MutableClass.bridge(
    name: String, parameters: List<String>, returns: String, registers: Int, body: String,
): MutableMethod {
    check(methods.none { it.name == name }) { "Preview bridge already exists: $type->$name" }
    val method = MutableMethod(ImmutableMethod(type, name,
        parameters.map { ImmutableMethodParameter(it, emptySet(), null) }, returns, AccessFlags.PUBLIC.value,
        emptySet(), emptySet(), MutableMethodImplementation(registers)))
    method.addInstructions(0, body)
    val handler = method.code().size
    val fallback = if (returns == "Z") "const/4 v0, 0x0\nreturn v0" else "return-void"
    method.addInstructions(handler, """
        move-exception v0
        invoke-static {v0}, $HOOK->hookError(Ljava/lang/Throwable;)V
        $fallback
    """.trimIndent())
    method.implementation!!.apply {
        addCatch("Ljava/lang/Throwable;", newLabelForIndex(0), newLabelForIndex(handler), newLabelForIndex(handler))
    }
    methods.add(method)
    return method
}
