package app.filesplus.patches.clip

import app.filesplus.patches.foldertabs.tabSelectionHookPatch
import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.preview.previewMultiSelectPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.w3c.dom.Element

private const val HOOK = "${Constants.EXT}clip/ClipHooks;"
private const val FACTORY = "${Constants.EXT}clip/ClipMenuFactory;"
private const val MENU = "Landroid/view/Menu;"
private const val INFLATER = "Landroid/view/MenuInflater;"
private const val VIEW = "Landroid/view/View;"
private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"
private const val POPUP = "Landroid/widget/PopupMenu;"
private const val STRING = "Ljava/lang/String;"
private const val MATERIAL_BUTTON = "Lcom/google/android/material/button/MaterialButton;"
private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private val inflateView = listOf("Landroid/view/LayoutInflater;",
    "Landroid/view/ViewGroup;", "Landroid/os/Bundle;")
private val ids = mutableMapOf<String, Long>()

private val clipboardResources = resourcePatch {
    execute {
        ids.clear()
        document("res/values/public.xml").use { doc ->
            val entries = doc.getElementsByTagName("public")
            for (i in 0 until entries.length) {
                val entry = entries.item(i) as Element
                ids[entry.getAttribute("type") + "/" + entry.getAttribute("name")] =
                    entry.getAttribute("id").removePrefix("0x").toLong(16)
            }
        }
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val relay = manifest.createElement("activity")
            relay.setAttribute("android:name", "app.filesplus.extension.clip.PastePickerActivity")
            relay.setAttribute("android:exported", "false")
            relay.setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
            application.appendChild(relay)
        }
    }
}
private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
private fun Method.string(value: String) = code().any { (it.ref() as? StringReference)?.string == value }
private fun Method.resource(value: String) = code().any {
    (it as? WideLiteralInstruction)?.wideLiteral == ids.getValue(value)
}
private fun Method.calls() = code().mapNotNull { it.ref() as? MethodReference }
private fun Method.fields() = code().mapNotNull { it.ref() as? FieldReference }
private fun <T> Iterable<T>.one(label: String, predicate: (T) -> Boolean): T {
    val hits = filter(predicate)
    check(hits.size == 1) { "Clipboard $label: expected 1 match, got ${hits.size}" }
    return hits.single().also { println("Clipboard fingerprint $label: 1 match ($it)") }
}

/** Preserve all input registers across every original return path, including branches directly to return. */
private fun MutableClass.after(target: MutableMethod, body: String) {
    check(!AccessFlags.STATIC.isSet(target.accessFlags))
    val name = target.name
    val flags = target.accessFlags
    val types = target.parameterTypes.map(CharSequence::toString)
    val words = 1 + types.sumOf { if (it == "J" || it == "D") 2 else 1 }
    check(words + 8 <= 16)
    val renamed = "modKitClipOriginal\$$name\$${types.size}"
    check(methods.none { it.name == renamed })
    check(methods.remove(target))
    target.name = renamed
    target.accessFlags = AccessFlags.PRIVATE.value
    methods.add(target)
    val wrapper = MutableMethod(ImmutableMethod(type, name,
        types.map { ImmutableMethodParameter(it, emptySet(), null) }, target.returnType, flags,
        emptySet(), emptySet(), MutableMethodImplementation(words + 8)))
    val (result, ret) = when (target.returnType) {
        "V" -> "" to "return-void"
        "Z" -> "move-result v0" to "return v0"
        else -> "move-result-object v0" to "return-object v0"
    }
    wrapper.addInstructions(0, """
        invoke-direct/range {p0 .. p${words - 1}}, $target
        $result
    """.trimIndent())
    val start = wrapper.code().size
    wrapper.addInstructions(start, body + "\n" + ret)
    val handler = wrapper.code().size
    wrapper.addInstructions(handler, """
        move-exception v1
        invoke-static {v1}, $HOOK->hookError(Ljava/lang/Throwable;)V
        $ret
    """.trimIndent())
    wrapper.implementation!!.apply {
        addCatch("Ljava/lang/Throwable;", newLabelForIndex(start), newLabelForIndex(handler), newLabelForIndex(handler))
    }
    methods.add(wrapper)
}

