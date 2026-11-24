package app.filesplus.patches.thumbs

import app.filesplus.patches.browse.browserResourceId
import app.filesplus.patches.browse.browserResources
import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction12x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val HOOK = "${Constants.EXT}thumbs/VideoThumbHooks;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val URI = "Landroid/net/Uri;"
private const val IMAGE_VIEW = "Landroid/widget/ImageView;"
private const val THROWABLE = "Ljava/lang/Throwable;"

val videoThumbnailCachePatch = bytecodePatch(
    name = "Video thumbnail cache",
    description = "Keep thumbnails for local videos across cache cleanups.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch, browserResources)

    execute {
        val thumbnailData = unique("ThumbnailData") {
            it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == STRING &&
                it.string("ThumbnailData(uri=") && it.string(", imageSignature=") &&
                it.string(", downsampleStrategy=")
        }.definingClass
        val compose = unique("Compose list and grid request") {
            it.parameterTypes == listOf(OBJECT) && it.returnType == OBJECT &&
                it.code().any { insn -> insn.opcode == Opcode.CHECK_CAST && insn.ref().toString() == thumbnailData } &&
                it.calls().any { call -> call.definingClass == "Ljava/lang/StringBuilder;" &&
                    call.name == "append" && call.parameterTypes == listOf("J") }
        }
        val objectKey = unique("Glide ObjectKey") {
            it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == STRING &&
                it.string("ObjectKey{object=")
        }.definingClass
        val graph = unique("Glide request and error graph") {
            it.parameterTypes.lastOrNull() == "Ljava/util/concurrent/Executor;" &&
                it.parameterTypes.size == 10 && it.string(
                    "You cannot use a request as both the main request and a thumbnail, consider using clone() on the request(s) passed to thumbnail()",
                )
        }
        val builder = graph.definingClass
        val errorRequest = verifiedErrorRequest(graph)
        val builderClass = mutableClassDefBy(builder)
        val clone = builderClass.methods.one("Glide builder clone") {
            it.parameterTypes.isEmpty() && it.returnType == builder &&
                it.code().count { instruction -> instruction.ref() == errorRequest } >= 2
        }
        val load = builderClass.methods.one("Glide model setter") {
            it.parameterTypes == listOf(OBJECT) && it.returnType == builder && it.code().any { insn ->
                insn.opcode == Opcode.IPUT_OBJECT &&
                    (insn.ref() as? FieldReference)?.let { ref -> ref.definingClass == builder && ref.type == OBJECT } == true
            }
        }
        val model = load.code().filter { it.opcode == Opcode.IPUT_OBJECT }.mapNotNull { it.ref() as? FieldReference }
            .single { it.definingClass == builder && it.type == OBJECT }
        val into = builderClass.methods.one("Glide ImageView submit") {
            it.parameterTypes == listOf(IMAGE_VIEW) && it.returnType == "V"
        }
        val keyType = classDefBy(objectKey).interfaces.single()
        val options = classDefBy(builder).superclass!!
        val signatureSetter = classDefBy(options).methods.one("Glide signature setter") {
            it.parameterTypes == listOf(keyType) && it.returnType == options
        }
        val signature = signatureSetter.code().filter { it.opcode == Opcode.IPUT_OBJECT }
            .mapNotNull { it.ref() as? FieldReference }.single { it.type == keyType }
        check(AccessFlags.PUBLIC.isSet(classDefBy(options).fields.single { it == signature }.accessFlags))

        val row = unique("native list request") {
            it.returnType == "V" && it.parameterTypes.size == 1 && it.calls().any { call -> call == into } &&
                it.resource("dimen/advanced_browsing_list_item_image_size") &&
                it.calls().any { call -> call.definingClass == objectKey }
        }
        val tile = unique("native grid request") {
            it.returnType == "V" && it.parameterTypes.size == 1 && it.calls().any { call -> call == into } &&
                it.resource("layout/square_item_loading_spinner") &&
                it.calls().any { call -> call.definingClass == objectKey }
        }
        val failure = unique("Glide single request failure") {
            it.returnType == "V" && it.parameterTypes.size == 1 &&
                it.string("Load failed for [") && it.string("] with dimensions [")
        }
        val failureCode = failure.code()
        val lockIndex = failureCode.indexOfFirst { it.opcode == Opcode.MONITOR_ENTER }
        check(lockIndex > 0 && failureCode[lockIndex - 1].opcode == Opcode.IGET_OBJECT)
        val lock = failureCode[lockIndex - 1].ref() as FieldReference
        val requestModel = failureCode.filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { it.ref() as? FieldReference }.distinct()
            .single { it.definingClass == failure.definingClass && it.type == OBJECT && it != lock }

        val thumbnailUri = unique("native thumbnail URI and MIME resolver") {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.size == 1 &&
                it.string("glide-filesgo-model-app-scheme") && it.string("glide-filesgo-model-apk-scheme") &&
                it.string("glide-filesgo-model-pdf-scheme")
        }
        rememberMediaMime(thumbnailUri)

        val replace = builderClass.bridge("modKitVideoThumb", listOf(builder), builder, 4)
        replace.addInstructionsWithLabels(0, """
            iget-object v0, p0, $model
            iget-object v1, p0, $signature
            invoke-static {v0, v1}, $HOOK->replacement($OBJECT$OBJECT)$URI
            move-result-object v0
            if-eqz v0, :original
            invoke-virtual {p0}, $clone
            move-result-object v1
            invoke-virtual {v1, v0}, $load
            move-result-object v1
            iput-object p0, v1, $errorRequest
            return-object v1
            :original
            return-object p0
        """.trimIndent())
        replace.catchHookFailure("return-object p0")

        val submit = builderClass.bridge("modKitSubmitVideoThumb", listOf(builder, IMAGE_VIEW), "V", 2)
        submit.addInstructions(0, """
            invoke-static {p0}, $replace
            move-result-object p0
            invoke-virtual {p0, p1}, $into
            return-void
        """.trimIndent())

        // The Compose callback returns an already configured Glide builder. Replace each return instruction
        // in place so jumps to a shared return also run the hook. Keep the original register and type.
        val callback = mutableMethod(compose)
        val returns = callback.code().withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        check(returns.isNotEmpty())
        for ((index, instruction) in returns.asReversed()) {
            val register = (instruction as OneRegisterInstruction).registerA
            check(register < callback.implementation!!.registerCount)
            callback.implementation!!.replaceInstruction(index, BuilderInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE, register, 1, replace,
            ))
            callback.addInstructions(index + 1, "move-result-object v$register\nreturn-object v$register")
        }

        for (original in listOf(row, tile)) {
            val binder = mutableMethod(original)
            val (index, invoke) = binder.code().withIndex().single { it.value.ref() == into }
            val replacement = when (invoke) {
                is FiveRegisterInstruction -> {
                    check(invoke.registerCount == 2)
                    BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, invoke.registerC, invoke.registerD, 0, 0, 0, submit)
                }
                is RegisterRangeInstruction -> {
                    check(invoke.registerCount == 2)
                    BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, invoke.startRegister, 2, submit)
                }
                else -> error("Unexpected Glide submit instruction: ${invoke.opcode}")
            }
            binder.implementation!!.replaceInstruction(index, replacement)
        }

        val failedRequest = mutableMethod(failure)
        check(failedRequest.implementation!!.registerCount >= 3)
        failedRequest.addInstructions(0, """
            move-object/from16 v0, p0
            iget-object v0, v0, $requestModel
            invoke-static {v0}, $HOOK->loadFailed($OBJECT)V
        """.trimIndent())

        val init = mutableClassDefBy("${Constants.EXT}ModKit;").methods.one("extension context initialization") {
            it.name == "init" && it.parameterTypes == listOf("Landroid/content/Context;") && it.returnType == "V"
        }
        val (contextWrite, _) = init.code().withIndex().single { (_, instruction) ->
            instruction.opcode == Opcode.SPUT_OBJECT && (instruction.ref() as? FieldReference)?.type ==
                "Landroid/content/Context;"
        }
        init.addInstructions(contextWrite + 1, "invoke-static {}, $HOOK->warmUp()V")

        mutableClassDefBy(HOOK).methods.one("extension installation status") {
            it.name == "installed" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
    }
}

