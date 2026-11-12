package app.filesplus.patches.seek

import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.w3c.dom.Element

private const val HOOKS = "${Constants.EXT}seek/SkipHooks;"
private const val CONTROLS = "Lcom/google/android/apps/nbu/files/documentbrowser/filepreview/video/VideoControlView;"

private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun Method.hasString(value: String) = code().any {
    (it.reference() as? StringReference)?.string == value
}

private val skipStringsPatch = resourcePatch {
    execute {
        for ((directory, strings) in mapOf(
            "values" to mapOf(
                "quick_seek_discovery" to "Double-tap left or right to skip backward or forward",
                "media_controls_rewind_10_description" to "Rewind",
                "media_controls_fastforward_10_description" to "Fast forward",
            ),
            "values-ja" to mapOf(
                "quick_seek_discovery" to "左右をダブルタップして巻き戻し・早送り",
                "media_controls_rewind_10_description" to "巻き戻し",
                "media_controls_fastforward_10_description" to "早送り",
            ),
        )) {
            val resourcePath = "res/$directory/strings.xml"
            // Language splits may be absent from a base APK; add Japanese overrides in that case.
            val file = get(resourcePath)
            if (!file.exists() && directory == "values-ja") {
                file.parentFile.mkdirs()
                file.writeText("<resources/>")
            }
            document(resourcePath).use { doc ->
                val nodes = doc.getElementsByTagName("string")
                val found = mutableSetOf<String>()
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as Element
                    val name = node.getAttribute("name")
                    strings[name]?.let {
                        node.textContent = it
                        found += name
                    }
                }
                for (name in strings.keys - found) {
                    check(directory == "values-ja") { "Missing skip string in $directory: $name" }
                    val node = doc.createElement("string")
                    node.setAttribute("name", name)
                    node.textContent = strings.getValue(name)
                    doc.documentElement.appendChild(node)
                }
            }
        }
    }
}

