package app.filesplus.patches.browse

import app.filesplus.patches.foldertabs.folderTabContextPatch
import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val ARCHIVE = "${Constants.EXT}browse/ArchiveFilter;"
private const val STATE = "${Constants.EXT}browse/ArchiveFilter\$State;"
private const val RELOAD = "${Constants.EXT}browse/ArchiveFilter\$Reload;"
private const val VIEW = "Landroid/view/View;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val PAGER = "Landroidx/viewpager2/widget/ViewPager2;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val THROWABLE = "Ljava/lang/Throwable;"
private const val PRIVATE = 0x2
private const val PUBLIC = 0x1
private const val STATIC = 0x8

val archiveFilterPatch = bytecodePatch(
    name = "Archives filter",
    description = "Add a native Archives filter chip to the Downloads tabs.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    // Install the folder-tabs wrapper first so catalogue reloads also refresh its context.
    dependsOn(modKitIntegrationPatch, browserResources, folderTabContextPatch)

    execute {
        val pagerId = browserResourceId("id/view_pager")
        val tabView = unique("Downloads tab view") {
            it.parameterTypes == listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;", BUNDLE) &&
                it.returnType == VIEW && it.resource(browserResourceId("layout/tab_fragment")) &&
                it.string("OnFileFilterClick") && it.string("android:get_usage_stats")
        }
        val pagerInit = unique("browser pager initialization") {
            it.parameterTypes == listOf(VIEW, BUNDLE) && it.returnType == "V" && it.resource(pagerId) &&
                it.string("File Browser Pager Adapter") && it.string("onPageSelected")
        }
        val category = unique("category enum initializer") {
            it.name == "<clinit>" && it.string("CATEGORY_DOWNLOAD") && it.string("CATEGORY_RECENTS")
        }.definingClass
        val leafCtor = unique("query condition constructor") {
            it.name == "<init>" && it.parameterTypes.size == 4 &&
                it.string("Null onField") && it.string("Null operator")
        }
        val leaf = leafCtor.definingClass
        val makeLeaf = classDefBy(leaf).methods.one("query condition factory") {
            it.isStatic() && it.returnType == leaf && it.parameterTypes == leafCtor.parameterTypes.take(3)
        }
        val group = unique("query filter decoder") {
            it.isStatic() && it.parameterTypes == listOf(STRING) &&
                it.returnType == it.definingClass && it.string("Invalid serialized filters: ")
        }.definingClass
        val makeGroup = classDefBy(group).methods.one("query filter collection factory") {
            it.isStatic() && it.returnType == group && it.parameterTypes == listOf("I", leaf, leaf, "[$leaf")
        }.calls().single { it.definingClass == group && it.parameterTypes == listOf("I", LIST) && it.returnType == group }
        val nameFieldType = unique("query name field", classPredicate = { field ->
            field.interfaces.any { parent ->
                classDefByOrNull(parent)?.interfaces?.contains(leafCtor.parameterTypes[0].toString()) == true
            }
        }) {
            it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == STRING && it.string("NAME")
        }.definingClass
        val operatorType = unique("query case-insensitive suffix operator") {
            it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == STRING &&
                it.string("ENDS_WITH_IGNORE_CASE")
        }.definingClass
        val filterFields = classDefBy(group).methods.flatMap { it.fields() }.distinctBy { it.toString() }
        val fileName = filterFields.single { it.type == nameFieldType }
        val endsWith = filterFields.single { it.type == operatorType }
        val andQuery = unique("native collection query conjunction") {
            it.isStatic() && it.parameterTypes.size == 2 && it.parameterTypes[0] == it.returnType &&
                it.parameterTypes[1] == group && it.calls().any { call ->
                    call.definingClass == group && call.parameterTypes == listOf("I", "[$group") && call.returnType == group
                }
        }
        val tab = andQuery.returnType
        val queryField = andQuery.code().filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { it.ref() as? FieldReference }.single { it.definingClass == tab && it.type == STRING }

        val peerAccessor = tabView.calls().distinctBy { it.toString() }.single {
            it.definingClass == tabView.definingClass && it.parameterTypes.isEmpty() &&
                classDefByOrNull(it.returnType)?.fields?.any { field -> field.type == tab } == true
        }
        val peer = peerAccessor.returnType
        val selectionObserver = mutableClassDefBy(unique("tab selection error callback", classPredicate = { owner ->
            owner.fields.any { it.type == peer }
        }) {
            it.parameterTypes == listOf(THROWABLE) && it.returnType == "V" && it.string("Unable to load the selection data")
        }.definingClass)
        val selectionChanged = selectionObserver.methods.one("tab selection value callback") {
            it.parameterTypes == listOf(OBJECT) && it.returnType == "V"
        }
        val selectionField = selectionChanged.code().filter { it.opcode == Opcode.IPUT_OBJECT }
            .mapNotNull { it.ref() as? FieldReference }.single()
        check(selectionField.definingClass == peer)
        val selectionCount = classDefBy(peer).methods.one("tab selection count consumer") {
            it.parameterTypes.isEmpty() && it.returnType == "Z" &&
                it.fields().any { field -> field.toString() == selectionField.toString() } &&
                it.calls().any { call -> call.definingClass == selectionField.type && call.returnType == "I" }
        }.calls().distinctBy { it.toString() }.single {
            it.definingClass == selectionField.type && it.parameterTypes.isEmpty() && it.returnType == "I"
        }
        val tabRoot = classDefBy(peer).fields.single { it.type == VIEW }
        val tabKey = tabView.fields().distinctBy { it.toString() }.single { it.definingClass == peer && it.type == tab }

        val tabCode = tabView.code()
        val conversion = tabCode.first { instruction ->
            (instruction.ref() as? MethodReference)?.let {
                it.definingClass == category && it.parameterTypes == listOf("I") && it.returnType == category
            } == true
        }
        val categoryRegister = (conversion as FiveRegisterInstruction).registerC
        val categoryRead = tabCode.takeWhile { it !== conversion }.last {
            it.opcode == Opcode.IGET && (it as TwoRegisterInstruction).registerA == categoryRegister
        }
        val tabCategory = categoryRead.ref() as FieldReference
        val tabConfig = tabView.fields().distinctBy { it.toString() }
            .single { it.definingClass == peer && it.type == tabCategory.definingClass }

        val pagerLiteral = tabCode.withIndex().single { it.value.literal() == pagerId }.index
        val findView = tabCode.drop(pagerLiteral + 1).mapNotNull { it.ref() as? MethodReference }.first()
        check(findView.name == "findViewById" && findView.parameterTypes == listOf("I") && findView.returnType == VIEW)
        val findViewInstruction = tabCode.drop(pagerLiteral + 1).first { it.ref() == findView }
        val activityRegister = (findViewInstruction as FiveRegisterInstruction).registerC
        val activityResult = tabCode.take(pagerLiteral).indexOfLast {
            it.opcode == Opcode.MOVE_RESULT_OBJECT && (it as OneRegisterInstruction).registerA == activityRegister
        }
        check(activityResult > 0)
        val activity = tabCode[activityResult - 1].ref() as MethodReference
        check(activity.parameterTypes.isEmpty())
        val pagerField = classDefBy(pagerInit.definingClass).fields.single { it.type == PAGER }
        val parentCategory = classDefBy(pagerInit.definingClass).fields.single { it.type == category }
        val adapterBase = pagerInit.calls().single {
            it.definingClass == PAGER && it.returnType == "V" && it.parameterTypes.size == 1 &&
                classDefByOrNull(it.parameterTypes[0].toString())?.methods?.any { method ->
                    method.parameterTypes.isEmpty() && method.isAbstract() && method.returnType == "I"
                } == true
        }.parameterTypes.single().toString()
        val notifyAdapter = classDefBy(adapterBase).methods.one("native adapter refresh") {
            it.returnType == "V" && it.parameterTypes.isEmpty() && AccessFlags.FINAL.isSet(it.accessFlags) &&
                !it.isStatic() && it.calls().any { call -> call.returnType == "V" && call.parameterTypes.isEmpty() }
        }
        val catalogue = mutableClassDefBy(unique("tab catalogue error callback") {
            it.parameterTypes == listOf(THROWABLE) && it.returnType == "V" &&
                it.string("Error loading child containers for %s")
        }.definingClass)
        val catalogueBody = catalogue.methods.one("tab catalogue value callback") {
            it.returnType == "V" && it.parameterTypes == listOf(OBJECT) && it.string("Failed to refresh the page.")
        }
        val catalogueUpdate = catalogue.methods.one("folder-tabs catalogue wrapper") {
            it.returnType == "V" && it.parameterTypes == listOf(OBJECT) &&
                !AccessFlags.PRIVATE.isSet(it.accessFlags) &&
                it.calls().any { call -> call.toString() == catalogueBody.toString() }
        }
        val parent = catalogue.fields.single { it.type == pagerInit.definingClass }
        val adapter = catalogueBody.fields().distinctBy { it.toString() }.single {
            it.definingClass == parent.type && classDefByOrNull(it.type)?.superclass == adapterBase
        }
        val selectionPeer = selectionObserver.fields.single { it.type == peer }

        installArchiveCatalogue(catalogue, catalogueUpdate, tab, queryField, parent, parentCategory,
            pagerField, adapter, notifyAdapter, group, fileName, endsWith, makeLeaf, makeGroup, andQuery)

        val tabFragment = mutableClassDefBy(tabView.definingClass)
        tabFragment.afterBrowserMethod(tabFragment.methods.single { it.toString() == tabView.toString() }, """
            invoke-virtual {p0}, $peerAccessor
            move-result-object v7
            iget-object v1, v7, $tabConfig
            iget v2, v1, $tabCategory
            const/4 v3, 0x2
            if-ne v2, v3, :done
            invoke-virtual {p0}, $activity
            move-result-object v1
            const v2, ${pagerId.hex()}
            invoke-virtual {v1, v2}, $findView
            move-result-object v1
            iget-object v2, v7, $tabKey
            iget-object v2, v2, $queryField
            iget-object v3, v7, $selectionField
            invoke-interface {v3}, $selectionCount
            move-result v3
            const v4, ${browserResourceId("id/file_filter_container").hex()}
            const v5, ${browserResourceId("id/file_filter_chip_group").hex()}
            const v6, ${browserResourceId("layout/file_filter_item").hex()}
            invoke-static/range {v0 .. v6}, $ARCHIVE->attach($VIEW${VIEW}${STRING}IIII)V
            :done
            nop
        """.trimIndent(), ARCHIVE)
        selectionObserver.afterBrowserMethod(selectionChanged, """
            iget-object v1, p0, $selectionPeer
            iget-object v2, v1, $tabRoot
            iget-object v3, v1, $selectionField
            invoke-interface {v3}, $selectionCount
            move-result v3
            invoke-static {v2, v3}, $ARCHIVE->selectionChanged(${VIEW}I)V
        """.trimIndent(), ARCHIVE)

        mutableClassDefBy(ARCHIVE).methods.single {
            it.name == "installed" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
    }
}

private fun installArchiveCatalogue(
    owner: MutableClass, update: MutableMethod, tab: String, query: FieldReference, parent: FieldReference,
    category: FieldReference, pager: FieldReference, adapter: FieldReference, notify: MethodReference,
    group: String, name: FieldReference, endsWith: FieldReference, makeLeaf: MethodReference,
    makeGroup: MethodReference, andQuery: MethodReference,
) {
    owner.interfaces.add(RELOAD)
    val state = owner.archiveField("modKitArchiveState", STATE)
    val originalTabs = owner.archiveField("modKitOriginalTabs", OBJECT)

    // A native OR of case-insensitive NAME suffix predicates, ANDed into each matching collection.
    val archiveGroup = owner.archiveMethod("modKitArchiveGroup", emptyList(), group, 9, PRIVATE or STATIC, """
        invoke-static {}, $ARCHIVE->suffixes()[Ljava/lang/String;
        move-result-object v0
        array-length v1, v0
        new-instance v2, Ljava/util/ArrayList;
        invoke-direct {v2, v1}, Ljava/util/ArrayList;-><init>(I)V
        sget-object v3, $name
        sget-object v4, $endsWith
        const/4 v5, 0x0
        :next
        if-ge v5, v1, :combine
        aget-object v6, v0, v5
        invoke-static {v3, v4, v6}, $makeLeaf
        move-result-object v7
        invoke-virtual {v2, v7}, Ljava/util/ArrayList;->add($OBJECT)Z
        add-int/lit8 v5, v5, 0x1
        goto :next
        :combine
        const/4 v8, 0x1
        invoke-static {v8, v2}, $makeGroup
        move-result-object v0
        return-object v0
    """)
    val rewrite = owner.archiveMethod("modKitRewriteArchiveTabs", listOf(OBJECT), OBJECT, 11, PRIVATE, """
        iget-object v0, p0, $state
        if-eqz v0, :original
        invoke-virtual {v0}, $STATE->hasRequests()Z
        move-result v1
        if-eqz v1, :original
        invoke-static {}, $archiveGroup
        move-result-object v2
        move-object v1, p1
        check-cast v1, $LIST
        invoke-interface {v1}, $LIST->size()I
        move-result v3
        new-instance v4, Ljava/util/ArrayList;
        invoke-direct {v4, v3}, Ljava/util/ArrayList;-><init>(I)V
        invoke-interface {v1}, $LIST->iterator()Ljava/util/Iterator;
        move-result-object v3
        :next
        invoke-interface {v3}, Ljava/util/Iterator;->hasNext()Z
        move-result v1
        if-eqz v1, :finished
        invoke-interface {v3}, Ljava/util/Iterator;->next()$OBJECT
        move-result-object v5
        check-cast v5, $tab
        iget-object v6, v5, $query
        invoke-virtual {v0, v6}, $STATE->isRequested($STRING)Z
        move-result v1
        if-eqz v1, :append
        invoke-static {v5, v2}, $andQuery
        move-result-object v5
        iget-object v7, v5, $query
        invoke-virtual {v0, v6, v7}, $STATE->recordVariant($STRING$STRING)V
        :append
        invoke-virtual {v4, v5}, Ljava/util/ArrayList;->add($OBJECT)Z
        goto :next
        :finished
        return-object v4
        :original
        return-object p1
    """)
    val prepare = owner.archiveMethod("modKitPrepareArchiveTabs", listOf(OBJECT), OBJECT, 7, PRIVATE, """
        iget-object v0, p0, $parent
        iget-object v1, v0, $category
        if-eqz v1, :original
        invoke-virtual {v1}, Ljava/lang/Enum;->name()$STRING
        move-result-object v1
        const-string v2, "CATEGORY_DOWNLOAD"
        invoke-virtual {v2, v1}, Ljava/lang/String;->equals($OBJECT)Z
        move-result v1
        if-eqz v1, :original
        iget-object v1, v0, $pager
        iget-object v2, p0, $state
        invoke-static {v1, p0, v2}, $ARCHIVE->bind($VIEW$RELOAD$STATE)$STATE
        move-result-object v2
        iput-object v2, p0, $state
        iput-object p1, p0, $originalTabs
        invoke-direct {p0, p1}, $rewrite
        move-result-object v0
        return-object v0
        :original
        return-object p1
    """, """
        iget-object v1, p0, $state
        if-eqz v1, :fallback
        invoke-virtual {v1}, $STATE->clear()V
        :fallback
        return-object p1
    """)
    val nameBefore = update.name
    val access = update.accessFlags
    check(owner.methods.none { it.name == "modKitArchiveOriginalTabs" })
    check(owner.methods.remove(update))
    update.name = "modKitArchiveOriginalTabs"
    update.accessFlags = PRIVATE
    owner.methods.add(update)
    val catalogueCallback = owner.archiveMethod(nameBefore, listOf(OBJECT), "V", 3, access, """
        invoke-direct {p0, p1}, $prepare
        move-result-object v0
        invoke-direct {p0, v0}, $update
        return-void
    """)
    // Re-enter the outer callback with the raw catalogue. It reapplies the filter and calls
    // the folder-tabs wrapper, which publishes the refreshed tabs after the native update.
    owner.archiveMethod("modKitReloadArchives", emptyList(), "Z", 5, PUBLIC, """
        iget-object v0, p0, $originalTabs
        if-eqz v0, :unavailable
        iget-object v1, p0, $parent
        iget-object v2, v1, $adapter
        if-eqz v2, :unavailable
        invoke-virtual {p0, v0}, $catalogueCallback
        invoke-virtual {v2}, $notify
        const/4 v0, 0x1
        return v0
        :unavailable
        const/4 v0, 0x0
        return v0
    """, "const/4 v0, 0x0\nreturn v0")
}

private fun MutableClass.archiveField(name: String, signature: String): FieldReference {
    check(fields.none { it.name == name })
    return MutableField(ImmutableField(type, name, signature, PRIVATE, null, emptySet(), emptySet()))
        .also { fields.add(it) }
}

private fun MutableClass.archiveMethod(
    name: String, parameters: List<String>, result: String, registers: Int, access: Int,
    body: String, fallback: String? = null,
): MutableMethod {
    check(registers <= 16 && registers > parameters.size + if (AccessFlags.STATIC.isSet(access)) 0 else 1)
    check(methods.none { it.name == name && it.parameterTypes == parameters })
    val method = MutableMethod(ImmutableMethod(type, name,
        parameters.map { ImmutableMethodParameter(it, emptySet(), null) }, result, access,
        emptySet(), emptySet(), MutableMethodImplementation(registers)))
    method.addInstructions(0, body.trimIndent())
    if (fallback != null) {
        val handler = method.code().size
        method.addInstructionsWithLabels(handler, """
            move-exception v0
            invoke-static {v0}, $ARCHIVE->hookError($THROWABLE)V
            ${fallback.trimIndent()}
        """.trimIndent())
        method.implementation!!.apply {
            addCatch(THROWABLE, newLabelForIndex(0), newLabelForIndex(handler), newLabelForIndex(handler))
        }
    }
    methods.add(method)
    return method
}

private fun BytecodePatchContext.unique(
    label: String, classPredicate: (com.android.tools.smali.dexlib2.iface.ClassDef) -> Boolean = { true },
    predicate: (Method) -> Boolean,
): Method {
    val matches = mutableListOf<Method>()
    classDefForEach { if (classPredicate(it)) it.methods.filterTo(matches, predicate) }
    return matches.one(label) { true }
}

private fun <T> Iterable<T>.one(label: String, predicate: (T) -> Boolean): T {
    val matches = filter(predicate)
    check(matches.size == 1) { "Archives filter fingerprint $label: expected 1 match, got ${matches.size}" }
    return matches.single().also { println("Archives filter fingerprint $label: 1 match ($it)") }
}

private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
private fun Instruction.literal() = (this as? WideLiteralInstruction)?.wideLiteral
private fun Method.calls() = code().mapNotNull { it.ref() as? MethodReference }
private fun Method.fields() = code().mapNotNull { it.ref() as? FieldReference }
private fun Method.string(value: String) = code().any { (it.ref() as? StringReference)?.string == value }
private fun Method.resource(id: Long) = code().any { it.literal() == id }
private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.isAbstract() = AccessFlags.ABSTRACT.isSet(accessFlags)
private fun Long.hex() = "0x${toString(16)}"
