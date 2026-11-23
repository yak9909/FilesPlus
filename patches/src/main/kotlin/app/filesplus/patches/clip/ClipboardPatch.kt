package app.filesplus.patches.clip

import app.filesplus.patches.browse.afterBrowserMethod
import app.filesplus.patches.browse.browserResourceId
import app.filesplus.patches.browse.takeBrowserBindings
import app.filesplus.patches.browse.browserHooksPatch
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
private const val MATERIAL_BUTTON = "Lcom/google/android/material/button/MaterialButton;"
private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private val inflateView = listOf("Landroid/view/LayoutInflater;",
    "Landroid/view/ViewGroup;", "Landroid/os/Bundle;")

private val clipboardResources = resourcePatch {
    execute {
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
    (it as? WideLiteralInstruction)?.wideLiteral == browserResourceId(value)
}
private fun Method.calls() = code().mapNotNull { it.ref() as? MethodReference }
private fun Method.fields() = code().mapNotNull { it.ref() as? FieldReference }
private fun <T> Iterable<T>.one(label: String, predicate: (T) -> Boolean): T {
    val hits = filter(predicate)
    check(hits.size == 1) { "Clipboard $label: expected 1 match, got ${hits.size}" }
    return hits.single().also { println("Clipboard fingerprint $label: 1 match ($it)") }
}

private fun MutableClass.after(target: MutableMethod, body: String) = afterBrowserMethod(target, body, HOOK)

private fun MutableMethod.fillClipboardBridge(body: String) {
    addInstructions(0, body.trimIndent() + "\nreturn-void")
    val handler = code().lastIndex
    addInstructions(handler, """
        move-exception v1
        invoke-static {v1}, $HOOK->hookError(Ljava/lang/Throwable;)V
        return-void
    """.trimIndent())
    implementation!!.apply {
        addCatch("Ljava/lang/Throwable;", newLabelForIndex(0), newLabelForIndex(handler), newLabelForIndex(handler))
    }
}

val clipboardPatch = bytecodePatch(
    name = "Clipboard",
    description = "Cut and paste files from the app's browsers and preview.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch, tabSelectionHookPatch, browserHooksPatch, previewMultiSelectPatch, clipboardResources)

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

        val bindings = takeBrowserBindings()
        val mediaOwner = bindings.mediaOwner
        val fragment = bindings.mediaFragment
        val rootView = bindings.rootView
        val capture = mediaOwner.methods.one("hidden media selection capture") { it.name == "modKitCaptureSelection" }
        mediaOwner.after(capture, """
            ${mediaOwner.actionMenu()}
            iget-object v2, p0, $fragment
            invoke-virtual {v2}, $rootView
            move-result-object v2
            invoke-static {v2, v1}, $HOOK->media($VIEW$MENU)V
            :done
        """.trimIndent())

        val storage = bindings.storage
        val filePopup = bindings.filePopup
        val fileType = filePopup.parameterTypes[2].toString()
        val folderType = bindings.folderType
        val folderPopup = storage.methods.one("storage folder popup anchor") {
            it.parameterTypes == listOf(INFLATER, MENU) && it.resource("menu/advanced_browsing_folder_dropdown_menu")
        }
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

        val storageFragment = bindings.storageFragment
        val peer = bindings.storagePeer
        val pickerView = classDefBy { cls -> cls.methods.any {
            it.parameterTypes == inflateView && it.resource("layout/advanced_browsing_picker_fragment")
        } }.methods.one("native picker view") {
            it.parameterTypes == inflateView && it.returnType == VIEW &&
                it.resource("layout/advanced_browsing_picker_fragment")
        }
        check(pickerView.definingClass != storageFragment.type)
        val iconSetter = pickerView.calls().distinctBy { it.toString() }.one("native picker button icon") {
            it.definingClass == MATERIAL_BUTTON && it.parameterTypes == listOf(DRAWABLE) && it.returnType == "V"
        }
        val fab = storage.methods.one("storage FAB variant") {
            it.parameterTypes == listOf(VIEW, "I") && it.resource("id/create_new_folder_expressive") &&
                it.resource("id/create_new_folder")
        }
        val fabFlag = fab.code().single { it.opcode == Opcode.IGET_BOOLEAN }.ref() as FieldReference
        val flagHolder = fab.fields().single { it.definingClass == storage.type && it.type == fabFlag.definingClass }
        bindings.attachStoragePaste.fillClipboardBridge("""
            iget-object v1, p0, $flagHolder
            iget-boolean v1, v1, $fabFlag
            invoke-static {p1, p2, v1}, $HOOK->attachStoragePaste($VIEW[BZ)V
        """)

        val flagOwner = mutableClassDefBy(classDefBy { cls -> cls.methods.any {
            it.returnType == "Z" && it.parameterTypes.isEmpty() && it.string("45676054") &&
                it.string("com.google.android.apps.nbu.files.device")
        } })
        val readFlag = flagOwner.methods.one("media paste bar phenotype reader") {
            it.returnType == "Z" && it.parameterTypes.isEmpty() && it.string("45676054") &&
                it.string("com.google.android.apps.nbu.files.device")
        }
        flagOwner.after(readFlag, "invoke-static {v0}, $HOOK->pasteBarFlag(Z)V")
        bindings.optionsHelper.fillClipboardBridge("""
            const v1, ${browserResourceId("id/move_to_action")}
            invoke-interface {p1, v1}, $MENU->findItem(I)Landroid/view/MenuItem;
            move-result-object v1
            if-eqz v1, :done
            $loadSelection
            invoke-static {p1, v2}, $HOOK->storage($MENU[B)V
            :done
        """)

        // When SMB runs first, the resource-bearing body is private and the caller invokes its wrapper.
        val folderPopupEntry = storage.methods.one("storage folder popup entry") { method ->
            if (AccessFlags.PRIVATE.isSet(folderPopup.accessFlags)) {
                method.parameterTypes == folderPopup.parameterTypes && method.returnType == folderPopup.returnType &&
                    method.calls().any { call -> call.toString() == folderPopup.toString() }
            } else method == folderPopup
        }
        val contextMenu = storageFragment.methods.one("storage folder long press") {
            it.name == "onCreateContextMenu" && it.calls().any { call -> call.toString() == folderPopupEntry.toString() }
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
            const v1, ${browserResourceId("id/show_folder_info_action")}
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
                    const v2, ${browserResourceId("id/popup_copy_to")}
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