// The first null branch must skip the error coordinator allocation and reach the separate
// thumbnail field check, whose recursion guard emits Glide's thumbnail-specific exception.
private fun BytecodePatchContext.verifiedErrorRequest(graph: Method): FieldReference {
    val code = graph.code()
    val builder = graph.definingClass
    val read = code.indexOfFirst {
        val field = it.ref() as? FieldReference
        it.opcode == Opcode.IGET_OBJECT && field?.definingClass == builder && field.type == builder
    }
    check(read >= 0) { "Glide request graph has no request field" }
    val field = code[read].ref() as FieldReference
    val nullGuard = code[read + 1]
    check(nullGuard.opcode == Opcode.IF_EQZ &&
        (nullGuard as OneRegisterInstruction).registerA == (code[read] as TwoRegisterInstruction).registerA)
    val skipAllocation = code.branchTarget(read + 1)
    val allocation = code[read + 2]
    check(allocation.opcode == Opcode.NEW_INSTANCE && skipAllocation > read + 3 &&
        code[skipAllocation - 1].opcode == Opcode.GOTO)
    val thumbnail = code.branchTarget(skipAllocation - 1)
    check(thumbnail > skipAllocation &&
        code.subList(skipAllocation, thumbnail).none { it.opcode == Opcode.NEW_INSTANCE })
    val coordinator = allocation.ref().toString()
    val constructor = code.subList(read + 3, skipAllocation).single { instruction ->
        val call = instruction.ref() as? MethodReference
        instruction.opcode == Opcode.INVOKE_DIRECT && call?.definingClass == coordinator && call.name == "<init>"
    }
    val coordinatorType = graph.parameterTypes[3].toString()
    check((constructor.ref() as MethodReference).parameterTypes == listOf(OBJECT, coordinatorType) &&
        (constructor as FiveRegisterInstruction).registerC == (allocation as OneRegisterInstruction).registerA &&
        classDefBy(coordinator).interfaces.containsAll(listOf(graph.returnType, coordinatorType)))
    val thumbField = code[thumbnail].ref() as? FieldReference
    check(code[thumbnail].opcode == Opcode.IGET_OBJECT && thumbField?.definingClass == builder &&
        thumbField.type == builder && thumbField != field &&
        code[thumbnail + 1].opcode == Opcode.IF_EQZ && code[thumbnail + 2].opcode == Opcode.IGET_BOOLEAN &&
        code[thumbnail + 3].opcode == Opcode.IF_NEZ)
    val recursionError = code.branchTarget(thumbnail + 3)
    check(code.drop(recursionError).take(4).any {
        (it.ref() as? StringReference)?.string?.startsWith("You cannot use a request as both") == true
    }) { "Glide thumbnail recursion guard no longer follows the error coordinator" }
    return field
}

