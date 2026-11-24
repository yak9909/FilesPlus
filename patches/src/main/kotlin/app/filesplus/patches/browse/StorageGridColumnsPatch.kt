package app.filesplus.patches.browse

import app.filesplus.patches.settings.settingsInjectionPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val HOOK = "${Constants.EXT}browse/StorageGridColumns;"
private const val PLAN = "${Constants.EXT}browse/StorageGridColumns\$Plan;"
private const val GRID = "Lcom/google/android/apps/nbu/files/recyclerview/view/GridLayoutRecyclerView;"
private const val RECYCLER = "Landroid/support/v7/widget/RecyclerView;"
private const val MANAGER = "Landroid/support/v7/widget/GridLayoutManager;"
private const val CONTEXT = "Landroid/content/Context;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val RECT = "Landroid/graphics/Rect;"
private const val VIEW = "Landroid/view/View;"
private const val THROWABLE = "Ljava/lang/Throwable;"
private const val GET_INTEGER = "Landroid/content/res/Resources;->getInteger(I)I"
private const val LIST_ADD = "$LIST->add($OBJECT)Z"
private const val LIST_GET = "$LIST->get(I)$OBJECT"
private const val SPAN_COUNT = 60

val storageGridColumnsPatch = bytecodePatch(
    name = "Storage grid columns",
    description = "Choose folder and file columns separately in the storage browser's grid.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(browserHooksPatch, settingsInjectionPatch)

    execute {
        val browser = takeBrowserBindings()
        val owner = browser.storage
        val fileType = browser.filePopup.parameterTypes[2].toString()
        val spanResource = owner.methods.fingerprint("storage column resource") {
            it.parameterTypes.isEmpty() && it.returnType == "I" &&
                it.resource("integer/grid_span_count") && it.resource("integer/grid_view_column_count")
        }
        val nativeSetter = classDefBy(GRID).methods.fingerprint("grid span setter") {
            it.parameterTypes == listOf("I") && it.returnType == "V" &&
                it.calls().any { call -> call.toString() == "$MANAGER->setSpanCount(I)V" }
        }
        val storageSetter = owner.methods.fingerprint("storage view mode") {
            it.parameterTypes.size == 2 && it.parameterTypes[1] == GRID && it.returnType == "V" &&
                it.calls().any { call -> call.toString() == nativeSetter.toString() } &&
                it.calls().any { call -> call.toString() == GET_INTEGER } &&
                it.calls().any { call -> call.toString() == spanResource.toString() }
        } as MutableMethod
        val mode = owner.fields.single { it.type == storageSetter.parameterTypes[0].toString() }
        val update = gridFingerprint("storage span lookup") {
            it.parameterTypes == listOf("I") && it.returnType == "I" &&
                it.string("Index out of bounds! Index larger than currentContent list size!") &&
                it.calls().any { call -> call.toString() == LIST_GET } &&
                it.code().any { op -> op.opcode == Opcode.IGET_OBJECT && op.field()?.type == owner.type }
        }
        val updateCode = update.code()
        val readCount = updateCode.indexOfFirst {
            it.opcode == Opcode.IGET && it.field()?.definingClass == owner.type
        }
        val originalCount = checkNotNull(updateCode[readCount].field())
        val page = checkNotNull(updateCode[readCount + 1].field())
        val folderCount = checkNotNull(updateCode[readCount + 2].field())
        check(page.definingClass == owner.type && folderCount.definingClass == page.type && folderCount.type == "I")

        val paging = gridFingerprint("storage paging and spacer") {
            it.parameterTypes == listOf(OBJECT) && it.returnType == "V" && it.string("Dummy") &&
                it.string("Unknown folder state.") && it.resource("id/empty_state") && it.resource("id/hidden_file_state") &&
                it.calls().any { call -> call.toString() == spanResource.toString() }
        }
        val reload = owner.methods.fingerprint("storage page fetch") {
            it.parameterTypes == listOf("I", "I") && it.returnType == "V" &&
                it.calls().any { call -> call.toString() == "Landroid/net/Uri;->parse($STRING)Landroid/net/Uri;" } &&
                it.code().any { op -> op.opcode == Opcode.IGET_OBJECT && op.field()?.type == paging.definingClass }
        }
        val newPlan = MutableField(ImmutableField(owner.type, "modKitStorageGridPlan", PLAN,
            AccessFlags.PUBLIC.value, null, emptySet(), emptySet()))
        owner.fields.add(newPlan)

        bindSetup(owner, storageSetter, nativeSetter, mode, newPlan, reload)
        bindLookup(update, newPlan, browser.folderType, fileType)
        val gap = bindPaging(owner, paging, newPlan, page, folderCount, originalCount)
        bindKeyboard(owner, spanResource, gap)
        bindDecoration(nativeSetter)

        mutableClassDefBy(HOOK).methods.single {
            it.name == "installed" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
    }
}

private fun BytecodePatchContext.bindSetup(
    owner: MutableClass, setter: MutableMethod, nativeSetter: Method, mode: FieldReference,
    plan: FieldReference, reload: Method,
) {
    val recycler = classDefBy(RECYCLER)
    val adapter = recycler.fields.single { it.name == "mAdapter" }
    val invalidate = recycler.methods.single { it.name == "invalidateItemDecorations" && it.parameterTypes.isEmpty() }
    val reloadFields = reload.code().take(2).map {
        check(it.opcode == Opcode.IPUT)
        checkNotNull(it.field()).also { field -> check(field.definingClass == owner.type && field.type == "I") }
    }
    val setup = owner.bridge("modKitSetStorageGrid", listOf(owner.type, GRID, "I"), "V", 4, """
        iget-object v0, p0, $mode
        invoke-static {v0, p2}, $HOOK->plan(${OBJECT}I)$PLAN
        move-result-object v0
        iget-object v1, p0, $plan
        move v2, p2
        if-eqz v0, :apply
        const/16 v2, $SPAN_COUNT
        :apply
        invoke-virtual {p1, v2}, $nativeSetter
        iput-object v0, p0, $plan
        invoke-static {v1, v0}, $HOOK->changed($PLAN$PLAN)Z
        move-result v3
        if-eqz v3, :done
        iget-object v3, p1, $adapter
        if-eqz v3, :done
        invoke-virtual {p1}, $invalidate
        iget v2, p0, ${reloadFields[0]}
        iget v3, p0, ${reloadFields[1]}
        invoke-virtual {p0, v2, v3}, $reload
        :done
        return-void
        move-exception v0
        invoke-static {v0}, $HOOK->hookError($THROWABLE)V
        const/4 v0, 0x0
        iput-object v0, p0, $plan
        invoke-virtual {p1, p2}, $nativeSetter
        return-void
    """)
    val code = setter.code()
    val call = code.indices.single { code[it].ref().toString() == nativeSetter.toString() }
    val setterArgs = code[call] as FiveRegisterInstruction
    val ownerArg = (code[call - 1] as TwoRegisterInstruction).registerB
    check(code[call - 1].opcode == Opcode.IPUT && ownerArg == setter.implementation!!.registerCount - 3)
    setter.checkRegisters(ownerArg, setterArgs.registerC, setterArgs.registerD)
    setter.replaceInstruction(call, "invoke-static {v$ownerArg, v${setterArgs.registerC}, v${setterArgs.registerD}}, $setup")
}

private fun BytecodePatchContext.bindLookup(method: Method, plan: FieldReference, folder: String, file: String) {
    val owner = mutableClassDefBy(method.definingClass)
    val lookup = owner.mutable(method)
    val span = owner.bridge("modKitStorageItemSpan", listOf(plan.definingClass, OBJECT, "I"), "I", 2, """
        iget-object v0, p0, $plan
        if-eqz v0, :original
        instance-of v1, p1, $folder
        if-nez v1, :span
        instance-of v1, p1, $file
        if-eqz v1, :other
        const/4 v1, 0x2
        goto :span
        :other
        instance-of v1, p1, $STRING
        if-eqz v1, :span
        const/4 v1, 0x3
        :span
        invoke-static {v0, v1, p2}, $HOOK->span(${PLAN}II)I
        move-result v0
        return v0
        move-exception v0
        invoke-static {v0}, $HOOK->hookError($THROWABLE)V
        :original
        const/4 v0, 0x0
        return v0
    """)
    val code = lookup.code()
    val get = code.indices.single { code[it].ref().toString() == LIST_GET }
    val args = code[get] as FiveRegisterInstruction
    val peer = (code[get - 1] as TwoRegisterInstruction).registerB
    val list = args.registerC
    val position = args.registerD
    val originalFlag = (code[get + 2] as TwoRegisterInstruction).registerA
    val fallback = (code.first() as OneRegisterInstruction).registerA
    check(code[get - 1].opcode == Opcode.IGET_OBJECT && code[get + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
        code[get + 2].opcode == Opcode.INSTANCE_OF && (code[get + 2].ref() as TypeReference).type == STRING &&
        code.first().literal() == 1L && setOf(peer, list, position, fallback).size == 4)
    lookup.checkRegisters(peer, list, position, fallback, originalFlag)
    lookup.replaceInstruction(get + 2, "instance-of v$originalFlag, v$list, $STRING")
    lookup.replaceInstruction(get + 1, "move-result-object v$list")
    lookup.addInstructionsWithLabels(get + 2, """
        invoke-static {v$peer, v$list, v$position}, $span
        move-result v$fallback
        if-eqz v$fallback, :original
        return v$fallback
        :original
        const/4 v$fallback, 0x1
    """.trimIndent())
}

private fun BytecodePatchContext.bindPaging(
    owner: MutableClass, method: Method, plan: FieldReference, page: FieldReference,
    folders: FieldReference, originalCount: FieldReference,
): MethodReference {
    val browser = owner.type
    val gap = owner.bridge("modKitStorageGridGap", listOf(browser, "I"), "I", 2, """
        iget-object v0, p0, $plan
        iget-object v1, p0, $page
        iget v1, v1, $folders
        invoke-static {v0, v1, p1}, $HOOK->gap(${PLAN}II)I
        move-result v0
        return v0
        move-exception v0
        invoke-static {v0}, $HOOK->hookError($THROWABLE)V
        return p1
    """, public = true)
    val pagingOwner = mutableClassDefBy(method.definingClass)
    val phase = pagingOwner.bridge("modKitStorageGridPhase", listOf(browser, "I", "I"), "I", 2, """
        iget-object v0, p0, $plan
        iget-object v1, p0, $page
        iget v1, v1, $folders
        invoke-static {v0, p1, v1, p2}, $HOOK->phase(${PLAN}III)I
        move-result v0
        return v0
        move-exception v0
        invoke-static {v0}, $HOOK->hookError($THROWABLE)V
        return p2
    """)
    val addSpacer = pagingOwner.bridge("modKitStorageGridAddSpacer", listOf(browser, LIST, OBJECT, "I"), "V", 3, """
        iget-object v0, p0, $plan
        if-eqz v0, :add
        iget-object v1, p0, $page
        iget v1, v1, $folders
        invoke-interface {p1}, $LIST->size()I
        move-result v2
        invoke-static {v0, p3, v2, v1}, $HOOK->includeSpacer(${PLAN}III)Z
        move-result v0
        if-nez v0, :add
        return-void
        move-exception v0
        invoke-static {v0}, $HOOK->hookError($THROWABLE)V
        :add
        invoke-interface {p1, p2}, $LIST_ADD
        return-void
    """)
    val paging = pagingOwner.mutable(method)
    val code = paging.code()
    val rem = remainderOf(paging)
    val resourceCall = code.take(rem).last { it.ref().toString() == GET_INTEGER }
    val peer = (code[code.indexOf(resourceCall) - 2] as FiveRegisterInstruction).registerC
    check((code[code.indexOf(resourceCall) - 2].ref() as MethodReference).definingClass == browser)
    val result = (code[rem] as TwoRegisterInstruction).registerA
    val phases = code.indices.filter {
        it > rem && code[it].opcode == Opcode.REM_INT && code[it - 1].field() == originalCount
    }
    check(phases.size == 2)
    val spacerAdd = code.indices.single {
        code[it].ref().toString() == LIST_ADD && (code.getOrNull(it - 1)?.ref() as? StringReference)?.string == "Dummy"
    }
    val spacerArgs = code[spacerAdd] as FiveRegisterInstruction
    val firstPhase = code[phases.first()] as ThreeRegisterInstruction
    val offset = firstPhase.registerB
    paging.checkRegisters(peer, result, offset, spacerArgs.registerC, spacerArgs.registerD)
    check(rem < spacerAdd && spacerAdd < phases.first())
    // Insert in descending order so the matched instruction indices stay valid.
    for (i in phases.asReversed()) {
        val op = code[i] as ThreeRegisterInstruction
        paging.checkRegisters(op.registerA, op.registerB)
        check(op.registerB == offset && op.registerA != offset && peer != op.registerA)
        paging.addInstructions(i + 1, """
            invoke-static {v$peer, v$offset, v${op.registerA}}, $phase
            move-result v${op.registerA}
        """.trimIndent())
    }
    paging.replaceInstruction(spacerAdd,
        "invoke-static {v$peer, v${spacerArgs.registerC}, v${spacerArgs.registerD}, v$offset}, $addSpacer")
    paging.addInstructions(rem + 1, "invoke-static {v$peer, v$result}, $gap\nmove-result v$result")
    return gap
}

private fun bindKeyboard(owner: MutableClass, spanResource: Method, gap: MethodReference) {
    val keyboard = owner.methods.fingerprint("storage keyboard navigation") {
        it.returnType == "Z" && it.parameterTypes.size == 1 && it.string("handleKeyboardActionInAdvBrowser") &&
            it.calls().any { call -> call.toString() == spanResource.toString() }
    } as MutableMethod
    val code = keyboard.code()
    val rem = remainderOf(keyboard)
    val result = (code[rem] as TwoRegisterInstruction).registerA
    val peer = (code[code.indexOfFirst { it.ref().toString() == spanResource.toString() }] as FiveRegisterInstruction).registerC
    keyboard.checkRegisters(result, peer)
    keyboard.addInstructions(rem + 1, "invoke-static {v$peer, v$result}, $gap\nmove-result v$result")
}

private fun remainderOf(method: Method): Int {
    val code = method.code()
    return code.indices.single {
        code[it].ref().toString() == GET_INTEGER && code.getOrNull(it + 1)?.opcode == Opcode.MOVE_RESULT &&
            code.getOrNull(it + 2)?.opcode == Opcode.REM_INT_2ADDR
    } + 2
}

private fun BytecodePatchContext.bindDecoration(nativeSetter: Method) {
    val count = nativeSetter.code().single { it.opcode == Opcode.IPUT }.field()!!
    val decoration = classDefBy(count.definingClass).methods.fingerprint("native grid spacing") {
        it.parameterTypes.take(3) == listOf(RECT, VIEW, RECYCLER) && it.parameterTypes.size == 4 &&
            it.returnType == "V" && it.resource("dimen/grid_spacing") &&
            it.calls().any { call -> call.toString() == "$RECYCLER->getChildAdapterPosition(${VIEW})I" }
    }
    val owner = mutableClassDefBy(decoration.definingClass)
    val target = owner.mutable(decoration)
    val code = target.code()
    val countIndex = code.indices.first { code[it].field() == count }
    val countRead = code[countIndex] as TwoRegisterInstruction
    val spanRead = code[countIndex + 3] as TwoRegisterInstruction
    val indexRead = code[countIndex + 6] as TwoRegisterInstruction
    val expressiveRead = code[countIndex + 7] as TwoRegisterInstruction
    val spacingCall = code.indices.single {
        it < countIndex && code[it].ref().toString() == "Landroid/content/res/Resources;->getDimensionPixelSize(I)I"
    }
    val spacing = (code[spacingCall + 1] as OneRegisterInstruction).registerA
    val spanSize = checkNotNull(code[countIndex + 3].field())
    val spanIndex = checkNotNull(code[countIndex + 6].field())
    val expressive = checkNotNull(code[countIndex + 7].field())
    val tablet = target.calls().single { it.parameterTypes == listOf(CONTEXT) && it.returnType == "Z" }
    check(listOf(Opcode.IGET, Opcode.CONST_4, Opcode.IF_LE, Opcode.IGET, Opcode.IF_EQ,
        Opcode.INT_TO_FLOAT, Opcode.IGET, Opcode.IGET_BOOLEAN) ==
        code.subList(countIndex, countIndex + 8).map { it.opcode })
    check(spanRead.registerB == indexRead.registerB && spanSize.definingClass == spanIndex.definingClass &&
        expressive.definingClass == owner.type && expressiveRead.registerB == countRead.registerB)
    val offsets = owner.bridge("modKitStorageGridOffsets", listOf(owner.type, RECT, VIEW, "I", spanSize.definingClass), "Z", 7, """
        move-object v0, p1
        move-object v1, p2
        move v2, p3
        iget v3, p4, $spanIndex
        iget v4, p4, $spanSize
        iget-boolean v5, p0, $expressive
        const/4 v6, 0x0
        if-nez v5, :offsets
        invoke-virtual {p2}, $VIEW->getContext()$CONTEXT
        move-result-object v6
        invoke-static {v6}, $tablet
        move-result v6
        :offsets
        invoke-static/range {v0 .. v6}, $HOOK->decorate($RECT${VIEW}IIIZZ)Z
        move-result v0
        return v0
        move-exception v0
        invoke-static {v0}, $HOOK->hookError($THROWABLE)V
        const/4 v0, 0x0
        return v0
    """)
    val self = target.implementation!!.registerCount - 5
    val temp = expressiveRead.registerA
    val total = countRead.registerA
    val params = spanRead.registerB
    check(self == countRead.registerB)
    check(setOf(self, self + 1, self + 2, temp, total, params, spacing).size == 7)
    target.checkRegisters(self, self + 1, self + 2, temp, total, params, spacing)
    target.addInstructionsWithLabels(countIndex + 5, """
        const/16 v$temp, $SPAN_COUNT
        if-ne v$total, v$temp, :original
        invoke-static {v$self, v${self + 1}, v${self + 2}, v$spacing, v$params}, $offsets
        move-result v$temp
        if-eqz v$temp, :original
        return-void
        :original
        nop
    """.trimIndent())
}

private fun MutableClass.bridge(
    name: String, types: List<String>, result: String, locals: Int, smali: String, public: Boolean = false,
): MutableMethod {
    val visibility = if (public) AccessFlags.PUBLIC else AccessFlags.PRIVATE
    val registers = locals + types.size
    check(registers <= 16)
    val body = MutableMethodImplementation(registers)
    val method = MutableMethod(ImmutableMethod(
        type, name, types.map { ImmutableMethodParameter(it, emptySet(), null) }, result,
        visibility.value or AccessFlags.STATIC.value, emptySet(), emptySet(), body,
    ))
    method.addInstructionsWithLabels(0, smali.trimIndent())
    val handler = method.code().indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
    check(handler > 0)
    method.implementation!!.apply {
        addCatch(THROWABLE, newLabelForIndex(0), newLabelForIndex(handler), newLabelForIndex(handler))
    }
    methods.add(method)
    return method
}

private fun MutableClass.mutable(method: Method) = methods.single { it.toString() == method.toString() }
private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
private fun Instruction.field() = ref() as? FieldReference
private fun Instruction.literal() = (this as? WideLiteralInstruction)?.wideLiteral
private fun Method.calls() = code().mapNotNull { it.ref() as? MethodReference }
private fun Method.string(value: String) = code().any { (it.ref() as? StringReference)?.string == value }
private fun Method.resource(name: String) = code().any { it.literal() == browserResourceId(name) }

private fun Method.checkRegisters(vararg registers: Int) {
    val count = checkNotNull(implementation).registerCount
    check(registers.all { it in 0 until minOf(16, count) }) { "Unsafe grid-column registers in $this" }
}

private fun Iterable<Method>.fingerprint(label: String, predicate: (Method) -> Boolean): Method {
    val matches = filter(predicate)
    check(matches.size == 1) { "Grid columns fingerprint $label: expected 1 match, got ${matches.size}" }
    return matches.single().also { println("Grid columns fingerprint $label: 1 match ($it)") }
}

private fun BytecodePatchContext.gridFingerprint(label: String, predicate: (Method) -> Boolean): Method {
    val hits = mutableListOf<Method>()
    classDefForEach { it.methods.filterTo(hits, predicate) }
    return hits.fingerprint(label) { true }
}
