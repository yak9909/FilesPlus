package app.filesplus.patches.preview

import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
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
private const val LIST = "Ljava/util/List;"
private const val LIST_HANDOFF = "${Constants.EXT}preview/ListHandoff;"
private const val SELECTION_TARGET = "${Constants.EXT}preview/SelectionTarget;"

/** Saved-state key of the list screen's selection model; its class is the hand-off target. */
private const val SELECTION_STATE_TAG = "SELECTED_DATA_STATE_TAG"

/** R.menu.file_preview_menu: the preview screen's top-bar menu. */
private const val PREVIEW_MENU_RES = 0x7f0f000eL

/** Presence bit of the MediaStore id in the app's file proto (not a name: it is the literal the app tests). */
private const val MEDIA_ID_BIT = 131072L

internal fun Method.code() = implementation?.instructions?.toList().orEmpty()
internal fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
internal fun Method.string(text: String) = code().any { (it.ref() as? StringReference)?.string == text }
internal fun Method.literal(value: Long) = code().any { (it as? WideLiteralInstruction)?.wideLiteral == value }
internal fun Method.calls() = code().mapNotNull { it.ref() as? MethodReference }

private fun need(condition: Boolean, detail: () -> String) {
    if (!condition) throw PatchException("Multi-select in preview: unexpected ${detail()}")
}