private fun BytecodePatchContext.rememberMediaMime(resolver: Method) {
    val target = mutableMethod(resolver)
    val code = target.code()
    val parseSites = code.withIndex().filter { (_, instruction) ->
        val call = instruction.ref() as? MethodReference
        instruction.opcode == Opcode.INVOKE_STATIC && call?.definingClass == URI &&
            call.name == "parse" && call.parameterTypes == listOf(STRING) && call.returnType == URI
    }
    check(parseSites.size == 2)
    val entry = parseSites.first().index - 4
    check(entry > 0 && code.drop(entry).map { it.opcode } == listOf(
        Opcode.IGET, Opcode.AND_INT_2ADDR, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT, Opcode.GOTO, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT,
    ) && parseSites.last().index == entry + 8)
    check(code.branchTarget(entry + 2) == entry + 7 && code.branchTarget(entry + 6) == entry + 10)
    val incoming = code.indices.filter {
        code[it] is OffsetInstruction && code.branchTarget(it) == entry
    }
    check(incoming.size == 3 && incoming.all { code[it].opcode == Opcode.IF_NEZ })
    // The first classifier also initializes the media URI flag mask before branching.
    val classifiers = incoming.associateWith { branch ->
        val call = branch - if (code[branch - 1].opcode == Opcode.CONST_HIGH16) 3 else 2
        val predicate = code[call].ref() as? MethodReference
        check(code[call].opcode == Opcode.INVOKE_STATIC && code[call + 1].opcode == Opcode.MOVE_RESULT &&
            predicate?.parameterTypes == listOf(STRING) && predicate.returnType == "Z")
        call
    }
    val mime = (code[classifiers.getValue(incoming.first())] as FiveRegisterInstruction).registerC
    val scratch = (code[incoming.first()] as OneRegisterInstruction).registerA
    check(classifiers.all { (branch, call) ->
        (code[call] as FiveRegisterInstruction).registerC == mime &&
            (code[call + 1] as OneRegisterInstruction).registerA == scratch &&
            (code[branch] as OneRegisterInstruction).registerA == scratch &&
            code.subList(call + 2, branch).none { mime in it.registers() || scratch in it.registers() }
    } && scratch != mime && scratch < 16 && mime < 16 &&
        scratch < target.implementation!!.registerCount && mime < target.implementation!!.registerCount &&
        code.drop(entry).none { scratch in it.registers() })
    val bridge = mutableClassDefBy(resolver.definingClass)
        .bridge("modKitParseThumbnailUri", listOf(STRING, STRING), URI, 3)
    val parse = parseSites.first().value.ref()
    bridge.addInstructions(0, """
        invoke-static {p0}, $parse
        move-result-object v0
        invoke-static {v0, p1}, $HOOK->rememberType($URI$STRING)V
        return-object v0
    """.trimIndent())

    // The original MIME is live at every incoming classifier branch. Preserve it in the
    // checked-dead classifier-result register before the media path reuses its register.
    val implementation = target.implementation!!
    for ((index, instruction) in parseSites) {
        val invoke = instruction as FiveRegisterInstruction
        check(invoke.registerCount == 1)
        implementation.replaceInstruction(index, BuilderInstruction35c(
            Opcode.INVOKE_STATIC, 2, invoke.registerC, scratch, 0, 0, 0, bridge,
        ))
    }
    for ((index, _) in parseSites.asReversed()) {
        // Restore the original nonzero classifier result before rejoining the other URI paths.
        target.addInstructions(index + 2, "const/4 v$scratch, 0x1")
    }
    val original = code[entry] as TwoRegisterInstruction
    implementation.replaceInstruction(entry, BuilderInstruction12x(Opcode.MOVE_OBJECT, scratch, mime))
    target.addInstructions(entry + 1, "iget v${original.registerA}, v${original.registerB}, ${code[entry].ref()}")
}

