package app.moddedfiles.patches.media

import app.moddedfiles.patches.misc.modKitIntegrationPatch
import app.moddedfiles.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val HOOKS = "${Constants.EXT}media/MediaHooks;"
private const val LISTENER = "${Constants.EXT}media/PlaybackListener;"
private const val EXO_PLAYER = "Landroidx/media3/exoplayer/ExoPlayer;"
private const val VIDEO_VIEW =
    "Lcom/google/android/apps/nbu/files/mediaconsumption/videoplayer/VideoPlayerView;"

private val previewInit = fingerprint {
    strings("bufferForPlaybackMs", "maxBufferMs")
    returns("V")
    parameters()
    custom { _, cls -> cls.fields.any { it.type == VIDEO_VIEW } }
}

private val previewError = fingerprint {
    strings("Error occurred when playing the media.", "Trying to disable exceed renderer capabilities.")
    returns("V")
}

private val codecInit = fingerprint {
    strings("Failed to initialize decoder: ", "Format exceeds selected codec's capabilities [%s, %s]")
    returns("V")
    parameters()
}

private val videoCodecError = fingerprint {
    strings("MediaCodecVideoRenderer", "Video codec error")
    returns("V")
    parameters("Ljava/lang/Exception;")
}

private val formatDescription = fingerprint {
    strings("id=", ", mimeType=", ", codecs=", ", res=", ", fps=")
    returns("Ljava/lang/String;")
}

val highResolutionPlaybackPatch = bytecodePatch(
    name = "High-resolution playback",
    description = "Try videos beyond reported decoder limits, enable decoder fallback, and log playback diagnostics.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch)

    execute {
        val init = previewInit.method
        val error = previewError.method
        val errorClass = previewError.classDef
        val listenerInterface = errorClass.interfaces.single()
        val callbackInterface = classDefBy(listenerInterface)
        requireShape(callbackInterface.methods.all { it.returnType == "V" }, "Player.Listener callbacks")

        val errorType = error.parameterTypes.single().toString()
        val errorCode = classDefBy(errorType).fields.single { it.type == "I" }
        val addListener = init.instructions().mapNotNull { it.methodReference() }.first {
            it.parameterTypes.map(CharSequence::toString) == listOf(listenerInterface) &&
                it.returnType == "V" && it.name != "<init>"
        }

        val adapter = mutableClassDefBy(LISTENER)
        adapter.interfaces.add(listenerInterface)
        callbackInterface.methods.forEach { callback ->
            val parameters = callback.parameterTypes.map(CharSequence::toString)
            val isError = callback.name == error.name && parameters == listOf(errorType)
            adapter.addBridge(
                callback.name, parameters, static = false,
                locals = 1,
                catchFailures = isError,
                body = if (isError) """
                    iget v0, p1, $errorCode
                    invoke-static {p1, v0}, $HOOKS->onPlayerError(Ljava/lang/Throwable;I)V
                """ else "",
            )
        }
        adapter.addBridge("attach", listOf("Ljava/lang/Object;"), locals = 1, catchFailures = false, body = """
            check-cast p0, ${addListener.definingClass}
            new-instance v0, $LISTENER
            invoke-direct {v0}, $LISTENER-><init>()V
            invoke-interface {p0, v0}, $addListener
        """)

        val description = formatDescription.originalMethod
        requireShape(AccessFlags.STATIC.isSet(description.accessFlags), "Format description is static")
        val formatType = description.parameterTypes.single().toString()
        adapter.addBridge("format", listOf("Ljava/lang/Object;"), locals = 1, catchFailures = false, body = """
            check-cast p0, $formatType
            invoke-static {p0}, $description
            move-result-object v0
            invoke-static {v0}, $HOOKS->onFormat(Ljava/lang/String;)V
        """)

        // Hook the exact player returned by the builder, before media is prepared or listeners run.
        val buildIndex = init.instructions().indexOfFirst {
            it.methodReference()?.let { ref ->
                ref.returnType == EXO_PLAYER && ref.parameterTypes.isEmpty()
            } == true
        }
        requireShape(buildIndex >= 0, "Preview ExoPlayer builder")
        val result = init.instructions()[buildIndex + 1]
        requireShape(result.opcode == Opcode.MOVE_RESULT_OBJECT, "Created preview player register")
        val playerRegister = (result as OneRegisterInstruction).registerA
        init.addInstructions(buildIndex + 2, """
            invoke-static/range {v$playerRegister .. v$playerRegister}, $HOOKS->attach(Ljava/lang/Object;)V
        """)

        // Files' error wrapper sets exceedRendererCapabilitiesIfNecessary=false and prepares
        // again. Once every decoder has failed, send the error to its original delegate instead.
        // This preserves the selector's default true value without an endless prepare/error loop.
        val delegate = errorClass.instanceFields.single { it.type == listenerInterface }
        val recovery = error.instructions()
        requireShape(error.implementation!!.registerCount - 2 >= 1, "Error handler scratch register")
        error.addInstructionsWithLabels(0, """
            invoke-static/range {p1 .. p1}, $HOOKS->preserveCapabilityAllowance(Ljava/lang/Throwable;)Z
            move-result v0
            if-eqz v0, :original_error
            iget-object v0, p0, $delegate
            invoke-interface {v0, p1}, $listenerInterface->${error.name}($errorType)V
            return-void
        """, ExternalLabel("original_error", recovery.first()))

        val renderer = videoCodecError.classDef
        val supports = renderer.methods.single {
            !AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.returnType == "I" && it.parameterTypes.size == 2 &&
                it.parameterTypes[1].toString() == formatType
        }
        supports.addInstructions(0, """
            invoke-static/range {p2 .. p2}, $HOOKS->format(Ljava/lang/Object;)V
        """)
        supports.instructions().indices.filter {
            supports.instructions()[it].opcode == Opcode.RETURN
        }.reversed().forEach { index ->
            val register = (supports.instructions()[index] as OneRegisterInstruction).registerA
            supports.addInstructions(index, """
                invoke-static/range {v$register .. v$register}, $HOOKS->rendererSupport(I)I
                move-result v$register
            """)
        }

        val configured = renderer.methods.single {
            it.parameterTypes.size == 4 && it.parameterTypes[1].toString() == formatType &&
                it.parameterTypes[2].toString() == "Landroid/media/MediaCrypto;" &&
                it.parameterTypes[3].toString() == "F"
        }
        configured.addInstructions(0, """
            invoke-static/range {p2 .. p2}, $HOOKS->format(Ljava/lang/Object;)V
        """)
        renderer.methods.single {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) ==
                listOf("Ljava/lang/String;", "J", "J")
        }.addInstructions(0, """
            invoke-static/range {p1 .. p1}, $HOOKS->onDecoder(Ljava/lang/String;)V
        """)
        videoCodecError.method.addInstructions(0, """
            invoke-static/range {p1 .. p1}, $HOOKS->onCodecError(Ljava/lang/Throwable;)V
        """)

        // In this R8 build enableDecoderFallback was constant false and was removed. Its
        // ArrayDeque retry loop remains, but only list.get(0) is queued. Populate that same
        // queue with the whole MediaCodecSelector result (including platform software codecs).
        val codec = codecInit.method
        val instructions = codec.instructions()
        val addIndex = instructions.indexOfFirst {
            it.methodReference()?.let { ref ->
                ref.definingClass == "Ljava/util/ArrayDeque;" && ref.name == "add" &&
                    ref.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
            } == true
        }
        requireShape(addIndex >= 0, "Decoder queue insertion")
        val queueWrite = (0 until addIndex).last {
            instructions[it].opcode == Opcode.IPUT_OBJECT &&
                instructions[it].fieldReference()?.type == "Ljava/util/ArrayDeque;"
        }
        val listCheck = (0 until queueWrite).last {
            instructions[it].methodReference()?.let { ref ->
                ref.definingClass == "Ljava/util/List;" && ref.name == "isEmpty"
            } == true
        }
        val queueRegister = (instructions[queueWrite] as TwoRegisterInstruction).registerA
        val listRegister = (instructions[listCheck] as FiveRegisterInstruction).registerC
        requireShape(queueRegister < 16 && listRegister < 16, "Decoder queue hook registers")
        requireShape(instructions[addIndex + 1].opcode != Opcode.MOVE_RESULT, "Unused queue add result")
        codec.addInstructionsWithLabels(queueWrite + 1, """
            invoke-static {v$queueRegister, v$listRegister}, $HOOKS->decoderQueue(Ljava/util/ArrayDeque;Ljava/util/List;)V
            goto/32 :queue_ready
        """, ExternalLabel("queue_ready", instructions[addIndex + 1]))
    }
}

