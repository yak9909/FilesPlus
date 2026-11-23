package app.filesplus.patches.smb

import app.filesplus.patches.browse.afterBrowserMethod
import app.filesplus.patches.browse.browserHooksPatch
import app.filesplus.patches.browse.browserResourceId
import app.filesplus.patches.browse.takeBrowserBindings
import app.filesplus.patches.preview.calls
import app.filesplus.patches.preview.code
import app.filesplus.patches.preview.literal
import app.filesplus.patches.preview.ref
import app.filesplus.patches.preview.string
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val HOOK = "${Constants.EXT}smb/SmbRoots;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val CONTEXT = "Landroid/content/Context;"
private const val URI = "Landroid/net/Uri;"
private const val MENU = "Landroid/view/Menu;"
private const val INFLATER = "Landroid/view/MenuInflater;"
private const val VIEW = "Landroid/view/View;"
private const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

private fun Method.resource(name: String) = literal(browserResourceId(name))
private fun Method.fields() = code().mapNotNull { it.ref() as? FieldReference }.distinctBy { it.toString() }

private fun <T> Iterable<T>.one(label: String, match: (T) -> Boolean): T {
    val hits = filter(match)
    check(hits.size == 1) { "SMB roots fingerprint $label: expected 1 match, got ${hits.size}: $hits" }
    return hits.single().also { println("SMB roots fingerprint $label: 1 match ($it)") }
}

