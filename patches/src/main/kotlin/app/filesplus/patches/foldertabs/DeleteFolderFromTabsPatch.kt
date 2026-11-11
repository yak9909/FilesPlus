package app.filesplus.patches.foldertabs

import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
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
private const val VIEW = "Landroid/view/View;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val PAGER = "Landroidx/viewpager2/widget/ViewPager2;"
private const val RUNNABLE = "Ljava/lang/Runnable;"

private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
private fun Method.string(text: String) = code().any { (it.ref() as? StringReference)?.string == text }
private fun Method.literal(value: Long) = code().any { (it as? WideLiteralInstruction)?.wideLiteral == value }
private fun Method.fields() = code().mapNotNull { it.ref() as? FieldReference }
private fun Method.calls() = code().mapNotNull { it.ref() as? MethodReference }

/** Shared context hooks are installed once, even when only the empty-tabs patch is selected. */
internal val folderTabContextPatch = bytecodePatch {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch)

    execute {
        // Saved-state keys and the AndroidX pager identify the shared controller for every category.
        val pager = mutableClassDefBy(classDefBy { cls ->
            cls.methods.any { it.string("selected_tab_index") } && cls.fields.any { it.type == PAGER }
        })
        val initialize = pager.methods.single { it.string("onPageSelected") &&
            it.parameterTypes == listOf(VIEW, BUNDLE) }
        val current = pager.methods.single { method ->
            method.parameterTypes.isEmpty() && method.returnType != "V" &&
                method.code().any { it.opcode == Opcode.CHECK_CAST } &&
                method.fields().any { it.type == LIST }
        }
        val tabType = (current.code().single { it.opcode == Opcode.CHECK_CAST }.ref() as TypeReference).type
        val list = current.fields().first { it.type == LIST }
        val index = current.fields().first { it.type == "I" && it.definingClass == pager.type }
        val pagerView = pager.fields.single { it.type == PAGER }
        val setPage = initialize.calls().first { it.definingClass == PAGER &&
            it.parameterTypes == listOf("I", "Z") && it.returnType == "V" }

        val collections = classDefBy { it.methods.any { m ->
            m.string("SubFileCollection requested for invalid fileCategory: ") } }
        val tabKey = collections.methods.flatMap { it.fields() }.filter {
            it.definingClass == tabType && it.type == STRING
        }.distinctBy { it.toString() }.single()
        // The All factory writes its boolean flag next to the stable all_label resource literal.
        val allFactory = classDefBy { it.methods.any { m -> m.literal(0x7f130026) && m.code().any {
            instruction -> instruction.opcode == Opcode.IPUT_BOOLEAN &&
                (instruction.ref() as? FieldReference)?.definingClass == tabType
        } } }.methods.single { it.literal(0x7f130026) }
        val all = allFactory.code().first { it.opcode == Opcode.IPUT_BOOLEAN &&
            (it.ref() as? FieldReference)?.definingClass == tabType }.ref() as FieldReference

        // Follow the app's collection decoder to its filter decoder; never parse protobufs in Java.
        val collectionDecode = classDefBy { it.methods.any { m -> m.string("Unrecognized collection identifier.") } }
            .methods.single { it.string("Unrecognized collection identifier.") }
        val decodeId = collectionDecode.calls().first { it.parameterTypes == listOf(STRING) }
        val serializedFilters = collectionDecode.fields().first {
            it.definingClass == decodeId.returnType && it.type == STRING
        }
        val filterType = collectionDecode.parameterTypes[1].toString()
        val filters = mutableClassDefBy(filterType)
        val decodeFilters = filters.methods.single {
            it.string("Invalid serialized filters: ") && it.parameterTypes == listOf(STRING)
        }
        val filter = mutableClassDefBy(classDefBy { it.methods.any { m ->
            m.string("Null onField") && m.string("Null operator") } })
        val filterConstructor = filter.methods.single { it.name == "<init>" && it.parameterTypes.size == 4 }
        val onField = filter.fields.single { it.type == filterConstructor.parameterTypes[0] }
        val operator = filter.fields.single { it.type == filterConstructor.parameterTypes[1] }
        val value = filter.fields.single { it.type == "Ljava/lang/Object;" }
        val capture = filter.bridge("modKitFolderFilter", listOf(BUNDLE), 5, """
            iget-object v0, p0, $onField
            invoke-static {v0}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)$STRING
            move-result-object v0
            iget-object v1, p0, $operator
            invoke-static {v1}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)$STRING
            move-result-object v1
            iget-object v2, p0, $value
            invoke-static {p1, v0, v1, v2}, $HOOK->captureFilter(${BUNDLE}${STRING}${STRING}Ljava/lang/Object;)V
        """.trimIndent(), invalidateBundle = true)
        val filterCtor = filters.methods.single { it.name == "<init>" && it.parameterTypes.size == 3 }
        val ctorFields = filterCtor.code().filter { it.opcode in setOf(Opcode.IPUT, Opcode.IPUT_OBJECT) }
            .map { it.ref() as FieldReference }
        val groupOperator = ctorFields.single { it.type == "I" }
        val children = ctorFields.filter { it.type == filterCtor.parameterTypes[1] }
        check(children.size == 2)
        val collectName = "modKitFolderFilters"
        val collect = filters.bridge(collectName, listOf(BUNDLE), 6, """
            iget v0, p0, $groupOperator
            const/4 v1, 0x2
            if-ne v0, v1, :done
            iget-object v0, p0, ${children[0]}
            invoke-interface {v0}, $LIST->iterator()Ljava/util/Iterator;
            move-result-object v0
            :filters
            invoke-interface {v0}, Ljava/util/Iterator;->hasNext()Z
            move-result v1
            if-eqz v1, :groups
            invoke-interface {v0}, Ljava/util/Iterator;->next()Ljava/lang/Object;
            move-result-object v1
            check-cast v1, ${filter.type}
            invoke-virtual {v1, p1}, $capture
            goto :filters
            :groups
            iget-object v0, p0, ${children[1]}
            invoke-interface {v0}, $LIST->iterator()Ljava/util/Iterator;
            move-result-object v0
            :next
            invoke-interface {v0}, Ljava/util/Iterator;->hasNext()Z
            move-result v1
            if-eqz v1, :done
            invoke-interface {v0}, Ljava/util/Iterator;->next()Ljava/lang/Object;
            move-result-object v1
            check-cast v1, $filterType
            invoke-virtual {v1, p1}, $filterType->$collectName(${BUNDLE})V
            goto :next
            :done
        """.trimIndent(), invalidateBundle = true)

        check(pager.fields.none { it.name == "modKitFolderTabs" })
        pager.fields.add(MutableField(ImmutableField(pager.type, "modKitFolderTabs", HOOK,
            AccessFlags.PRIVATE.value, null, emptySet(), emptySet())))
        val context = "${pager.type}->modKitFolderTabs:$HOOK"
        val selected = pager.bridge("modKitFolderSelected", emptyList(), 9, """
            iget-object v0, p0, $context
            if-eqz v0, :done
            const/4 v1, 0x0
            const/4 v2, 0x1
            invoke-virtual {v0, v1, v2, v1}, $HOOK->select(${STRING}Z${BUNDLE})V
            iget v1, p0, $index
            if-ltz v1, :empty
            iget-object v2, p0, $list
            invoke-interface {v2}, $LIST->size()I
            move-result v3
            if-ge v1, v3, :empty
            invoke-interface {v2, v1}, $LIST->get(I)Ljava/lang/Object;
            move-result-object v4
            check-cast v4, $tabType
            iget-object v5, v4, $tabKey
            iget-boolean v7, v4, $all
            new-instance v6, $BUNDLE
            invoke-direct {v6}, $BUNDLE-><init>()V
            if-nez v7, :select
            invoke-virtual {v5}, $STRING->isEmpty()Z
            move-result v3
            if-eqz v3, :decode
            const/4 v7, 0x1
            goto :select
            :decode
            invoke-static {v5}, $decodeId
            move-result-object v3
            iget-object v3, v3, $serializedFilters
            invoke-static {v3}, $decodeFilters
            move-result-object v3
            invoke-virtual {v3, v6}, $collect
            :select
            invoke-virtual {v0, v5, v7, v6}, $HOOK->select(${STRING}Z${BUNDLE})V
            goto :done
            :empty
            const/4 v1, 0x0
            const/4 v2, 0x1
            invoke-virtual {v0, v1, v2, v1}, $HOOK->select(${STRING}Z${BUNDLE})V
            :done
        """.trimIndent())
        val attach = pager.bridge("modKitFolderAttach", listOf(VIEW), 4, """
            invoke-static {p1, p0}, $HOOK->attach(${VIEW}${RUNNABLE})$HOOK
            move-result-object v0
            iput-object v0, p0, $context
            invoke-virtual {p0}, $selected
        """.trimIndent())
        initialize.beforeReturns("invoke-virtual {p0, p1}, $attach")

        // Native page callback and list subscriber are found through their controller field and work.
        val pageCallback = mutableClassDefBy(classDefBy { cls ->
            cls.fields.any { it.type == pager.type } && cls.methods.any { method ->
                method.parameterTypes == listOf("I") && method.code().any {
                    it.opcode == Opcode.IPUT && it.ref().toString() == index.toString()
                }
            }
        })
        val pageOwner = pageCallback.fields.single { it.type == pager.type }
        val pageBridge = pageCallback.bridge("modKitFolderPage", emptyList(), 2, """
            iget-object v0, p0, $pageOwner
            invoke-virtual {v0}, $selected
        """.trimIndent())
        pageCallback.methods.single { it.parameterTypes == listOf("I") && it.returnType == "V" }
            .beforeReturns("invoke-virtual {p0}, $pageBridge")
        val loaded = mutableClassDefBy(classDefBy { it.methods.any { m -> m.string("Failed to refresh the page.") } })
        val loadedOwner = loaded.fields.single { it.type == pager.type }
        val changed = pager.bridge("modKitFolderList", listOf(LIST), 4, """
            iget-object v0, p0, $context
            if-eqz v0, :done
            invoke-virtual {v0}, $HOOK->tabsChanged()V
            if-eqz p1, :done
            invoke-interface {p1}, $LIST->isEmpty()Z
            move-result v1
            if-nez v1, :done
            invoke-virtual {p0}, $selected
            :done
        """.trimIndent())
        val loadedBridge = loaded.bridge("modKitFolderLoaded", listOf("Ljava/lang/Object;"), 4, """
            iget-object v0, p0, $loadedOwner
            check-cast p1, $LIST
            invoke-virtual {v0, p1}, $changed
        """.trimIndent())
        loaded.methods.single { it.string("Failed to refresh the page.") }
            .beforeReturns("invoke-virtual {p0, p1}, $loadedBridge")
        val loadFailed = loaded.bridge("modKitFolderLoadFailed", emptyList(), 3, """
            iget-object v0, p0, $loadedOwner
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $changed
        """.trimIndent())
        loaded.methods.single { it.parameterTypes == listOf("Ljava/lang/Throwable;") && it.returnType == "V" }
            .beforeReturns("invoke-virtual {p0}, $loadFailed")

        // lrx.h(Uri) is the app's MediaStore invalidation path used by its ContentObserver.
        val source = pager.fields.single { it.type == collections.type }
        val storageTypes = mutableSetOf<String>()
        classDefForEach { cls ->
            if (AccessFlags.INTERFACE.isSet(cls.accessFlags) && cls.methods.any {
                it.parameterTypes == listOf("Landroid/net/Uri;") && it.returnType == "V"
            }) {
                storageTypes += cls.type
            }
        }
        val storage = collections.fields.single { it.type in storageTypes }
        val invalidate = classDefBy(storage.type).methods.single {
            it.parameterTypes == listOf("Landroid/net/Uri;") && it.returnType == "V"
        }
        check(RUNNABLE !in pager.interfaces && pager.methods.none { it.name == "run" })
        pager.interfaces.add(RUNNABLE)
        pager.bridge("run", emptyList(), 5, """
            iget-object v0, p0, $context
            if-eqz v0, :done
            iget v1, p0, $index
            iget-object v2, p0, $list
            invoke-interface {v2}, $LIST->size()I
            move-result v2
            invoke-virtual {v0, v1, v2}, $HOOK->refreshIndex(II)I
            move-result v1
            iput v1, p0, $index
            iget-object v2, p0, $pagerView
            const/4 v3, 0x0
            invoke-virtual {v2, v1, v3}, $setPage
            iget-object v1, p0, $source
            iget-object v1, v1, $storage
            const-string v2, "external"
            invoke-static {v2}, Landroid/provider/MediaStore${'$'}Files;->getContentUri($STRING)Landroid/net/Uri;
            move-result-object v2
            invoke-interface {v1, v2}, $invalidate
            :done
        """.trimIndent())

    }
}