val previewMultiSelectPatch = bytecodePatch(
    name = "Multi-select in preview",
    description = "Add Select / Deselect items to the preview screen's menu to collect files while previewing, and select them in the list when you go back.",
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

        val menuEligibility = previewMenuEligibility(indexed, current, menuCode.take(inflateIndex))
        installPreviewLongPress(peer, current, bitsRef!!, idRef!!, menuEligibility)

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
        // Pass the current entry as well so pending clipboard moves can match a page without a MediaStore id.
        val pageBridge = peer.bridge("modKitPreviewPage", emptyList(), "V", 7, """
            $loadCurrent
            invoke-static {v0, v2, v3, v4, v5}, $HOOK->onPageSelected(${VIEW}JZLjava/lang/Object;)V
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

        // Hand-off to the list: the selection model class is the one whose constructor creates the state
        // holder under the saved-state key below (the same anchor the folder-tab patches use).
        val modelTypes = mutableListOf<String>()
        classDefForEach { cls ->
            if (cls.methods.any { it.name == "<init>" && it.string(SELECTION_STATE_TAG) }) modelTypes.add(cls.type)
        }
        need(modelTypes.size == 1) { "selection model classes: ${modelTypes.size}" }
        val model = mutableClassDefBy(modelTypes.single())
        val constructor = model.methods.filter { it.name == "<init>" }.also { need(it.size == 1) { "selection model constructors: ${it.size}" } }.single()

        // "Add these entries to the selection": (List, boolean)V that pushes the update to the list observers
        // (the app's select-all and single-select paths call it with the same proto entries). false = no analytics event.
        val addEntries = model.methods.filter {
            it.parameterTypes == listOf(LIST, "Z") && it.returnType == "V" && it.string("SELECTION_DATA_SOURCE")
        }.also { need(it.size == 1) { "selection model add methods: ${it.size}" } }.single()

        // The merge step must work on the very same entry type the preview hands over.
        var mergesPreviewEntries = false
        classDefForEach { cls ->
            if (cls.methods.any { m ->
                    m.literal(MEDIA_ID_BIT) && m.code().any { insn ->
                        insn.opcode == Opcode.CHECK_CAST && (insn.ref() as? TypeReference)?.type == entryType
                    } && m.calls().any { it.definingClass == "Ljava/util/Set;" && it.name == "contains" }
                }) mergesPreviewEntries = true
        }
        need(mergesPreviewEntries) { "no selection merge step for entry type $entryType" }

        need(SELECTION_TARGET !in model.interfaces && model.methods.none { it.name == "modKitAddSelected" }) { "model already patched" }
        model.interfaces.add(SELECTION_TARGET)
        model.bridge("modKitAddSelected", listOf(LIST), "V", 3, """
            const/4 v0, 0x0
            invoke-virtual {p0, p1, v0}, $addEntries
            return-void
        """.trimIndent())

        val returns = constructor.code().withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
        need(returns.isNotEmpty()) { "selection model constructor without return" }
        for ((index) in returns.reversed()) {
            (constructor as MutableMethod).addInstructions(index, "invoke-static/range {p0 .. p0}, $LIST_HANDOFF->registerModel(Ljava/lang/Object;)V")
        }
    }
}

/** Reads the current pager row and runs the same two exclusion checks as native menu inflation. */
private fun previewMenuEligibility(indexed: Method, current: Method, beforeInflate: List<Instruction>): String {
    val index = current.code().single { it.opcode == Opcode.IGET }.ref() as FieldReference
    val lookup = indexed.code().dropWhile { it.opcode != Opcode.IGET_OBJECT }
    need(lookup.take(14).map { it.opcode } == listOf(
        Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.CHECK_CAST,
        Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT, Opcode.SUB_INT_2ADDR, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST, Opcode.IGET_OBJECT,
    )) { "indexed preview row lookup" }
    val window = lookup[0].ref() as FieldReference
    val rows = lookup[1].ref() as FieldReference
    val bounds = lookup[2].ref() as FieldReference
    val boundsType = (lookup[3].ref() as TypeReference).type
    val start = lookup[4].ref() as MethodReference
    val intValue = lookup[7].ref() as MethodReference
    val rowAt = lookup[10].ref() as MethodReference
    val rowType = (lookup[12].ref() as TypeReference).type
    need(index.definingClass == current.definingClass && index.type == "I" &&
        window.definingClass == indexed.definingClass && rows.definingClass == window.type &&
        bounds.definingClass == window.type && start.definingClass == boundsType &&
        intValue.toString() == "Ljava/lang/Integer;->intValue()I" &&
        rowAt.toString() == "$LIST->get(I)Ljava/lang/Object;"
    ) { "indexed preview row references" }

    val checks = beforeInflate.withIndex().mapNotNull { (i, instruction) ->
        (instruction.ref() as? MethodReference)?.takeIf {
            instruction.opcode == Opcode.INVOKE_STATIC && it.returnType == "Z" &&
                beforeInflate.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT &&
                beforeInflate.getOrNull(i + 2)?.opcode == Opcode.IF_NEZ
        }
    }
    val rowExcluded = checks.filter {
        it.definingClass == indexed.definingClass && it.parameterTypes == listOf(rowType)
    }.longPressMatch("native menu row exclusion")
    val entryExcluded = checks.filter {
        it.parameterTypes == listOf(indexed.returnType)
    }.longPressMatch("native menu trash exclusion")
    return """
        iget-object v0, p0, $window
        iget-object v1, v0, $rows
        check-cast v1, $LIST
        iget-object v0, v0, $bounds
        check-cast v0, $boundsType
        invoke-virtual {v0}, $start
        move-result-object v0
        check-cast v0, Ljava/lang/Integer;
        invoke-virtual {v0}, $intValue
        move-result v0
        iget v2, p0, $index
        sub-int/2addr v2, v0
        invoke-interface {v1, v2}, $rowAt
        move-result-object v0
        check-cast v0, $rowType
        invoke-static {v0}, $rowExcluded
        move-result v0
        if-nez v0, :unhandled
        invoke-static {v4}, $entryExcluded
        move-result v0
        if-nez v0, :unhandled
    """.trimIndent()
}

private fun BytecodePatchContext.installPreviewLongPress(
    peer: MutableClass, current: MethodReference, bits: FieldReference, id: FieldReference,
    menuEligibility: String,
) {
    val dragMethods = mutableListOf<Method>()
    classDefForEach { cls ->
        cls.methods.filterTo(dragMethods) {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                it.parameterTypes.size == 7 && it.parameterTypes.first() == VIEW &&
                it.string("drag and drop logging") && it.string("files-source") && it.string("parent-folder-uri")
        }
    }
    val drag = dragMethods.longPressMatch("native drag starter")
    val entryPoint = drag.parameterTypes[3].toString()
    val safeName = "SAFE_FOLDER_BROWSER"
    val entryPoints = classDefBy(entryPoint).methods.filter {
        it.name == "<clinit>" && it.string(safeName)
    }.longPressMatch("entry-point enum initializer").code()
    val safeNameIndex = entryPoints.indexOfFirst { (it.ref() as? StringReference)?.string == safeName }
    val safeFolder = entryPoints.drop(safeNameIndex + 1).firstNotNullOf { instruction ->
        (instruction.ref() as? FieldReference)?.takeIf {
            instruction.opcode == Opcode.SPUT_OBJECT && it.definingClass == entryPoint && it.type == entryPoint
        }
    }

    val handlers = mutableListOf<Method>()
    classDefForEach { cls ->
        cls.methods.filterTo(handlers) { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 1 &&
                method.returnType.startsWith("L") && method.calls().any { it == drag } &&
                method.calls().any { it == current } && method.code().any {
                    it.opcode == Opcode.SGET_OBJECT && it.ref() == safeFolder
                }
        }
    }
    val match = handlers.longPressMatch("preview event handler")
    val handler = mutableClassDefBy(match.definingClass).methods.single { it == match }
    val code = handler.code()
    val dragIndex = code.indices.single { code[it].ref() == drag }
    val safeIndex = code.indices.single { code[it].opcode == Opcode.SGET_OBJECT && code[it].ref() == safeFolder }
    val safeCheck = code[safeIndex + 1]
    val acknowledge = code[dragIndex + 1]
    val handled = acknowledge.ref() as? FieldReference
    need(acknowledge.opcode == Opcode.SGET_OBJECT && handled?.type == handler.returnType &&
        code[dragIndex + 2].opcode == Opcode.RETURN_OBJECT &&
        (code[dragIndex + 2] as OneRegisterInstruction).registerA ==
            (acknowledge as OneRegisterInstruction).registerA
    ) { "preview drag acknowledgment" }
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    need(safeCheck.opcode == Opcode.IF_EQ &&
        (safeCheck as TwoRegisterInstruction).registerB == (code[safeIndex] as OneRegisterInstruction).registerA &&
        addresses[safeIndex + 1] + (safeCheck as OffsetInstruction).codeOffset == addresses[dragIndex + 1]
    ) { "Safe folder guard must bypass the preview drag block" }

    val read = (safeIndex + 2 until dragIndex).single { code[it].ref() == current }
    val viewCall = code[read + 2].ref() as? MethodReference
    val listCall = code[read + 4].ref() as? MethodReference
    need(code[read].opcode == Opcode.INVOKE_VIRTUAL && code[read + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
        code[read + 2].opcode == Opcode.INVOKE_VIRTUAL && viewCall?.returnType == VIEW &&
        viewCall.parameterTypes.isEmpty() && code[read + 3].opcode == Opcode.MOVE_RESULT_OBJECT &&
        code[read + 4].opcode == Opcode.INVOKE_STATIC && listCall?.returnType == drag.parameterTypes[1] &&
        code[read + 5].opcode == Opcode.MOVE_RESULT_OBJECT
    ) { "preview drag file, view, and singleton list sequence" }

    val peerReg = (code[read] as FiveRegisterInstruction).registerC
    val entryReg = (code[read + 1] as OneRegisterInstruction).registerA
    val viewReg = (code[read + 3] as OneRegisterInstruction).registerA
    val listInvoke = code[read + 4] as FiveRegisterInstruction
    val scratch = (code[read + 5] as OneRegisterInstruction).registerA
    val impl = handler.implementation!!
    val parameterRegisters = 1 + handler.parameterTypes.size + handler.parameterTypes.count { it == "J" || it == "D" }
    need(listInvoke.registerCount == 1 && listInvoke.registerC == entryReg &&
        listOf(peerReg, entryReg, viewReg).all { it <= 15 && it != scratch } &&
        scratch < impl.registerCount - parameterRegisters && impl.tryBlocks.none {
            addresses[read + 4] in it.startCodeAddress until (it.startCodeAddress + it.codeUnitCount)
        }
    ) { "preview drag registers or try range" }

    val bridge = peer.bridge("modKitPreviewLongPress", listOf(VIEW, current.returnType), "Z", 8, """
        move-object v4, p2
        iget v3, v4, $bits
        const v1, $MEDIA_ID_BIT
        and-int/2addr v3, v1
        if-nez v3, :has_id
        const/4 v0, 0x0
        return v0
        :has_id
        $menuEligibility
        move-object v0, p1
        iget-wide v1, v4, $id
        const/4 v3, 0x1
        invoke-static/range {v0 .. v4}, $HOOK->onLongPress(${VIEW}JZLjava/lang/Object;)Z
        move-result v0
        return v0
        :unhandled
        const/4 v0, 0x0
        return v0
    """.trimIndent())

    // Reuse the file and root the app just looked up. The native list call does not read
    // the scratch register and its move-result overwrites it on the fallback path.
    handler.addInstructionsWithLabels(read + 4, """
        invoke-virtual {v$peerReg, v$viewReg, v$entryReg}, $bridge
        move-result v$scratch
        if-eqz v$scratch, :original
        sget-object v$scratch, $handled
        return-object v$scratch
    """.trimIndent(), ExternalLabel("original", code[read + 4]))

    mutableClassDefBy(HOOK).methods.single {
        it.name == "longPressInstalled" && it.parameterTypes.isEmpty() && it.returnType == "Z"
    }.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
}

private fun <T : MethodReference> List<T>.longPressMatch(label: String): T {
    need(size == 1) { "Preview long-press fingerprint $label: expected 1 match, got $size" }
    return single().also { println("Preview long-press fingerprint $label: 1 match ($it)") }
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

/**
 * Low-register helper with a Throwable guard: a failing hook logs and falls back to the original behavior.
 * [guardFrom] leading instructions stay outside the guarded range (they may define registers that [onError] uses).
 */
internal fun MutableClass.bridge(
    name: String, parameters: List<String>, returns: String, registers: Int, body: String,
    guardFrom: Int = 0, onError: String = "",
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
        $onError
        $fallback
    """.trimIndent())
    method.implementation!!.apply {
        addCatch("Ljava/lang/Throwable;", newLabelForIndex(guardFrom), newLabelForIndex(handler), newLabelForIndex(handler))
    }
    methods.add(method)
    return method
}
