package app.filesplus.patches.browse

import app.filesplus.patches.foldertabs.folderTabContextPatch
import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
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
import java.util.WeakHashMap

private const val HOOK = "${Constants.EXT}browse/BrowserHooks;"
private const val MENU = "Landroid/view/Menu;"
private const val INFLATER = "Landroid/view/MenuInflater;"
private const val VIEW = "Landroid/view/View;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private val inflateView = listOf("Landroid/view/LayoutInflater;",
    "Landroid/view/ViewGroup;", "Landroid/os/Bundle;")
@Volatile private var browserIds = emptyMap<String, Long>()

internal fun browserResourceId(name: String): Long = browserIds.getValue(name)

private val browserResources = resourcePatch {
    execute {
        browserIds = document("res/values/public.xml").use { doc ->
            val ids = mutableMapOf<String, Long>()
            val entries = doc.getElementsByTagName("public")
            for (i in 0 until entries.length) {
                val entry = entries.item(i) as Element
                ids[entry.getAttribute("type") + "/" + entry.getAttribute("name")] =
                    entry.getAttribute("id").removePrefix("0x").toLong(16)
            }
            ids
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
private fun <T> Iterable<T>.one(label: String, predicate: (T) -> Boolean): T {
    val hits = filter(predicate)
    check(hits.size == 1) { "Browser hooks $label: expected 1 match, got ${hits.size}" }
    return hits.single().also { println("Browser hooks fingerprint $label: 1 match ($it)") }
}

/** Preserve all input registers across every original return path, including branches directly to return. */
internal fun MutableClass.afterBrowserMethod(target: MutableMethod, body: String, errorHook: String) {
    check(!AccessFlags.STATIC.isSet(target.accessFlags))
    val name = target.name
    val flags = target.accessFlags
    val types = target.parameterTypes.map(CharSequence::toString)
    val words = 1 + types.sumOf { if (it == "J" || it == "D") 2 else 1 }
    check(words + 8 <= 16)
    val renamed = "modKitBrowserOriginal\$$name\$${types.size}"
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
    // addInstructions(index, smali) does not move the snippet's own labels to `index` (a branch to `:done` would
    // land `start` instructions too early and fail ART verification). addInstructionsWithLabels remaps them.
    wrapper.addInstructionsWithLabels(start, body + "\n" + ret)
    val handler = wrapper.code().size
    wrapper.addInstructions(handler, """
        move-exception v1
        invoke-static {v1}, $errorHook->hookError(Ljava/lang/Throwable;)V
        $ret
    """.trimIndent())
    wrapper.implementation!!.apply {
        addCatch("Ljava/lang/Throwable;", newLabelForIndex(start), newLabelForIndex(handler), newLabelForIndex(handler))
    }
    methods.add(wrapper)
}

internal class BrowserBindings(
    val storage: MutableClass,
    val storageFragment: MutableClass,
    val storagePeer: MethodReference,
    val storageFolder: FieldReference,
    val filePopup: MutableMethod,
    val folderType: String,
    val attachStoragePaste: MutableMethod,
    val optionsHelper: MutableMethod,
    val mediaOwner: MutableClass,
    val mediaFragment: FieldReference,
    val rootView: Method,
)

private val bindingsByContext = WeakHashMap<BytecodePatchContext, BrowserBindings>()

internal fun BytecodePatchContext.takeBrowserBindings(): BrowserBindings = synchronized(bindingsByContext) {
    checkNotNull(bindingsByContext[this]) { "Shared browser hooks were not installed" }
}

private fun MutableClass.clipboardBridge(name: String, types: List<String>): MutableMethod {
    val method = MutableMethod(ImmutableMethod(type, name,
        types.map { ImmutableMethodParameter(it, emptySet(), null) }, "V", AccessFlags.PUBLIC.value,
        emptySet(), emptySet(), MutableMethodImplementation(8 + 1 + types.size)))
    method.addInstructions(0, "return-void")
    methods.add(method)
    return method
}

/** Common native browser entry points. No menu or paste controls are added here. */
internal val browserHooksPatch = bytecodePatch {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch, folderTabContextPatch, browserResources)

    execute {
        fun uniqueMethod(label: String, predicate: (Method) -> Boolean): Method {
            val hits = mutableListOf<Method>()
            classDefForEach { it.methods.filterTo(hits, predicate) }
            return hits.one(label) { true }
        }
        val nativeMediaMenu = uniqueMethod("media overflow creator") {
            it.parameterTypes == listOf(MENU) && it.resource("menu/tabbed_fragment_menu_v2")
        }
        val mediaOwner = mutableClassDefBy(nativeMediaMenu.definingClass)
        val fragmentType = mediaOwner.methods.single { it.name == "<init>" }.parameterTypes.first().toString()
        val fragment = mediaOwner.fields.single { it.type == fragmentType }
        val rootView = classDefBy(fragmentType).methods.one("media root view") {
            it.parameterTypes.isEmpty() && it.returnType == VIEW
        }
        val mediaMenu = mediaOwner.methods.single { it.toString() == nativeMediaMenu.toString() }
        mediaOwner.afterBrowserMethod(mediaMenu, """
            iget-object v1, p0, $fragment
            invoke-virtual {v1}, $rootView
            move-result-object v1
            invoke-static {v1, p1}, $HOOK->menu($VIEW$MENU)V
        """.trimIndent(), HOOK)
        val nativeFilePopup = uniqueMethod("storage file popup") {
            it.parameterTypes.size == 3 && it.resource("menu/advanced_browsing_file_dropdown_menu")
        }
        val storage = mutableClassDefBy(nativeFilePopup.definingClass)
        val filePopup = storage.methods.single { it.toString() == nativeFilePopup.toString() }
        val fileType = filePopup.parameterTypes[2].toString()
        // The folder-click handler identifies its target by parameter, independent of unrelated provider casts.
        val folderClick = storage.methods.one("storage folder click target") {
            it.returnType == "Z" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == "Landroid/view/MenuItem;" &&
                it.resource("id/show_folder_info_action")
        }
        val folderType = folderClick.parameterTypes[1].toString()
        check(folderType != fileType) { "Browser hooks folder-click target unexpectedly equals the file type" }

        val nativeOptions = uniqueMethod("storage options selection menu") {
            it.parameterTypes == listOf(MENU, INFLATER) && it.resource("menu/advanced_browsing_action_menu_v2")
        }
        val storageFragment = mutableClassDefBy(nativeOptions.definingClass)
        val options = storageFragment.methods.single { it.toString() == nativeOptions.toString() }
        val peer = options.calls().distinctBy { it.toString() }.single { it.definingClass == storageFragment.type && it.returnType == storage.type &&
            it.parameterTypes.isEmpty() }
        uniqueMethod("storage overflow creator") {
            it.parameterTypes == listOf(MENU, INFLATER) && it.resource("menu/advanced_browsing_top_menu_v2")
        }.also { check(it.toString() == options.toString()) }

        val storageView = uniqueMethod("storage bar view") {
            it.parameterTypes == inflateView && it.returnType == VIEW &&
                it.resource("layout/advanced_browsing_browser_fragment_v2")
        }
        check(storageView.definingClass == storageFragment.type)
        val folder = storageView.currentFolderField(storage.type, folderType)
        val attachStoragePaste = storage.clipboardBridge("modKitAttachStoragePaste", listOf(VIEW, "[B"))
        val optionsHelper = storage.clipboardBridge("modKitClipOptions", listOf(MENU))
        val attach = storageFragment.methods.single { it.toString() == storageView.toString() }
        storageFragment.afterBrowserMethod(attach, """
            invoke-virtual {p0}, $peer
            move-result-object v1
            iget-object v2, v1, $folder
            invoke-static {v2}, $HOOK->folderBytes($OBJECT)[B
            move-result-object v2
            invoke-static {v0, v2}, $HOOK->attachStorage($VIEW[B)V
            invoke-virtual {v1, v0, v2}, $attachStoragePaste
        """.trimIndent(), HOOK)
        storageFragment.afterBrowserMethod(options, """
            invoke-virtual {p0}, $rootView
            move-result-object v1
            invoke-static {v1, p1}, $HOOK->menu($VIEW$MENU)V
            invoke-virtual {p0}, $peer
            move-result-object v1
            invoke-virtual {v1, p1}, $optionsHelper
            iget-object v2, v1, $folder
            const/4 v3, 0x0
            invoke-static {p1, v2, v3}, ${Constants.EXT}smb/SmbRoots;->filterMenu(${MENU}${OBJECT}${OBJECT})V
        """.trimIndent(), HOOK)

        val mediaView = uniqueMethod("media bar view") {
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
        mediaScreen.afterBrowserMethod(mediaScreen.methods.single { it.toString() == mediaView.toString() }, """
            invoke-virtual {p0}, $mediaPeer
            move-result-object v1
            iget-object v1, v1, $category
            invoke-virtual {v1}, Ljava/lang/Enum;->name()$STRING
            move-result-object v1
            invoke-static {v0, v1}, $HOOK->attachMedia($VIEW$STRING)V
        """.trimIndent(), HOOK)

        println("Browser hooks fingerprint media category field: 1 match ($category)")
        synchronized(bindingsByContext) {
            bindingsByContext[this] = BrowserBindings(storage, storageFragment, peer, folder, filePopup, folderType,
                attachStoragePaste, optionsHelper, mediaOwner, fragment, rootView)
        }
    }
}

/** Follow the folder argument to the native top-level drop target back to its field load. */
private fun Method.currentFolderField(owner: String, folderType: String): FieldReference {
    val instructions = code()
    val anchor = instructions.indexOfFirst { (it.ref() as? StringReference)?.string == "OnDropToAdvancedBrowserTopLevel" }
    check(anchor >= 0) { "Browser hooks storage folder drop anchor missing" }
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
    error("Browser hooks storage current folder load missing")
}