private fun BytecodePatchContext.unique(label: String, predicate: (Method) -> Boolean): Method {
    val hits = mutableListOf<Method>()
    classDefForEach { cls -> cls.methods.filterTo(hits, predicate) }
    return hits.one(label) { true }
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single { it.toString() == method.toString() }

val smbStorageRootsPatch = bytecodePatch(
    name = "SMB in storage browser",
    description = "Browse saved SMB connections from All storage in the native read-only storage browser.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(browserHooksPatch)

    execute {
        val browser = takeBrowserBindings()
        val hooks = mutableClassDefBy(HOOK)
        hooks.methods.single { it.name == "installed" }.addInstructions(0, "const/4 v0, 0x1\nreturn v0")

        val loadError = unique("root loader failure") {
            it.parameterTypes == listOf("Ljava/lang/Throwable;") && it.string("Cannot load root containers")
        }
        val loader = mutableClassDefBy(loadError.definingClass)
        val handoff = loader.methods.one("root adapter handoff") {
            it.parameterTypes == listOf(OBJECT) && it.returnType == "V" &&
                it.code().any { insn -> insn.opcode == Opcode.CHECK_CAST && (insn.ref() as? TypeReference)?.type == LIST } &&
                it.calls().any { call -> call.parameterTypes == listOf(LIST) && call.returnType == "V" }
        }
        val loaderPeer = handoff.fields().single { it.definingClass == loader.type }
        val homeView = unique("All storage fragment") {
            it.parameterTypes == listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;", "Landroid/os/Bundle;") &&
                it.returnType == VIEW && it.resource("layout/root_container_list_v2_fragment")
        }
        val hierarchy = generateSequence(homeView.definingClass) { classDefByOrNull(it)?.superclass }.toSet()
        val homeFragment = classDefBy(loaderPeer.type).fields.single { it.type in hierarchy }
        val host = hierarchy.mapNotNull { classDefByOrNull(it) }.flatMap { it.methods }.one("fragment activity accessor") {
            it.parameterTypes.isEmpty() && it.string(" not attached to an activity.") &&
                ACTIVITY in generateSequence(it.returnType) { type -> classDefByOrNull(type)?.superclass }.toSet()
        }

        val adapt = MutableMethod(ImmutableMethod(loader.type, "modKitAppendSmbRoots",
            listOf(ImmutableMethodParameter(OBJECT, emptySet(), null)), OBJECT, AccessFlags.PRIVATE.value,
            emptySet(), emptySet(), MutableMethodImplementation(4)))
        adapt.addInstructions(0, """
            iget-object v0, p0, $loaderPeer
            iget-object v0, v0, $homeFragment
            invoke-virtual {v0}, $host
            move-result-object v0
            move-object v1, p1
            check-cast v1, $LIST
            invoke-static {v1, v0}, $HOOK->append($LIST$ACTIVITY)$LIST
            move-result-object v1
            return-object v1
            move-exception v0
            invoke-static {v0}, $HOOK->hookError(Ljava/lang/Throwable;)V
            return-object p1
        """.trimIndent())
        adapt.implementation!!.apply {
            val handler = adapt.code().indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
            addCatch("Ljava/lang/Throwable;", newLabelForIndex(0), newLabelForIndex(handler), newLabelForIndex(handler))
        }
        loader.methods.add(adapt)
        handoff.addInstructions(0, "invoke-direct/range {p0 .. p1}, $adapt\nmove-result-object p1")

        val rowBinder = unique("native capsule row binder") {
            it.parameterTypes == listOf(VIEW, OBJECT) && it.returnType == "V" &&
                it.string("onClickRootContainer") && it.resource("id/storage_location_unknown_item_view")
        }
        val binderFields = rowBinder.fields()
        val folder = browser.folderType
        val folderField = binderFields.single { it.type == folder && it.definingClass != folder }
        val wrapperType = folderField.definingClass
        val rowType = rowBinder.code().mapNotNull { (it.ref() as? TypeReference)?.type }.distinct().single { type ->
            binderFields.any { it.definingClass == type && it.type == OBJECT } && type != folder
        }
        val rowTag = binderFields.single { it.definingClass == rowType && it.type == "I" }
        val rowData = binderFields.single { it.definingClass == rowType && it.type == OBJECT }
        val label = binderFields.single { it.definingClass == folder && it.type == OBJECT }
        val rawKind = rowBinder.calls().distinctBy { it.toString() }.one("storage enum decoder") {
            it.parameterTypes == listOf("I") && classDefByOrNull(it.returnType)?.methods?.any { method ->
                method.name == "<clinit>" && method.string("STORAGE_LOCATION_UNKNOWN")
            } == true
        }
        val kind = rowBinder.code().zipWithNext().mapNotNull { (before, call) ->
            (before.ref() as? FieldReference)?.takeIf {
                before.opcode == Opcode.IGET && it.definingClass == folder && call.ref()?.toString() == rawKind.toString()
            }
        }.distinctBy { it.toString() }.single()
        val click = unique("native root click") {
            it.parameterTypes == listOf(VIEW) && it.returnType == "V" && it.string("placeholder") &&
                it.fields().any { field -> field.definingClass == folder && field.type == STRING }
        }
        val location = click.fields().single { it.definingClass == folder && it.type == STRING }
        val exists = classDefBy(folder).fields.single { it.type == STRING && it.toString() != location.toString() }
        val validatorStub = hooks.methods.single { it.name == "validRow" }
        // Keep the row object, the scalar comparisons and the field value in separate locals.
        val validate = MutableMethod(ImmutableMethod(hooks.type, validatorStub.name, validatorStub.parameters,
            validatorStub.returnType, validatorStub.accessFlags, validatorStub.annotations,
            validatorStub.hiddenApiRestrictions, MutableMethodImplementation(6)))
        validate.addInstructionsWithLabels(0, """
            move-object v0, p0
            check-cast v0, $rowType
            iget v1, v0, $rowTag
            add-int/lit8 v1, v1, -0x1
            if-nez v1, :invalid
            iget-object v0, v0, $rowData
            check-cast v0, $wrapperType
            iget-object v0, v0, $folderField
            iget v1, v0, $kind
            add-int/lit8 v1, v1, -0x2
            if-nez v1, :invalid
            iget-object v2, v0, $label
            invoke-virtual {p1, v2}, $STRING->equals($OBJECT)Z
            move-result v1
            if-eqz v1, :invalid
            iget-object v2, v0, $location
            invoke-virtual {p2, v2}, $STRING->equals($OBJECT)Z
            move-result v1
            if-eqz v1, :invalid
            iget-object v2, v0, $exists
            invoke-virtual {p2, v2}, $STRING->equals($OBJECT)Z
            move-result v1
            return v1
            :invalid
            const/4 v1, 0x0
            return v1
        """.trimIndent())
        check(hooks.methods.remove(validatorStub))
        hooks.methods.add(validate)

        val providerCheck = unique("DocumentsProvider URI classifier") {
            it.parameterTypes == listOf(URI, CONTEXT) && it.returnType == "Z" &&
                it.string("android.content.action.DOCUMENTS_PROVIDER") &&
                it.calls().any { call -> call.definingClass == "Landroid/content/pm/PackageManager;" &&
                    call.name == "queryIntentContentProviders" }
        }.let(::mutable)
        check(providerCheck.implementation!!.registerCount > 2) { "SMB roots providerCheck needs a scratch local" }
        providerCheck.addInstructionsWithLabels(0, """
            invoke-static/range {p0 .. p1}, $HOOK->isProviderUri($URI$CONTEXT)Z
            move-result v0
            if-eqz v0, :native
            return v0
        """.trimIndent(), ExternalLabel("native", providerCheck.code().first()))

        val storage = browser.storage
        val currentFolder = browser.storageFolder
        val watchError = unique("native root catalogue observer error") {
            it.parameterTypes == listOf("Ljava/lang/Throwable;") && it.string("Unable to load root containers") &&
                it.fields().any { field -> field.definingClass == storage.type }
        }
        val watcher = mutableClassDefBy(watchError.definingClass)
        val onCatalogue = watcher.methods.one("native root catalogue observer") {
            it.parameterTypes == listOf(OBJECT) && it.returnType == "V" &&
                it.code().any { instruction ->
                    val field = instruction.ref() as? FieldReference
                    instruction.opcode == Opcode.IPUT_OBJECT && field?.definingClass == storage.type && field.type == LIST
                } && it.fields().any { field -> field.definingClass == storage.type && field.type == folder }
        }
        val watcherPeer = onCatalogue.fields().single { it.definingClass == watcher.type && it.type == storage.type }
        val watchedRoot = onCatalogue.fields().single { it.definingClass == storage.type && it.type == folder }
        val catalogue = onCatalogue.fields().single { it.definingClass == storage.type && it.type == LIST }
        // Preserve the native catalogue assignment. The SMB root's existence is checked by its separate URI observer.
        val keepOpen = MutableMethod(ImmutableMethod(watcher.type, "modKitKeepSmbBrowserOpen",
            listOf(ImmutableMethodParameter(OBJECT, emptySet(), null)), "Z", AccessFlags.PRIVATE.value,
            emptySet(), emptySet(), MutableMethodImplementation(4)))
        keepOpen.addInstructionsWithLabels(0, """
            iget-object v0, p0, $watcherPeer
            iget-object v1, v0, $watchedRoot
            invoke-static {v1}, $HOOK->isFolderProto($OBJECT)Z
            move-result v1
            if-eqz v1, :native
            move-object v1, p1
            check-cast v1, $LIST
            iput-object v1, v0, $catalogue
            const/4 v0, 0x1
            return v0
            :native
            const/4 v0, 0x0
            return v0
            move-exception v0
            invoke-static {v0}, $HOOK->hookError(Ljava/lang/Throwable;)V
            const/4 v0, 0x0
            return v0
        """.trimIndent())
        keepOpen.implementation!!.apply {
            val handler = keepOpen.code().indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
            addCatch("Ljava/lang/Throwable;", newLabelForIndex(0), newLabelForIndex(handler), newLabelForIndex(handler))
        }
        watcher.methods.add(keepOpen)
        check(onCatalogue.implementation!!.registerCount > 2) { "SMB root catalogue observer needs a scratch local" }
        onCatalogue.addInstructionsWithLabels(0, """
            invoke-direct/range {p0 .. p1}, $keepOpen
            move-result v0
            if-eqz v0, :native
            return-void
        """.trimIndent(), ExternalLabel("native", onCatalogue.code().first()))

        val filePopup = storage.methods.one("storage file popup") {
            it.parameterTypes.size == 3 && it.resource("menu/advanced_browsing_file_dropdown_menu")
        }
        val fileType = filePopup.parameterTypes[2].toString()
        storage.afterBrowserMethod(filePopup, """
            iget-object v1, p0, $currentFolder
            invoke-static {p2, v1, p3}, $HOOK->filterMenu($MENU$OBJECT$OBJECT)V
        """.trimIndent(), HOOK)
        val folderPopup = storage.methods.one("storage folder popup") {
            it.parameterTypes == listOf(INFLATER, MENU) && it.resource("menu/advanced_browsing_folder_dropdown_menu")
        }
        storage.afterBrowserMethod(folderPopup, """
            iget-object v1, p0, $currentFolder
            const/4 v2, 0x0
            invoke-static {p2, v1, v2}, $HOOK->filterMenu($MENU$OBJECT$OBJECT)V
        """.trimIndent(), HOOK)

        val actionUpdate = unique("ActionMode menu holder") {
            it.string("updateAllMenuItems should be called when action exists")
        }
        val action = actionUpdate.fields().single { field ->
            classDefByOrNull(field.type)?.methods?.any { it.parameterTypes.isEmpty() && it.returnType == MENU } == true
        }
        val actionMenu = classDefBy(action.type).methods.one("ActionMode getMenu") {
            it.parameterTypes.isEmpty() && it.returnType == MENU
        }
        val holder = storage.fields.single { it.type == actionUpdate.definingClass }
        val selection = storage.methods.one("storage selection visibility") {
            it.parameterTypes.isEmpty() && it.returnType == "V" &&
                it.resource("id/move_to_action") && it.resource("id/copy_to_action") &&
                it.calls().any { call -> call.definingClass == holder.type }
        }
        storage.afterBrowserMethod(selection, """
            iget-object v1, p0, $holder
            iget-object v1, v1, $action
            if-eqz v1, :done
            invoke-virtual {v1}, $actionMenu
            move-result-object v1
            iget-object v2, p0, $currentFolder
            const/4 v3, 0x0
            invoke-static {v1, v2, v3}, $HOOK->filterMenu($MENU$OBJECT$OBJECT)V
            :done
        """.trimIndent(), HOOK)

        val fab = storage.methods.one("both native create-folder FABs") {
            it.parameterTypes == listOf(VIEW, "I") && it.returnType == "V" &&
                it.resource("id/create_new_folder") && it.resource("id/create_new_folder_expressive")
        }
        check(fab.implementation!!.registerCount > 3) { "SMB roots fab needs a scratch local" }
        // Each fragment owns its FAB and an SMB browser stays on SMB folders, so no restore is needed.
        fab.addInstructionsWithLabels(0, """
            iget-object v0, p0, $currentFolder
            invoke-static {p1, v0}, $HOOK->hideFab($VIEW$OBJECT)Z
            move-result v0
            if-eqz v0, :native
            return-void
        """.trimIndent(), ExternalLabel("native", fab.code().first()))

        val isStorageMenuList: (Method) -> Boolean = {
            it.parameterTypes == listOf(OBJECT) && it.returnType == LIST &&
                it.resource("string/file_browser_menu_item_folder_info") &&
                it.resource("string/file_browser_backup_to_google_drive")
        }
        val listProviderClass = unique("storage file and folder menu lists", isStorageMenuList).definingClass
        val provider = mutableClassDefBy(listProviderClass)
        val listProvider = provider.methods.single(isStorageMenuList)
        val itemType = listProvider.calls().filter { it.name == "<init>" &&
            it.parameterTypes == listOf(STRING, "Ljava/lang/Runnable;") }
            .map { it.definingClass }.distinct().single()
        val title = classDefBy(itemType).fields.one("native menu row title") { it.type == STRING }
        hooks.methods.single { it.name == "rowTitle" }.addInstructions(0, """
            check-cast p0, $itemType
            iget-object v0, p0, $title
            return-object v0
        """.trimIndent())
        val dropDownOwner = listProvider.fields().single { it.definingClass == provider.type && it.type == OBJECT }
        val storageFragment = storage.fields.single { it.type == homeFragment.type }
        provider.afterBrowserMethod(listProvider, """
            instance-of v1, p1, $folder
            if-nez v1, :filter
            instance-of v2, p1, $fileType
            if-eqz v2, :done
            :filter
            iget-object v2, p0, $dropDownOwner
            instance-of v3, v2, ${storage.type}
            if-eqz v3, :done
            check-cast v2, ${storage.type}
            iget-object v2, v2, $storageFragment
            invoke-virtual {v2}, $host
            move-result-object v2
            invoke-static {v0, p1, v1, v2}, $HOOK->filterList($LIST${OBJECT}Z$CONTEXT)$LIST
            move-result-object v0
            :done
        """.trimIndent(), HOOK)

        val drop = unique("folder drag destination registration") {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.size == 8 &&
                it.parameterTypes[0] == VIEW && it.parameterTypes[3] == folder &&
                it.calls().any { call -> call.definingClass == VIEW && call.name == "setOnDragListener" }
        }.let(::mutable)
        check(drop.implementation!!.registerCount > 8) { "SMB roots drop needs a scratch local" }
        drop.addInstructionsWithLabels(0, """
            invoke-static/range {p3 .. p3}, $HOOK->isFolderProto($OBJECT)Z
            move-result v0
            if-eqz v0, :native
            return-void
        """.trimIndent(), ExternalLabel("native", drop.code().first()))

        val preview = unique("preview overflow creator") {
            it.parameterTypes == listOf(MENU, INFLATER) && it.returnType == "V" && it.resource("menu/file_preview_menu")
        }
        val previewPeer = preview.calls().distinctBy { it.toString() }.one("preview peer") {
            it.definingClass == preview.definingClass && it.parameterTypes.isEmpty() &&
                classDefByOrNull(it.returnType)?.methods?.any { method ->
                    method.parameterTypes == listOf("I") && method.string("File preview index is not valid.")
                } == true
        }
        val previewOwner = classDefBy(previewPeer.returnType)
        val indexed = previewOwner.methods.single {
            it.parameterTypes == listOf("I") && it.returnType == fileType && it.string("File preview index is not valid.")
        }
        val currentFile = previewOwner.methods.one("preview current file") {
            it.parameterTypes.isEmpty() && it.returnType == fileType &&
                it.calls().any { call -> call.toString() == indexed.toString() }
        }
        mutableClassDefBy(preview.definingClass).afterBrowserMethod(mutable(preview), """
            invoke-virtual {p0}, $previewPeer
            move-result-object v1
            invoke-virtual {v1}, $currentFile
            move-result-object v1
            const/4 v2, 0x0
            invoke-static {p1, v2, v1}, $HOOK->filterMenu($MENU$OBJECT$OBJECT)V
        """.trimIndent(), HOOK)
    }
}