val clipboardPatch = bytecodePatch(
    name = "Clipboard",
    description = "Cut and paste files from the app's browsers and preview.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch, tabSelectionHookPatch, previewMultiSelectPatch, clipboardResources)

    execute {
        val actionModes = classDefBy { cls -> cls.methods.any {
            it.string("setActionModeTitle should be called when action exists")
        } }
        val updateMenu = actionModes.methods.one("ActionMode menu access") {
            it.string("updateAllMenuItems should be called when action exists")
        }
        val action = updateMenu.fields().distinctBy { it.toString() }.single { field ->
            classDefByOrNull(field.type)?.methods?.any { it.parameterTypes.isEmpty() && it.returnType == MENU } == true
        }
        val getMenu = classDefBy(action.type).methods.one("ActionMode getMenu") {
            it.parameterTypes.isEmpty() && it.returnType == MENU
        }
        fun MutableClass.actionMenu(): String {
            val holder = fields.single { it.type == actionModes.type }
            return """
                iget-object v1, p0, $holder
                iget-object v1, v1, $action
                if-eqz v1, :done
                invoke-virtual {v1}, $getMenu
                move-result-object v1
            """.trimIndent()
        }

        val mediaOwner = mutableClassDefBy(classDefBy { cls -> cls.methods.any {
            it.parameterTypes == listOf(MENU) && it.resource("menu/tabbed_fragment_menu_v2")
        } })
        val capture = mediaOwner.methods.one("hidden media selection capture") { it.name == "modKitCaptureSelection" }
        val fragmentType = mediaOwner.methods.single { it.name == "<init>" }.parameterTypes.first().toString()
        val fragment = mediaOwner.fields.single { it.type == fragmentType }
        val rootView = classDefBy(fragmentType).methods.one("media root view") {
            it.parameterTypes.isEmpty() && it.returnType == VIEW
        }
        val mediaMenu = mediaOwner.methods.one("media overflow creator") {
            it.parameterTypes == listOf(MENU) && it.resource("menu/tabbed_fragment_menu_v2")
        }
        mediaOwner.after(mediaMenu, """
            iget-object v1, p0, $fragment
            invoke-virtual {v1}, $rootView
            move-result-object v1
            invoke-static {v1, p1}, $HOOK->pasteMenu($VIEW$MENU)V
        """.trimIndent())
        mediaOwner.after(capture, """
            ${mediaOwner.actionMenu()}
            iget-object v2, p0, $fragment
            invoke-virtual {v2}, $rootView
            move-result-object v2
            invoke-static {v2, v1}, $HOOK->media($VIEW$MENU)V
            :done
        """.trimIndent())

        val storage = mutableClassDefBy(classDefBy { cls -> cls.methods.any {
            it.parameterTypes.size == 3 && it.resource("menu/advanced_browsing_file_dropdown_menu")
        } })
        val filePopup = storage.methods.one("storage file popup") {
            it.parameterTypes.size == 3 && it.resource("menu/advanced_browsing_file_dropdown_menu")
        }
        val fileType = filePopup.parameterTypes[2].toString()
        val folderPopup = storage.methods.one("storage folder popup anchor") {
            it.parameterTypes == listOf(INFLATER, MENU) && it.resource("menu/advanced_browsing_folder_dropdown_menu")
        }
        // The folder-click handler identifies its target by parameter, independent of unrelated provider casts.
        val folderClick = storage.methods.one("storage folder click target") {
            it.returnType == "Z" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == "Landroid/view/MenuItem;" &&
                it.resource("id/show_folder_info_action")
        }
        val folderType = folderClick.parameterTypes[1].toString()
        check(folderType != fileType) { "Clipboard folder-click target unexpectedly equals the file type" }

        filePopup.let { storage.after(it, """
            invoke-static {p3}, $HOOK->bytes($OBJECT)[B
            move-result-object v1
            const/4 v2, 0x0
            invoke-static {p2, v1, v2}, $HOOK->popup($MENU[BZ)V
        """.trimIndent()) }

        val visibility = storage.methods.one("storage selection visibility") {
            it.parameterTypes.isEmpty() && it.returnType == "V" &&
                it.resource("id/move_to_action") && it.resource("id/copy_to_action") &&
                it.calls().any { call -> call.definingClass == actionModes.type }
        }
        val selection = storage.fields.single { field -> classDefByOrNull(field.type)?.methods?.any {
            it.string("SINGLE_FOLDER_SELECTION_SET")
        } == true }
        val selectionGetter = classDefBy(selection.type).methods.one("storage selection proto") {
            it.parameterTypes.isEmpty() && it.returnType.startsWith("L") &&
                it.code().any { instruction -> instruction.opcode == Opcode.CHECK_CAST } &&
                it.calls().any { call -> call.parameterTypes.isEmpty() && call.returnType == "Ljava/util/Collection;" }
        }
        val loadSelection = """
            iget-object v2, p0, $selection
            invoke-virtual {v2}, $selectionGetter
            move-result-object v2
            invoke-static {v2}, $HOOK->bytes($OBJECT)[B
            move-result-object v2
        """.trimIndent()
        storage.after(visibility, """
            ${storage.actionMenu()}
            $loadSelection
            invoke-static {v1, v2}, $HOOK->storage($MENU[B)V
            :done
        """.trimIndent())

        val storageFragment = mutableClassDefBy(classDefBy { cls -> cls.methods.any {
            it.parameterTypes == listOf(MENU, INFLATER) && it.resource("menu/advanced_browsing_action_menu_v2")
        } })
        val options = storageFragment.methods.one("storage options selection menu") {
            it.parameterTypes == listOf(MENU, INFLATER) && it.resource("menu/advanced_browsing_action_menu_v2")
        }
        val peer = options.calls().first { it.definingClass == storageFragment.type && it.returnType == storage.type &&
            it.parameterTypes.isEmpty() }
        storageFragment.methods.one("storage overflow creator") {
            it.parameterTypes == listOf(MENU, INFLATER) && it.resource("menu/advanced_browsing_top_menu_v2")
        }.also { check(it == options) }

        val storageView = storageFragment.methods.one("storage bar view") {
            it.parameterTypes == inflateView && it.returnType == VIEW &&
                it.resource("layout/advanced_browsing_browser_fragment_v2")
        }
        val pickerView = classDefBy { cls -> cls.methods.any {
            it.parameterTypes == inflateView && it.resource("layout/advanced_browsing_picker_fragment")
        } }.methods.one("native picker view") {
            it.parameterTypes == inflateView && it.returnType == VIEW &&
                it.resource("layout/advanced_browsing_picker_fragment")
        }
        check(pickerView.definingClass != storageView.definingClass)
        val iconSetter = pickerView.calls().distinctBy { it.toString() }.one("native picker button icon") {
            it.definingClass == MATERIAL_BUTTON && it.parameterTypes == listOf(DRAWABLE) && it.returnType == "V"
        }
        // The fragment's peer reads the last folder from its own arguments. The drop target uses that same field.
        val folder = storageView.currentFolderField(storage.type, folderType)
        val fab = storage.methods.one("storage FAB variant") {
            it.parameterTypes == listOf(VIEW, "I") && it.resource("id/create_new_folder_expressive") &&
                it.resource("id/create_new_folder")
        }
        val fabFlag = fab.code().single { it.opcode == Opcode.IGET_BOOLEAN }.ref() as FieldReference
        val flagHolder = fab.fields().single { it.definingClass == storage.type && it.type == fabFlag.definingClass }
        storageFragment.after(storageView, """
            invoke-virtual {p0}, $peer
            move-result-object v1
            iget-object v2, v1, $folder
            invoke-static {v2}, $HOOK->bytes($OBJECT)[B
            move-result-object v2
            iget-object v1, v1, $flagHolder
            iget-boolean v1, v1, $fabFlag
            invoke-static {v0, v2, v1}, $HOOK->attachStoragePaste($VIEW[BZ)V
        """.trimIndent())

        val mediaView = classDefBy { cls -> cls.methods.any {
            it.parameterTypes == inflateView && it.resource("layout/tabbed_file_browser_fragment_v3")
        } }.methods.one("media bar view") {
            it.parameterTypes == inflateView && it.returnType == VIEW && it.resource("layout/tabbed_file_browser_fragment_v3")
        }
        val mediaScreen = mutableClassDefBy(mediaView.definingClass)
        val categoryType = mediaOwner.fields.map { it.type }.distinct().single { type ->
            type.startsWith("L") && classDefByOrNull(type)?.methods?.any {
                it.name == "<clinit>" && it.string("CATEGORY_IMAGE") && it.string("CATEGORY_DOWNLOAD")
            } == true
        }
        val mediaPeer = mediaView.calls().distinctBy { it.toString() }.single {
            it.definingClass == mediaScreen.type && it.parameterTypes.isEmpty() &&
                classDefByOrNull(it.returnType)?.fields?.any { f -> f.type == categoryType } == true
        }
        val category = classDefBy(mediaPeer.returnType).fields.single { it.type == categoryType }
        mediaScreen.after(mediaScreen.methods.single { it.toString() == mediaView.toString() }, """
            invoke-virtual {p0}, $mediaPeer
            move-result-object v1
            iget-object v1, v1, $category
            invoke-virtual {v1}, Ljava/lang/Enum;->name()$STRING
            move-result-object v1
            invoke-static {v0, v1}, $HOOK->attachMediaPaste($VIEW$STRING)V
        """.trimIndent())

        val flagOwner = mutableClassDefBy(classDefBy { cls -> cls.methods.any {
            it.returnType == "Z" && it.parameterTypes.isEmpty() && it.string("45676054") &&
                it.string("com.google.android.apps.nbu.files.device")
        } })
        val readFlag = flagOwner.methods.one("media paste bar phenotype reader") {
            it.returnType == "Z" && it.parameterTypes.isEmpty() && it.string("45676054") &&
                it.string("com.google.android.apps.nbu.files.device")
        }
        flagOwner.after(readFlag, "invoke-static {v0}, $HOOK->pasteBarFlag(Z)V")
        val optionsHelper = MutableMethod(ImmutableMethod(storage.type, "modKitClipOptions",
            listOf(ImmutableMethodParameter(MENU, emptySet(), null)), "V", AccessFlags.PUBLIC.value,
            emptySet(), emptySet(), MutableMethodImplementation(10)))
        optionsHelper.addInstructions(0, """
            $loadSelection
            invoke-static {p1, v2}, $HOOK->storage($MENU[B)V
            return-void
        """.trimIndent())
        val handler = optionsHelper.code().size
        optionsHelper.addInstructions(handler, """
            move-exception v1
            invoke-static {v1}, $HOOK->hookError(Ljava/lang/Throwable;)V
            return-void
        """.trimIndent())
        optionsHelper.implementation!!.apply {
            addCatch("Ljava/lang/Throwable;", newLabelForIndex(0), newLabelForIndex(handler), newLabelForIndex(handler))
        }
        storage.methods.add(optionsHelper)
        storageFragment.after(options, """
            invoke-virtual {p0}, $rootView
            move-result-object v1
            invoke-static {v1, p1}, $HOOK->pasteMenu($VIEW$MENU)V
            const v1, ${ids.getValue("id/move_to_action")}
            invoke-interface {p1, v1}, $MENU->findItem(I)Landroid/view/MenuItem;
            move-result-object v1
            if-eqz v1, :done
            invoke-virtual {p0}, $peer
            move-result-object v1
            invoke-virtual {v1, p1}, $optionsHelper
            :done
        """.trimIndent())

        val contextMenu = storageFragment.methods.one("storage folder long press") {
            it.name == "onCreateContextMenu" && it.calls().any { call -> call.toString() == folderPopup.toString() }
        }
        val folderGuard = contextMenu.code().one("storage folder dropdown type guard") {
            it.opcode == Opcode.INSTANCE_OF && (it.ref() as? TypeReference)?.type != fileType
        }
        val guardedFolderType = (folderGuard.ref() as TypeReference).type
        check(folderType == guardedFolderType) {
            "Clipboard folder type mismatch: click target $folderType, folder dropdown guard $guardedFolderType"
        }
        val contextFields = contextMenu.fields().filter { it.definingClass == storage.type }.distinctBy { it.toString() }
        val contextIndex = contextFields.single { it.type == "Ljava/util/concurrent/atomic/AtomicInteger;" }
        val contextEntries = contextFields.single { it.type == LIST }
        storageFragment.after(contextMenu, """
            const v1, ${ids.getValue("id/show_folder_info_action")}
            invoke-interface {p1, v1}, $MENU->findItem(I)Landroid/view/MenuItem;
            move-result-object v1
            if-eqz v1, :done
            invoke-virtual {p0}, $peer
            move-result-object v1
            iget-object v2, v1, $contextIndex
            invoke-virtual {v2}, Ljava/util/concurrent/atomic/AtomicInteger;->get()I
            move-result v2
            iget-object v1, v1, $contextEntries
            invoke-interface {v1, v2}, $LIST->get(I)$OBJECT
            move-result-object v1
            instance-of v2, v1, $folderType
            if-eqz v2, :done
            invoke-static {v1}, $HOOK->bytes($OBJECT)[B
            move-result-object v1
            const/4 v2, 0x1
            invoke-static {p1, v1, v2}, $HOOK->popup($MENU[BZ)V
            :done
        """.trimIndent())

        val mediaPopupOwner = mutableClassDefBy(classDefBy { cls -> cls.methods.any {
            it.parameterTypes == listOf(INFLATER, MENU, fileType) && it.resource("menu/file_browser_list_item_popup_menu")
        } })
        val mediaPopup = mediaPopupOwner.methods.one("media file popup") {
            it.parameterTypes == listOf(INFLATER, MENU, fileType) && it.resource("menu/file_browser_list_item_popup_menu")
        }
        mediaPopupOwner.after(mediaPopup, """
            invoke-static {p3}, $HOOK->bytes($OBJECT)[B
            move-result-object v1
            const/4 v2, 0x0
            invoke-static {p2, v1, v2}, $HOOK->popup($MENU[BZ)V
        """.trimIndent())

        for ((label, anchor) in listOf("storage" to "string/file_browser_menu_item_folder_info",
                "media" to "string/menu_item_copy_to")) {
            val provider = mutableClassDefBy(classDefBy { cls -> cls.methods.any {
                it.parameterTypes == listOf(OBJECT) && it.returnType == LIST && it.resource(anchor) &&
                    it.resource("string/file_browser_backup_to_google_drive")
            } })
            val listMethod = provider.methods.one("$label menu list") {
                it.parameterTypes == listOf(OBJECT) && it.returnType == LIST && it.resource(anchor) &&
                    it.resource("string/file_browser_backup_to_google_drive")
            }
            val ctor = listMethod.calls().filter { it.name == "<init>" &&
                it.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/Runnable;") }
                .distinctBy { it.toString() }.single()
            provider.interfaces.add(FACTORY)
            val factory = MutableMethod(ImmutableMethod(provider.type, "clipMenuRow",
                listOf("Ljava/lang/String;", "Ljava/lang/Runnable;").map { ImmutableMethodParameter(it, emptySet(), null) },
                OBJECT, AccessFlags.PUBLIC.value, emptySet(), emptySet(), MutableMethodImplementation(4)))
            factory.addInstructions(0, """
                new-instance v0, ${ctor.definingClass}
                invoke-direct {v0, p1, p2}, $ctor
                return-object v0
            """.trimIndent())
            provider.methods.add(factory)
            val mode = if (label == "storage") {
                val discriminator = provider.fields.single { it.type == "I" }
                """
                    iget v1, p0, $discriminator
                    const/4 v2, 0x1
                    if-gt v1, v2, :done
                """.trimIndent()
            } else ""
            provider.after(listMethod, """
                $mode
                ${if (label == "media") "const/4 v2, 0x1\ninvoke-static {v2}, $HOOK->observedPasteBarFlag(Z)V" else ""}
                instance-of v2, p1, $folderType
                invoke-static {p1}, $HOOK->bytes($OBJECT)[B
                move-result-object v1
                invoke-static {v0, v1, v2, p0}, $HOOK->list($LIST[BZ$FACTORY)$LIST
                move-result-object v0
                :done
            """.trimIndent())
            if (label == "storage") {
                val popupMethod = provider.methods.one("storage folder popup provider") {
                    it.parameterTypes == listOf(POPUP, OBJECT) &&
                        it.returnType == "Landroid/widget/PopupMenu\$OnMenuItemClickListener;"
                }
                val discriminator = provider.fields.single { it.type == "I" }
                provider.after(popupMethod, """
                    iget v1, p0, $discriminator
                    if-nez v1, :done
                    invoke-virtual {p1}, $POPUP->getMenu()$MENU
                    move-result-object v1
                    invoke-static {p2}, $HOOK->bytes($OBJECT)[B
                    move-result-object v2
                    const/4 v3, 0x1
                    invoke-static {v1, v2, v3}, $HOOK->popup($MENU[BZ)V
                    :done
                """.trimIndent())
            } else {
                val popupMethod = provider.methods.one("media popup variant observer") {
                    it.parameterTypes == listOf(POPUP, OBJECT) &&
                        it.returnType == "Landroid/widget/PopupMenu\$OnMenuItemClickListener;"
                }
                provider.after(popupMethod, """
                    invoke-virtual {p1}, $POPUP->getMenu()$MENU
                    move-result-object v1
                    const v2, ${ids.getValue("id/popup_copy_to")}
                    invoke-interface {v1, v2}, $MENU->findItem(I)Landroid/view/MenuItem;
                    move-result-object v1
                    if-eqz v1, :done
                    const/4 v1, 0x0
                    invoke-static {v1}, $HOOK->observedPasteBarFlag(Z)V
                    :done
                """.trimIndent())
            }
        }
        val hooks = mutableClassDefBy(HOOK)
        hooks.methods.single { it.name == "setPasteIcon" }.apply {
            addInstructions(0, """
                check-cast p0, $MATERIAL_BUTTON
                invoke-virtual/range {p0 .. p1}, $iconSetter
                const/4 v0, 0x1
                return v0
                move-exception v0
                invoke-static {v0}, $HOOK->hookError(Ljava/lang/Throwable;)V
                const/4 v0, 0x0
                return v0
            """.trimIndent())
            implementation!!.apply {
                addCatch("Ljava/lang/Throwable;", newLabelForIndex(0), newLabelForIndex(4), newLabelForIndex(4))
            }
        }
        hooks.methods.single { it.name == "installed" }.apply {
            addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
        hooks.methods.single { it.name == "installedHooks" }.apply {
            addInstructions(0, """
                const-string v0, "Installed: media selection; storage selection/options; storage file/folder popup + long press; media popup; storage/media lists; preview; storage/media paste bars and overflow; storage current folder; media bar flag; picker relay"
                return-object v0
            """.trimIndent())
        }
    }
}

/** Follow the folder argument to the native top-level drop target back to its field load. */
private fun Method.currentFolderField(owner: String, folderType: String): FieldReference {
    val instructions = code()
    val anchor = instructions.indexOfFirst { (it.ref() as? StringReference)?.string == "OnDropToAdvancedBrowserTopLevel" }
    check(anchor >= 0) { "Clipboard storage folder drop anchor missing" }
    val callAt = (anchor + 1 until instructions.size).first { index ->
        val call = instructions[index].ref() as? MethodReference
        call != null && call.parameterTypes.firstOrNull() == VIEW && folderType in call.parameterTypes &&
            STRING in call.parameterTypes && call.returnType == "V"
    }
    val invoke = instructions[callAt] as RegisterRangeInstruction
    val reference = instructions[callAt].ref() as MethodReference
    check(instructions[callAt].opcode == Opcode.INVOKE_STATIC_RANGE)
    val argument = reference.parameterTypes.indexOf(folderType)
    check(reference.parameterTypes.take(argument).all { it != "J" && it != "D" })
    var register = invoke.startRegister + argument
    for (index in callAt - 1 downTo 0) {
        val instruction = instructions[index]
        if ((instruction as? OneRegisterInstruction)?.registerA != register) continue
        if (instruction.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)) {
            register = (instruction as TwoRegisterInstruction).registerB
        } else if (instruction.opcode == Opcode.IGET_OBJECT) {
            val field = instruction.ref() as FieldReference
            check(field.definingClass == owner && field.type == folderType)
            return listOf(field).one("storage current folder field") { it == field }
        }
    }
    error("Clipboard storage current folder load missing")
}