val deleteFolderFromTabsPatch = bytecodePatch(
    name = "Delete folder from tabs",
    description = "Permanently delete the current folder from category tabs, with confirmation and protected folders.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch, folderTabContextPatch)

    execute {
        val menuOwner = mutableClassDefBy(classDefBy { cls -> cls.methods.any { method ->
            method.parameterTypes == listOf("Landroid/view/Menu;") && method.literal(0x7f0f001b)
        } })
        val createMenu = menuOwner.methods.single { it.parameterTypes == listOf("Landroid/view/Menu;") &&
            it.literal(0x7f0f001b) }
        val fragmentType = menuOwner.methods.single { it.name == "<init>" }.parameterTypes.first().toString()
        val fragment = menuOwner.fields.single { it.type == fragmentType }
        val rootView = classDefBy(fragmentType).methods.single { it.parameterTypes.isEmpty() && it.returnType == VIEW }
        val selection = createMenu.code().first { it.opcode == Opcode.IGET_BOOLEAN }.ref() as FieldReference
        val menuBridge = menuOwner.bridge("modKitFolderMenu", listOf("Landroid/view/Menu;"), 4, """
            iget-object v0, p0, $fragment
            invoke-virtual {v0}, $rootView
            move-result-object v0
            iget-boolean v1, p0, $selection
            invoke-static {v0, p1, v1}, $HOOK->onMenu(${VIEW}Landroid/view/Menu;Z)V
        """.trimIndent())
        createMenu.beforeReturns("invoke-virtual {p0, p1}, $menuBridge")
    }
}