val customSkipDurationPatch = bytecodePatch(
    name = "Custom skip duration",
    description = "Customize double-tap and button skip durations; long-press a skip button to change its duration. Optionally, a double-tap rewind near the start goes to the start.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch, skipStringsPatch)

    execute {
        // Both animation labels and the MotionEvent signature identify the quick seek peer.
        val quickClass = classDefBy {
            it.methods.any { m -> m.hasString("rewindSwipeTrianglesAnimation") &&
                m.hasString("fastForwardSwipeTrianglesAnimation") }
        }
        val quick = mutableClassDefBy(quickClass).methods.single {
            it.hasString("rewindSwipeTrianglesAnimation") &&
                it.hasString("fastForwardSwipeTrianglesAnimation") &&
                it.parameterTypes == listOf("Landroid/view/MotionEvent;") && it.returnType == "V"
        }
        val calls = quick.code().withIndex().filter { (_, insn) ->
            val ref = insn.reference() as? MethodReference
            insn.opcode == Opcode.INVOKE_STATIC && ref?.parameterTypes == listOf("J") &&
                ref.returnType == ref.definingClass && quickClass.fields.any { it.type == ref.returnType }
        }
        check(calls.size == 4) { "Expected four quick seek duration factories, got ${calls.size}" }
        val factory = calls.first().value.reference() as MethodReference
        check(calls.all { it.value.reference() == factory }) { "Mixed quick seek factories" }
        // Insert at each use: a compiler may share one const-wide among several calls.
        for ((index, insn) in calls.reversed()) {
            val reg = (insn as FiveRegisterInstruction).registerC
            quick.addInstructions(index, """
                invoke-static {}, $HOOKS->doubleTapSeconds()J
                move-result-wide v$reg
            """.trimIndent())
        }

        // Stable playback diagnostic plus the unobfuscated view return type anchors the controller.
        val controller = mutableClassDefBy(classDefBy {
            it.methods.any { m -> m.hasString("Requested orientation is unknown.") } &&
                it.methods.any { m -> m.returnType == CONTROLS }
        })
        val initializer = controller.methods.single { it.name == "<clinit>" }.code()
        val fields = mutableSetOf<String>()
        val constants = mutableMapOf<Int, Long>()
        for ((index, insn) in initializer.withIndex()) {
            if (insn is WideLiteralInstruction && insn is OneRegisterInstruction &&
                insn.opcode in setOf(Opcode.CONST_WIDE, Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE_HIGH16)) {
                constants[insn.registerA] = insn.wideLiteral
            }
            if (insn.opcode != Opcode.INVOKE_STATIC || insn.reference() != factory) continue
            val reg = (insn as FiveRegisterInstruction).registerC
            if (constants[reg] != 10L) continue
            val result = initializer.getOrNull(index + 1)
            val store = initializer.getOrNull(index + 2)
            val field = store?.reference() as? FieldReference
            check(result?.opcode == Opcode.MOVE_RESULT_OBJECT && store?.opcode == Opcode.SPUT_OBJECT &&
                field?.definingClass == controller.type && field.type == factory.returnType &&
                (result as OneRegisterInstruction).registerA == (store as OneRegisterInstruction).registerA)
            fields += field.toString()
        }
        check(fields.size == 2) { "Expected two ten-second button fields, got ${fields.size}" }

        val helperName = "modKitButtonSkipDuration"
        check(controller.methods.none { it.name == helperName })
        val helper = MutableMethod(ImmutableMethod(
            controller.type, helperName, emptyList(), factory.returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(2, emptyList(), emptyList(), emptyList()),
        ))
        helper.addInstructions(0, """
            invoke-static {}, $HOOKS->buttonSeconds()J
            move-result-wide v0
            invoke-static {v0, v1}, $factory
            move-result-object v0
            return-object v0
        """.trimIndent())
        controller.methods.add(helper)
        val readers = mutableListOf<Pair<Method, List<Int>>>()
        classDefForEach { cls ->
            for (method in cls.methods) {
                val indices = method.code().withIndex().filter { (_, insn) ->
                    insn.opcode == Opcode.SGET_OBJECT && insn.reference().toString() in fields
                }.map { it.index }
                if (indices.isNotEmpty()) readers += method to indices
            }
        }
        check(readers.sumOf { it.second.size } == 2) { "Expected two button skip reads" }
        for ((source, indices) in readers) {
            val method = mutableClassDefBy(source.definingClass).methods.single {
                it.name == source.name && it.parameterTypes == source.parameterTypes && it.returnType == source.returnType
            }
            for (index in indices.reversed()) {
                val reg = (method.code()[index] as OneRegisterInstruction).registerA
                method.replaceInstruction(index, "invoke-static {}, ${controller.type}->$helperName()${factory.returnType}")
                method.addInstructions(index + 1, "move-result-object v$reg")
            }
        }
        val inflate = mutableClassDefBy(CONTROLS).methods.single {
            it.name == "onFinishInflate" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
        val returns = inflate.code().withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
        check(returns.isNotEmpty())
        for ((index) in returns.reversed()) {
            inflate.addInstructions(index, "invoke-static/range {p0 .. p0}, $HOOKS->onFinishInflate(Landroid/view/View;)V")
        }

        hookRewindToStart(factory.returnType)
    }
}

/**
 * Double-tap rewind is ignored when it would end at or before the start (the buttons already
 * clamp to 0). Shrink the rewind amount via [HOOKS]`.rewindSkip` in both quick seek callbacks:
 * the video one (duration objects, result must be positive) and the audio one (millisecond longs).
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.hookRewindToStart(duration: String) {
    val owner = mutableClassDefBy(classDefBy { cls ->
        cls.methods.any { it.hasString("rewind is called when videoPlayer is null.") }
    })
    val rewind = owner.methods.single { it.hasString("rewind is called when videoPlayer is null.") }
    check(rewind.parameterTypes == listOf(duration) && rewind.returnType == "Z") { "Unexpected rewind callback" }

    val durationClass = mutableClassDefBy(duration)
    val nanos = durationClass.fields.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "J" }
    check(durationClass.methods.any { it.name == "<init>" && it.parameterTypes == listOf("J") })

    // Audio: `position - skip >= 0` on milliseconds. Rewrite the skip operand before the subtraction.
    val subs = rewind.code().withIndex().filter {
        it.value.opcode == Opcode.SUB_LONG || it.value.opcode == Opcode.SUB_LONG_2ADDR
    }
    check(subs.size == 1) { "Expected one audio rewind subtraction, got ${subs.size}" }
    val (subIndex, sub) = subs.single()
    val (position, skip) = if (sub is ThreeRegisterInstruction) sub.registerB to sub.registerC
        else (sub as TwoRegisterInstruction).registerA to sub.registerB
    check(maxOf(position, skip) + 1 <= 15) { "Audio rewind registers out of range" }
    rewind.addInstructions(subIndex, """
        invoke-static {v$position, v${position + 1}, v$skip, v${skip + 1}}, $HOOKS->rewindSkip(JJ)J
        move-result-wide v$skip
    """.trimIndent())

    // Video: `position.minus(skip)` must stay positive. Replace skip before the call.
    val minus = rewind.code().withIndex().filter { (_, insn) ->
        val ref = insn.reference() as? MethodReference
        insn.opcode == Opcode.INVOKE_VIRTUAL && ref?.definingClass == duration &&
            ref.parameterTypes == listOf(duration) && ref.returnType == duration
    }
    check(minus.size == 1) { "Expected one video rewind subtraction, got ${minus.size}" }
    val (minusIndex, call) = minus.single()
    call as FiveRegisterInstruction
    check(call.registerCount == 2)

    val helperName = "modKitRewindSkip"
    check(owner.methods.none { it.name == helperName })
    val helper = MutableMethod(ImmutableMethod(
        owner.type, helperName, listOf(
            com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(duration, null, null),
            com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(duration, null, null),
        ), duration, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(6, emptyList(), emptyList(), emptyList()),
    ))
    helper.addInstructions(0, """
        iget-wide v0, p0, $nanos
        iget-wide v2, p1, $nanos
        invoke-static {v0, v1, v2, v3}, $HOOKS->rewindSkip(JJ)J
        move-result-wide v0
        new-instance v2, $duration
        invoke-direct {v2, v0, v1}, $duration-><init>(J)V
        return-object v2
    """.trimIndent())
    owner.methods.add(helper)
    rewind.addInstructions(minusIndex, """
        invoke-static {v${call.registerC}, v${call.registerD}}, ${owner.type}->$helperName($duration$duration)$duration
        move-result-object v${call.registerD}
    """.trimIndent())
}