private fun List<Instruction>.branchTarget(index: Int): Int {
    val address = take(index).sumOf { it.codeUnits } + (this[index] as OffsetInstruction).codeOffset
    var current = 0
    return indexOfFirst { instruction ->
        (current == address).also { current += instruction.codeUnits }
    }.also { check(it >= 0) }
}

private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
    is TwoRegisterInstruction -> listOf(registerA, registerB)
    is OneRegisterInstruction -> listOf(registerA)
    else -> emptyList()
}

private fun MutableClass.bridge(name: String, types: List<String>, result: String, registers: Int): MutableMethod {
    return MutableMethod(ImmutableMethod(type, name, types.map { ImmutableMethodParameter(it, emptySet(), null) },
        result, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
        MutableMethodImplementation(registers))).also { methods.add(it) }
}

private fun MutableMethod.catchHookFailure(fallback: String) {
    val handler = code().size
    addInstructions(handler, """
        move-exception v0
        invoke-static {v0}, $HOOK->hookError($THROWABLE)V
        $fallback
    """.trimIndent())
    implementation!!.apply {
        addCatch(THROWABLE, newLabelForIndex(0), newLabelForIndex(handler), newLabelForIndex(handler))
    }
}

private fun BytecodePatchContext.mutableMethod(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
    }

private fun BytecodePatchContext.unique(label: String, predicate: (Method) -> Boolean): Method {
    val matches = mutableListOf<Method>()
    classDefForEach { it.methods.filterTo(matches, predicate) }
    return matches.one(label) { true }
}

private fun <T> Iterable<T>.one(label: String, predicate: (T) -> Boolean): T {
    val matches = filter(predicate)
    check(matches.size == 1) { "Video thumbs fingerprint $label: expected 1 match, got ${matches.size}" }
    return matches.single().also { println("Video thumbs fingerprint $label: 1 match ($it)") }
}

private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
private fun Method.calls() = code().mapNotNull { it.ref() as? MethodReference }
private fun Method.string(value: String) = code().any { (it.ref() as? StringReference)?.string == value }
private fun Method.resource(name: String) = code().any {
    (it as? WideLiteralInstruction)?.wideLiteral == browserResourceId(name)
}