/** Isolated low-register helpers preserve original registers and catch bridge failures as well. */
private fun MutableClass.bridge(
    name: String, parameters: List<String>, registers: Int, body: String, invalidateBundle: Boolean = false,
): MutableMethod {
    check(methods.none { it.name == name }) { "Folder-tabs bridge already exists: $type->$name" }
    val method = MutableMethod(ImmutableMethod(type, name,
        parameters.map { ImmutableMethodParameter(it, emptySet(), null) }, "V", AccessFlags.PUBLIC.value,
        emptySet(), emptySet(), MutableMethodImplementation(registers)))
    val invalidate = if (!invalidateBundle) "" else """
        const-string v1, "invalid"
        const/4 v2, 0x1
        invoke-virtual {p1, v1, v2}, $BUNDLE->putBoolean(${STRING}Z)V
    """.trimIndent()
    method.addInstructions(0, "$body\nreturn-void")
    val handler = method.code().size
    method.addInstructions(handler, """
        move-exception v0
        $invalidate
        invoke-static {v0}, $HOOK->hookError(Ljava/lang/Throwable;)V
        return-void
    """.trimIndent())
    method.implementation!!.apply {
        addCatch("Ljava/lang/Throwable;", newLabelForIndex(0), newLabelForIndex(handler), newLabelForIndex(handler))
    }
    methods.add(method)
    return method
}

private fun MutableMethod.beforeReturns(instruction: String) {
    val returns = code().withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
    check(returns.isNotEmpty())
    for ((index) in returns.reversed()) addInstructions(index, instruction)
}