private fun requireShape(condition: Boolean, detail: String) {
    if (!condition) throw PatchException("High-resolution playback: unexpected $detail")
}

private fun MutableMethod.instructions(): List<Instruction> = implementation!!.instructions.toList()

private fun Instruction.methodReference() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference() =
    (this as? ReferenceInstruction)?.reference as? FieldReference

/** Generate typed app-facing bridges after discovering the obfuscated interface and Format. */
private fun MutableClass.addBridge(
    name: String,
    parameters: List<String>,
    static: Boolean = true,
    locals: Int,
    catchFailures: Boolean = true,
    body: String,
) {
    methods.removeAll { it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters }
    val words = parameters.sumOf { if (it == "J" || it == "D") 2 else 1 } + if (static) 0 else 1
    val method = MutableMethod(ImmutableMethod(
        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
        AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0,
        null, null, ImmutableMethodImplementation(locals + words, emptyList(), emptyList(), emptyList()),
    ))
    method.addInstructions(0, "$body\nreturn-void")
    if (catchFailures) {
        val handlerIndex = method.instructions().size
        method.addInstructions(handlerIndex, """
            move-exception v0
            invoke-static {v0}, $HOOKS->bridgeError(Ljava/lang/Throwable;)V
            return-void
        """)
        method.implementation!!.apply {
            addCatch("Ljava/lang/Throwable;", newLabelForIndex(0),
                newLabelForIndex(handlerIndex), newLabelForIndex(handlerIndex))
        }
    }
    methods.add(method)
}
