package app.filesplus.patches.foldertabs

import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val HOOK = "${Constants.EXT}foldertabs/FolderTabContext;"
private const val STRING = "Ljava/lang/String;"
private const val BUNDLE = "Landroid/os/Bundle;"
private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
private fun Method.hasString(text: String) = code().any { (it.ref() as? StringReference)?.string == text }
private fun Method.calls() = code().mapNotNull { it.ref() as? MethodReference }
private fun Method.fields() = code().mapNotNull { it.ref() as? FieldReference }

val showEmptyFolderTabsPatch = bytecodePatch(
    name = "Show empty folder tabs",
    description = "Remember category folders and optionally append tabs for known folders with no matching files.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch, folderTabContextPatch)

    execute {
        val collections = classDefBy { it.methods.any { method ->
            method.hasString("SubFileCollection requested for invalid fileCategory: ")
        } }
        val categoryType = collections.methods.single {
            it.hasString("SubFileCollection requested for invalid fileCategory: ")
        }.parameterTypes.first().toString()
        // This Callable runs on the collection service executor, before the UI list subscriber.
        val callback = mutableClassDefBy(classDefBy { it.methods.any { method ->
            method.hasString("Failed to fetch all and sub file collections.")
        } })
        val build = callback.methods.single { it.hasString("Failed to fetch all and sub file collections.") }
        val code = build.code()
        val diagnostic = code.indexOfFirst {
            (it.ref() as? StringReference)?.string == "Failed to fetch all and sub file collections."
        }
        val finish = (diagnostic + 1 until code.size).first { code[it].opcode == Opcode.RETURN_OBJECT }
        check(code[finish - 1].opcode == Opcode.MOVE_RESULT_OBJECT)
        val freeze = code[finish - 2].ref() as MethodReference
        check(freeze.parameterTypes.isEmpty() && code[finish - 2].opcode == Opcode.INVOKE_VIRTUAL)
        val immutableList = freeze.returnType
        val copyList = build.calls().filter {
            it.parameterTypes == listOf("Ljava/util/Collection;") && it.returnType == immutableList
        }.distinctBy { it.toString() }.single()
        val categoryCast = code.indexOfFirst { it.opcode == Opcode.CHECK_CAST &&
            (it.ref() as? TypeReference)?.type == categoryType }
        check(categoryCast >= 0)
        val categoryRegister = (code[categoryCast] as OneRegisterInstruction).registerA
        val categoryField = code.take(categoryCast).last {
            it.opcode == Opcode.IGET_OBJECT && (it as TwoRegisterInstruction).registerA == categoryRegister
        }.ref() as FieldReference
        check(categoryField.definingClass == callback.type)
        val result = (code[finish] as OneRegisterInstruction).registerA
        check(result in 1..15) { "No safe low register for folder-list return hook" }

        // Reuse the shared filter bridge. No extension code sees an app class or protobuf.
        val filters = classDefBy { it.methods.any { method -> method.name == "modKitFolderFilters" } }
        val collect = filters.methods.single { it.name == "modKitFolderFilters" }
        val decodeFilters = filters.methods.single { it.hasString("Invalid serialized filters: ") }
        val decoderOwner = classDefBy { it.methods.any { method -> method.hasString("Unrecognized collection identifier.") } }
        val decodeCollection = decoderOwner.methods.single { it.hasString("Unrecognized collection identifier.") }
        val decodeId = decodeCollection.calls().first { it.parameterTypes == listOf(STRING) }
        val idType = decodeId.returnType
        val serialized = decodeCollection.fields().first { it.definingClass == idType && it.type == STRING }
        val sourceKind = decoderOwner.methods.single { it.hasString("Unknown identifier: ") }.fields()
            .first { it.definingClass == idType && it.type == "I" }
        val tabKey = collections.methods.flatMap { it.fields() }.filter {
            it.type == STRING && it.definingClass != collections.type &&
                classDefByOrNull(it.definingClass)?.fields?.count { field -> field.type == STRING } == 2
        }.first { field -> collections.methods.any { method ->
            field.definingClass in method.parameterTypes.map(CharSequence::toString)
        } }
        val tab = mutableClassDefBy(tabKey.definingClass)
        val label = tab.fields.single { it.type == STRING && it.toString() != tabKey.toString() }
        val allFactory = classDefBy { cls -> cls.methods.any { method -> method.code().any {
            (it as? WideLiteralInstruction)?.wideLiteral == 0x7f130026L
        } && method.fields().any { it.definingClass == tab.type && it.type == "Z" } } }
            .methods.single { method -> method.code().any { (it as? WideLiteralInstruction)?.wideLiteral == 0x7f130026L } }
        val all = allFactory.code().first { it.opcode == Opcode.IPUT_BOOLEAN &&
            (it.ref() as? FieldReference)?.definingClass == tab.type }.ref() as FieldReference
        val flags = allFactory.code().first { it.opcode == Opcode.IPUT &&
            (it.ref() as? FieldReference)?.definingClass == tab.type }.ref() as FieldReference

        val filter = classDefBy { it.methods.any { method -> method.hasString("Null onField") && method.hasString("Null operator") } }
        val filterCtor = filter.methods.single { it.name == "<init>" && it.parameterTypes.size == 4 }
        val makeFilter = filter.methods.single { AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.returnType == filter.type && it.parameterTypes == filterCtor.parameterTypes.take(3) }
        val makeGroup = filters.methods.single { AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes == listOf(filter.type) && it.returnType == filters.type }
        val parentType = classDefBy { it.methods.any { method -> method.hasString("PARENT_PATH") &&
            method.parameterTypes.isEmpty() && method.returnType == STRING } }.type
        val equalsType = classDefBy { it.methods.any { method -> method.hasString("EQUALS") &&
            method.parameterTypes.isEmpty() && method.returnType == STRING } }.type
        val parent = filters.methods.flatMap { it.fields() }.filter { it.type == parentType }
            .distinctBy { it.toString() }.single()
        val equals = filters.methods.flatMap { it.fields() }.filter { it.type == equalsType }
            .distinctBy { it.toString() }.single()
        val serializeFilters = filters.methods.single { it.parameterTypes.isEmpty() && it.returnType == STRING }
        val encode = serializeFilters.calls().single { it.parameterTypes == listOf("[B") && it.returnType == STRING }
        val encoding = serializeFilters.fields().single { it.type == encode.definingClass }
        // R8 references the inherited protobuf byte serializer through its common base class.
        val bytes = serializeFilters.calls().single { it.parameterTypes.isEmpty() && it.returnType == "[B" }

        // Preserve the host's exact category SQL, including its separate Downloads implementation.
        val downloads = mutableClassDefBy(classDefBy { cls -> cls.methods.any {
            it.name == "<init>" && it.hasString("rootDownloadsPaths is empty!")
        } })
        val mediaQuery = mutableClassDefBy(classDefBy { cls -> cls.superclass == downloads.superclass &&
            cls.methods.any { it.name == "<init>" && it.parameterTypes.size == 3 &&
                it.parameterTypes[1].toString() == filters.type && it.parameterTypes[2].toString() == "I" } &&
            cls.methods.any { it.name == "<init>" && it.parameterTypes.lastOrNull().toString() == BUNDLE }
        })
        for ((owner, type) in listOf(mediaQuery to "I", downloads to filters.type)) {
            val constructor = owner.methods.single { it.name == "<init>" &&
                it.parameterTypes.last().toString() == type }
            val sql = owner.fields.single { it.type == STRING }
            // R8 reuses the constructor's parameter registers, so the arguments are captured right after
            // super() and the MediaStore call is made at every return from this-only state.
            val filtersField = "${owner.type}->modKitFilters:$STRING"
            val typeField = "${owner.type}->modKitType:I"
            check(owner.fields.none { it.name == "modKitFilters" || it.name == "modKitType" })
            owner.fields.add(MutableField(ImmutableField(owner.type, "modKitFilters", STRING,
                AccessFlags.PRIVATE.value, null, emptySet(), emptySet())))
            owner.fields.add(MutableField(ImmutableField(owner.type, "modKitType", "I",
                AccessFlags.PRIVATE.value, null, emptySet(), emptySet())))
            val typeValue = if (owner == downloads) "const/16 v1, -0x64" else "move v1, p3"
            val capture = owner.addEmptyTabsBridge("modKitCaptureFolderQuery",
                constructor.parameterTypes.map(CharSequence::toString), "V",
                constructor.parameterTypes.size + 4, fallback = "return-void", body = """
                    invoke-virtual {p2}, $serializeFilters
                    move-result-object v0
                    iput-object v0, p0, $filtersField
                    $typeValue
                    iput v1, p0, $typeField
                    return-void
                """.trimIndent())
            val remember = owner.addEmptyTabsBridge("modKitRememberFolderQuery", emptyList(), "V", 4,
                fallback = "return-void", body = """
                    iget-object v0, p0, $filtersField
                    iget v1, p0, $typeField
                    iget-object v2, p0, $sql
                    invoke-static {v0, v1, v2}, $HOOK->rememberQuery(${STRING}I$STRING)V
                    return-void
                """.trimIndent())
            val lastParameter = constructor.parameterTypes.size
            val superInit = constructor.code().indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_DIRECT && (instruction.ref() as? MethodReference)?.let {
                    it.name == "<init>" && it.definingClass == owner.superclass
                } == true
            }
            check(superInit >= 0) { "No super constructor call in ${owner.type}" }
            // Returns can be branch targets; replacing keeps their labels on the hook, then the return follows.
            for ((index) in constructor.code().withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.reversed()) {
                constructor.replaceInstruction(index, "invoke-virtual {p0}, $remember")
                constructor.addInstruction(index + 1, "return-void")
            }
            constructor.addInstructions(superInit + 1, "invoke-virtual/range {p0 .. p$lastParameter}, $capture")
        }

        val synthetic = tab.addEmptyTabsBridge("modKitEmptyFolderTab", listOf(STRING, STRING), tab.type, 7,
            static = true, fallback = "const/4 v0, 0x0\nreturn-object v0", body = """
                invoke-static {p0}, $decodeId
                move-result-object v0
                iget v1, v0, $sourceKind
                const/4 v2, 0x1
                if-ne v1, v2, :unsupported
                sget-object v1, $parent
                sget-object v2, $equals
                invoke-static {v1, v2, p1}, $makeFilter
                move-result-object v1
                invoke-static {v1}, $makeGroup
                move-result-object v1
                invoke-virtual {v1}, $serializeFilters
                move-result-object v1
                iput-object v1, v0, $serialized
                sget-object v1, $encoding
                invoke-virtual {v0}, $bytes
                move-result-object v0
                invoke-virtual {v1, v0}, $encode
                move-result-object v0
                new-instance v1, ${tab.type}
                invoke-direct {v1}, ${tab.type}-><init>()V
                const/4 v2, 0x3
                iput v2, v1, $flags
                iput-object v0, v1, $tabKey
                new-instance v2, Ljava/io/File;
                invoke-direct {v2, p1}, Ljava/io/File;-><init>($STRING)V
                invoke-virtual {v2}, Ljava/io/File;->getName()$STRING
                move-result-object v2
                iput-object v2, v1, $label
                return-object v1
                :unsupported
                const/4 v0, 0x0
                return-object v0
            """.trimIndent())

        val augment = callback.addEmptyTabsBridge("modKitEmptyFolderTabs", listOf(immutableList), immutableList, 14,
            fallback = "return-object p1", body = """
                invoke-interface {p1}, Ljava/util/List;->size()I
                move-result v9
                if-eqz v9, :original
                new-array v2, v9, [$BUNDLE
                const/4 v0, 0x0
                const/4 v3, 0x0
                invoke-interface {p1}, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v1
                :next_tab
                invoke-interface {v1}, Ljava/util/Iterator;->hasNext()Z
                move-result v9
                if-eqz v9, :known
                invoke-interface {v1}, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v4
                check-cast v4, ${tab.type}
                iget-object v5, v4, $tabKey
                invoke-virtual {v5}, $STRING->isEmpty()Z
                move-result v9
                if-nez v9, :skip
                if-nez v0, :has_template
                move-object v0, v5
                :has_template
                iget-boolean v6, v4, $all
                if-nez v6, :skip
                new-instance v7, $BUNDLE
                invoke-direct {v7}, $BUNDLE-><init>()V
                invoke-static {v5}, $decodeId
                move-result-object v8
                iget-object v8, v8, $serialized
                const-string v9, "filters"
                invoke-virtual {v7, v9, v8}, $BUNDLE->putString($STRING$STRING)V
                invoke-static {v8}, $decodeFilters
                move-result-object v8
                invoke-virtual {v8, v7}, $collect
                aput-object v7, v2, v3
                :skip
                add-int/lit8 v3, v3, 0x1
                goto :next_tab
                :known
                if-eqz v0, :original
                iget-object v9, p0, $categoryField
                check-cast v9, Ljava/lang/Enum;
                invoke-virtual {v9}, Ljava/lang/Enum;->name()$STRING
                move-result-object v9
                invoke-static {v9, v2}, $HOOK->emptyFolders($STRING[$BUNDLE)[$STRING
                move-result-object v1
                array-length v2, v1
                if-eqz v2, :original
                new-instance v10, Ljava/util/ArrayList;
                invoke-direct {v10, p1}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
                const/4 v3, 0x0
                :append
                if-ge v3, v2, :freeze
                aget-object v4, v1, v3
                invoke-static {v0, v4}, $synthetic
                move-result-object v5
                if-eqz v5, :skip_added
                invoke-interface {v10, v5}, Ljava/util/List;->add(Ljava/lang/Object;)Z
                :skip_added
                add-int/lit8 v3, v3, 0x1
                goto :append
                :freeze
                invoke-static {v10}, $copyList
                move-result-object v0
                return-object v0
                :original
                return-object p1
            """.trimIndent())
        build.addInstructions(finish, """
            move-object/from16 v0, p0
            invoke-virtual {v0, v$result}, $augment
            move-result-object v$result
        """.trimIndent())
    }
}

/** Typed helpers leave the original list untouched if native decoding or encoding fails. */
private fun MutableClass.addEmptyTabsBridge(
    name: String, parameters: List<String>, result: String, registers: Int,
    static: Boolean = false, fallback: String, body: String,
): MutableMethod {
    check(methods.none { it.name == name })
    val method = MutableMethod(ImmutableMethod(type, name,
        parameters.map { ImmutableMethodParameter(it, emptySet(), null) }, result,
        AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0,
        emptySet(), emptySet(), MutableMethodImplementation(registers)))
    method.addInstructions(0, body)
    val handler = method.code().size
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
