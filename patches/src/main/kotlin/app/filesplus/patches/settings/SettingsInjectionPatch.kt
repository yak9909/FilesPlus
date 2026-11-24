package app.filesplus.patches.settings

import app.filesplus.patches.browse.afterBrowserMethod
import app.filesplus.patches.foldertabs.tabSelectionHookPatch
import app.filesplus.patches.misc.modKitIntegrationPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val PREF = "Landroidx/preference/Preference;"
private const val CATEGORY = "Landroidx/preference/PreferenceCategory;"
private const val SCREEN = "Landroidx/preference/PreferenceScreen;"
private const val SWITCH = "Lcom/google/android/apps/nbu/files/settings/MaterialSwitchPreference;"
private const val ACTIVITY = "Lcom/google/android/apps/nbu/files/settings/SettingsActivity;"
private const val CTX = "Landroid/content/Context;"
private const val OBJ = "Ljava/lang/Object;"
private const val TEXT = "Ljava/lang/CharSequence;"
private const val VIEW = "Landroid/view/View;"
private const val DIALOG = "Landroid/app/Dialog;"
private const val CLICK = "Landroid/content/DialogInterface\$OnClickListener;"
private const val BRIDGE = "${Constants.EXT}settings/PrefBridge;"
private const val SMB_SWIPE = "${Constants.EXT}settings/SmbSwipe;"
private const val PAGES = "${Constants.EXT}settings/SettingsPages;"

private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
private fun Method.calls() = code().mapNotNull { it.ref() as? MethodReference }
private fun Method.hasString(value: String) = code().any { (it.ref() as? StringReference)?.string == value }
private fun List<MethodReference>.unique(label: String) = distinctBy { it.toString() }.also {
    check(it.size == 1) { "Settings: expected one $label, found $it" }
}.single()

/**
 * SettingsActivity is declared singleTask, so a sub-page launch would only reach onNewIntent of the running
 * instance and never show its extra. Dropping the launch mode makes it a standard activity (it is not exported,
 * so the only side effect is that duplicate settings instances become possible).
 */
private val settingsLaunchModePatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val activities = manifest.getElementsByTagName("activity")
            val settings = (0 until activities.length).map { activities.item(it) as Element }.singleOrNull {
                it.getAttribute("android:name") in setOf(
                    "${Constants.ORIGINAL_PACKAGE}.settings.SettingsActivity", ".settings.SettingsActivity",
                )
            }
            check(settings != null) { "Settings: SettingsActivity is not declared in the manifest" }
            settings.removeAttribute("android:launchMode")
            check(settings.getAttribute("android:launchMode") !in setOf("singleTask", "singleInstance", "singleTop")) {
                "Settings: SettingsActivity is still not a standard activity"
            }
        }
    }
}

/** Preferences, listeners and Material dialogs are the app's own objects, reached through generated methods. */
val settingsInjectionPatch = bytecodePatch(
    name = "FilesPlus settings",
    description = "Put FilesPlus settings and diagnostics in the app's original settings screens.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch, tabSelectionHookPatch, settingsLaunchModePatch)

    execute {
        fun isA(type: String, parent: String): Boolean {
            var current: String? = type
            while (current != null) {
                if (current == parent) return true
                current = classDefByOrNull(current)?.superclass
            }
            return false
        }
        fun definition(ref: MethodReference): Method {
            var type: String? = ref.definingClass
            while (type != null) {
                val cls = classDefBy(type)
                cls.methods.singleOrNull {
                    it.name == ref.name && it.parameterTypes == ref.parameterTypes && it.returnType == ref.returnType
                }?.let { return it }
                type = cls.superclass
            }
            error("Settings: method definition missing: $ref")
        }

        val candidates = mutableListOf<Method>()
        classDefForEach { cls -> cls.methods.filterTo(candidates) {
            it.parameterTypes.isEmpty() && it.returnType == "V" &&
                it.hasString("ABOUT_FILES_PREF_KEY") && it.hasString("OTHER_PREF_CATEGORY_KEY") &&
                it.hasString("LANGUAGE_PREF_KEY")
        } }
        check(candidates.size == 1) { "Settings: ambiguous settings builder: ${candidates.size}" }
        val owner = mutableClassDefBy(candidates.single().definingClass)
        val build = owner.methods.single { it.toString() == candidates.single().toString() }
        val activity = mutableClassDefBy(ACTIVITY)
        val onCreate = activity.methods.single { it.name == "onCreate" && it.parameterTypes == listOf("Landroid/os/Bundle;") }
        check(onCreate.code().any { it.opcode == Opcode.NEW_INSTANCE && (it.ref() as? TypeReference)?.type == owner.type }) {
            "Settings: builder is not the fragment created by SettingsActivity"
        }
        val code = build.code()
        fun keyIndex(key: String) = code.indices.single { (code[it].ref() as? StringReference)?.string == key }
        fun keyCallOf(key: String): Int = (keyIndex(key) + 1 until code.size).first { index ->
            val ref = code[index].ref() as? MethodReference
            ref != null && isA(ref.definingClass, PREF) &&
                ref.parameterTypes == listOf("Ljava/lang/String;") && ref.returnType == "V"
        }
        fun item(key: String): Pair<Int, Int> {
            val keyCall = keyCallOf(key)
            val register = (code[keyCall] as Instruction35c).registerC
            val end = (keyCall + 1 until code.size).first { index ->
                val call = code[index] as? Instruction35c
                val ref = call?.reference as? MethodReference
                call?.opcode == Opcode.INVOKE_VIRTUAL && call.registerCount == 2 && call.registerD == register &&
                    ref?.parameterTypes == listOf(PREF) && ref.returnType in setOf("V", "Z") &&
                    isA(ref.definingClass, PREF)
            }
            return keyCall to end
        }
        val (aboutKey, aboutEnd) = item("ABOUT_FILES_PREF_KEY")
        val aboutRegister = (code[aboutKey] as Instruction35c).registerC
        fun itemCalls(start: Int, end: Int) = code.subList(start, end).mapNotNull { it.ref() as? MethodReference }
            .filter { isA(it.definingClass, PREF) }
        val about = itemCalls(aboutKey, aboutEnd)
        val key = listOf(code[aboutKey].ref() as MethodReference).unique("key setter")
        val title = about.filter { it.parameterTypes == listOf(TEXT) && it.returnType == "V" }.unique("title setter")
        val icon = about.filter { it.parameterTypes == listOf("I") && it.returnType == "V" }.unique("icon setter")
        val add = definition(code[aboutEnd].ref() as MethodReference)
        val beforeAbout = code.subList(0, aboutKey).indexOfLast {
            (it.ref() as? MethodReference)?.let { ref -> ref.definingClass == PREF && ref.name == "<init>" } == true
        }
        check(beforeAbout >= 0)
        val prepare = code.subList(beforeAbout + 1, aboutKey).mapNotNull { it.ref() as? MethodReference }
            .filter { isA(it.definingClass, PREF) && it.parameterTypes.isEmpty() && it.returnType == "V" }
            .unique("disable persistence")
        val (summaryStart, summaryEnd) = item("CLEAR_SEARCH_HISTORY_PREF_KEY")
        val summary = itemCalls(summaryStart, summaryEnd).filter {
            it.parameterTypes == listOf(TEXT) && it.returnType == "V" && it.name != title.name
        }.unique("summary setter")
        val (categoryStart, categoryEnd) = item("OTHER_PREF_CATEGORY_KEY")
        val categoryStyle = itemCalls(categoryStart, categoryEnd).filter {
            it.parameterTypes == listOf("Z") && it.returnType == "V"
        }.unique("category style")
        // The theme switch is the only MaterialSwitchPreference the builder initialises with setChecked(boolean).
        // R8 may have rebound the reference to a superclass, so the receiver (the register of the key call) decides.
        val themeKey = keyCallOf("THEME_CHANGE_PREF_KEY")
        val themeRegister = (code[themeKey] as Instruction35c).registerC
        val checked = code.subList(themeKey + 1, minOf(code.size, themeKey + 40)).mapNotNull {
            val call = it as? Instruction35c
            val ref = call?.reference as? MethodReference
            if (call != null && call.opcode == Opcode.INVOKE_VIRTUAL && call.registerCount == 2 &&
                call.registerC == themeRegister && ref!!.parameterTypes == listOf("Z") && ref.returnType == "V" &&
                isA(SWITCH, ref.definingClass)) ref else null
        }.unique("switch checked setter")
        // Field references come from the settings builder; interface descriptors and callbacks are validated.
        fun listener(parameters: List<String>): FieldReference {
            val fields = code.filter { it.opcode == Opcode.IPUT_OBJECT }.mapNotNull { it.ref() as? FieldReference }
                .distinctBy { it.toString() }.filter { field ->
                    isA(field.definingClass, PREF) && classDefByOrNull(field.type)?.let { cls ->
                        AccessFlags.INTERFACE.isSet(cls.accessFlags) && cls.methods.count() == 1 &&
                            cls.methods.single().parameterTypes == parameters && cls.methods.single().returnType == "V"
                    } == true
                }
            check(fields.size == 1) { "Settings: ambiguous listener field for $parameters: $fields" }
            return fields.single()
        }
        val click = listener(listOf(PREF))
        val change = listener(listOf(PREF, OBJ))
        val screenFactory = build.calls().filter { it.parameterTypes == listOf(CTX) && it.returnType == SCREEN }
            .unique("screen factory")
        val managerField = classDefBy(PREF).fields.single { it.type == screenFactory.definingClass }
        val contextField = classDefBy(PREF).fields.single { it.type == CTX }
        val host = build.calls().filter { it.parameterTypes.isEmpty() && isA(it.returnType, "Landroid/app/Activity;") }
            .unique("fragment activity accessor")
        val finalCalls = code.withIndex().filter { (_, instruction) ->
            val ref = instruction.ref() as? MethodReference
            instruction.opcode == Opcode.INVOKE_VIRTUAL && ref?.parameterTypes == listOf(SCREEN) && ref.returnType == "V"
        }
        check(finalCalls.size == 1 && finalCalls.single().index == code.indexOfLast { it.opcode != Opcode.RETURN_VOID }) {
            "Settings: final setPreferenceScreen call changed"
        }
        val finalCall = finalCalls.single()
        val install = finalCall.value.ref() as MethodReference

        // Find the shared dialog peer by a stable validation message, then follow its fragment field.
        val dialogPeer = classDefBy { cls -> cls.methods.any {
            it.hasString("DialogConfig should either set \"sub_title\" (string) or \"subtitle_res_id\" (int) or none, but never both. ")
        } }
        val dialogCreators = dialogPeer.fields.mapNotNull { classDefByOrNull(it.type) }.flatMap { cls ->
            cls.methods.filter { it.parameterTypes == listOf("Landroid/os/Bundle;") && it.returnType == DIALOG }
        }
        check(dialogCreators.size == 1) { "Settings: ambiguous shared dialog fragment" }
        val dialogCreator = dialogCreators.single()
        val constructors = dialogCreator.calls().filter {
            it.name == "<init>" && it.parameterTypes == listOf(CTX, "I") &&
                classDefBy(it.definingClass).methods.any { method ->
                    method.name == "create" && isA(method.returnType, DIALOG)
                }
        }
        val constructor = constructors.unique("Material dialog constructor")
        val dialogType = constructor.definingClass
        val dialogCalls = dialogCreator.calls().filter { it.definingClass == dialogType }
        val dialogTitle = dialogCalls.filter { ref ->
            ref.parameterTypes == listOf(TEXT) && definition(ref).calls().any {
                it.name == "setTitle" && it.parameterTypes == listOf(TEXT)
            }
        }.unique("Material dialog title")
        val dialogView = dialogCalls.filter { ref ->
            ref.parameterTypes == listOf(VIEW) && definition(ref).calls().any {
                it.name == "setView" && it.parameterTypes == listOf(VIEW)
            }
        }.unique("Material dialog content")
        // R8 binds create() to the declaring builder class, so any superclass of the dialog type is accepted.
        val create = dialogCreator.calls().filter {
            it.name == "create" && it.parameterTypes.isEmpty() && isA(dialogType, it.definingClass) &&
                isA(it.returnType, DIALOG)
        }.unique("Material dialog create")
        val buttons = dialogPeer.methods.single { it.parameterTypes == listOf(dialogType) && it.returnType == "V" }.calls()
            .filter { it.definingClass == dialogType }
        val positive = buttons.filter { it.parameterTypes == listOf(TEXT, CLICK) }.unique("positive button")
        val negativeWrapper = buttons.filter { it.parameterTypes == listOf(TEXT) }.unique("negative button wrapper")
        val negative = definition(negativeWrapper).calls().filter { it.parameterTypes == listOf(TEXT, CLICK) }
            .unique("negative button")
        // Single-choice list of the same Material builder: (CharSequence[], int checked, OnClickListener).
        val singleChoice = classDefBy(dialogType).methods.filter {
            it.parameterTypes == listOf("[Ljava/lang/CharSequence;", "I", CLICK) && it.returnType == "V"
        }.also { check(it.size == 1) { "Settings: expected one single-choice setter, found ${it.size}" } }.single()

        println("Switch view fingerprint native single-choice builder: 1 match ($singleChoice)")

        owner.interfaces.add(BRIDGE)
        owner.fields.add(MutableField(ImmutableField(owner.type, "filesPlusScreen", OBJ,
            AccessFlags.PRIVATE.value, null, emptySet(), emptySet())))
        fun bridge(name: String, params: List<String>, returns: String = "V", locals: Int = 2, body: String): MutableMethod =
            owner.method(name, params, returns, locals, body)
        bridge("host", emptyList(), "Landroid/app/Activity;", body = """
            invoke-virtual {p0}, $host
            move-result-object v0
            return-object v0
        """.trimIndent())
        for ((name, type, args) in listOf(
            Triple("newCategory", CATEGORY, "${CTX}Landroid/util/AttributeSet;"),
            Triple("newPreference", PREF, "${CTX}Landroid/util/AttributeSet;"),
            Triple("newSwitch", SWITCH, CTX),
        )) {
            check(classDefBy(type).methods.any { it.name == "<init>" &&
                it.parameterTypes == if (type == SWITCH) listOf(CTX) else listOf(CTX, "Landroid/util/AttributeSet;") })
            bridge(name, listOf(CTX), OBJ, body = """
                new-instance v0, $type
                const/4 v1, 0x0
                invoke-direct {v0, p1${if (type == SWITCH) "" else ", v1"}}, $type-><init>($args)V
                return-object v0
            """.trimIndent())
        }
        fun operation(name: String, method: MethodReference, params: List<String>) {
            bridge(name, listOf(OBJ) + params, body = """
                check-cast p1, ${method.definingClass}
                invoke-virtual {p1${if (params.isEmpty()) "" else ", p2"}}, $method
                return-void
            """.trimIndent())
        }
        operation("prepare", prepare, emptyList())
        operation("setKey", key, listOf("Ljava/lang/String;"))
        operation("setTitle", title, listOf(TEXT))
        operation("setSummary", summary, listOf(TEXT))
        operation("setIcon", icon, listOf("I"))
        operation("setChecked", checked, listOf("Z"))
        bridge("categoryStyle", listOf(OBJ), body = """
            check-cast p1, ${categoryStyle.definingClass}
            const/4 v0, 0x0
            invoke-virtual {p1, v0}, $categoryStyle
            return-void
        """.trimIndent())
        for ((prefix, field) in listOf("Click" to click, "Change" to change)) {
            bridge("${prefix.lowercase()}Type", emptyList(), "Ljava/lang/Class;", body = """
                const-class v0, ${field.type}
                return-object v0
            """.trimIndent())
            bridge("set$prefix", listOf(OBJ, OBJ), body = """
                check-cast p1, ${field.definingClass}
                check-cast p2, ${field.type}
                iput-object p2, p1, $field
                return-void
            """.trimIndent())
        }
        bridge("addTo", listOf(OBJ, OBJ), body = """
            check-cast p1, ${add.definingClass}
            check-cast p2, $PREF
            invoke-virtual {p1, p2}, $add
            return-void
        """.trimIndent())
        bridge("newScreen", listOf(OBJ), OBJ, body = """
            check-cast p1, $PREF
            iget-object v0, p1, $managerField
            iget-object v1, p1, $contextField
            invoke-virtual {v0, v1}, $screenFactory
            move-result-object v0
            invoke-virtual {v0}, $prepare
            return-object v0
        """.trimIndent())
        bridge("context", listOf(OBJ), CTX, body = """
            check-cast p1, $PREF
            iget-object v0, p1, $contextField
            return-object v0
        """.trimIndent())
        bridge("publish", listOf(OBJ), body = """
            iput-object p1, p0, ${owner.type}->filesPlusScreen:$OBJ
            return-void
        """.trimIndent())
        // SettingsPages.build catches every Throwable itself, and the bridge cannot throw (instance-of guards the
        // cast), so no smali try/catch is needed. A failed or absent page leaves the original screen installed.
        val commit = bridge("filesPlusInstall", listOf(SCREEN), locals = 2, body = """
            const/4 v0, 0x0
            iput-object v0, p0, ${owner.type}->filesPlusScreen:$OBJ
            invoke-static {p0, p1}, $PAGES->build($OBJ$OBJ)V
            iget-object v0, p0, ${owner.type}->filesPlusScreen:$OBJ
            instance-of v1, v0, $SCREEN
            if-eqz v1, :original
            check-cast v0, $SCREEN
            move-object p1, v0
            :original
            invoke-virtual {p0, p1}, $install
            return-void
        """.trimIndent())
        val registers = finalCall.value as Instruction35c
        build.replaceInstruction(finalCall.index, "invoke-virtual {v${registers.registerC}, v${registers.registerD}}, $commit")

        bridge("dialog", listOf(CTX, TEXT, VIEW, TEXT, TEXT), DIALOG, locals = 3, body = """
            new-instance v0, $dialogType
            const/4 v1, 0x0
            invoke-direct {v0, p1, v1}, $constructor
            invoke-virtual {v0, p2}, $dialogTitle
            invoke-virtual {v0, p3}, $dialogView
            invoke-virtual {v0, p4, v1}, $positive
            invoke-virtual {v0, p5, v1}, $negative
            invoke-virtual {v0}, $create
            move-result-object v0
            return-object v0
        """.trimIndent())
        val choiceParams = listOf(CTX, TEXT, "[Ljava/lang/CharSequence;", "I", CLICK)
        val pages = mutableClassDefBy(PAGES)
        val stub = pages.methods.single {
            it.name == "nativeChoices" && it.parameterTypes == choiceParams && it.returnType == DIALOG
        }
        check(pages.methods.remove(stub))
        val nativeChoices = MutableMethod(ImmutableMethod(PAGES, stub.name,
            choiceParams.map { ImmutableMethodParameter(it, emptySet(), null) }, DIALOG, stub.accessFlags,
            emptySet(), emptySet(), MutableMethodImplementation(7)))
        nativeChoices.addInstructions(0, """
            new-instance v0, $dialogType
            const/4 v1, 0x0
            invoke-direct {v0, p0, v1}, $constructor
            invoke-virtual {v0, p1}, $dialogTitle
            invoke-virtual {v0, p2, p3, p4}, $singleChoice
            invoke-virtual {v0}, $create
            move-result-object v0
            return-object v0
        """.trimIndent())
        pages.methods.add(nativeChoices)
        bridge("choices", choiceParams, DIALOG, body = """
            invoke-static/range {p1 .. p5}, $nativeChoices
            move-result-object v0
            return-object v0
        """.trimIndent())

        // Every PrefBridge member must have a generated implementation, or the app would crash at runtime.
        val generated = owner.methods.map { it.name to it.parameterTypes.map(CharSequence::toString) }.toSet()
        for (member in classDefBy(BRIDGE).methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }) {
            check((member.name to member.parameterTypes.map(CharSequence::toString)) in generated) {
                "Settings: bridge member without implementation: $member"
            }
        }

        val preference = mutableClassDefBy(PREF)
        val binders = preference.methods.filter { method ->
            method.returnType == "V" && method.parameterTypes.size == 1 &&
                method.code().any { (it as? WideLiteralInstruction)?.wideLiteral == 0x01020016L } &&
                method.calls().any { it.definingClass == VIEW && it.name == "setOnClickListener" }
        }
        check(binders.size == 1) { "Settings preference row bind fingerprint: expected 1 match, got ${binders.size}" }
        val binder = binders.single()
        println("Settings fingerprint preference row bind: 1 match ($binder)")
        val itemView = binder.code().mapNotNull { it.ref() as? FieldReference }.distinctBy { it.toString() }.single {
            it.type == VIEW && isA(binder.parameterTypes.single().toString(), it.definingClass)
        }
        preference.afterBrowserMethod(binder, """
            iget-object v1, p1, $itemView
            invoke-static {p0, v1}, $SMB_SWIPE->bind($OBJ$VIEW)V
        """.trimIndent(), PAGES)

        val titleCalls = onCreate.code().withIndex().filter { (_, instruction) ->
            (instruction.ref() as? MethodReference)?.let {
                it.name == "setTitle" && it.parameterTypes == listOf("I") && it.returnType == "V"
            } == true
        }
        check(titleCalls.size == 1) { "Settings: title call changed" }
        val titleCall = titleCalls.single()
        val titleRegister = (titleCall.value as Instruction35c).registerC
        onCreate.addInstruction(titleCall.index + 1,
            "invoke-static/range {v$titleRegister .. v$titleRegister}, $PAGES->updateTitle(Landroid/app/Activity;)V")
        val result = activity.methods.single {
            it.name == "onActivityResult" && it.parameterTypes == listOf("I", "I", "Landroid/content/Intent;")
        }
        result.addInstruction(0,
            "invoke-static/range {p0 .. p3}, $PAGES->onActivityResult(Landroid/app/Activity;IILandroid/content/Intent;)V")
    }
}

private fun MutableClass.method(
    name: String, params: List<String>, returns: String, locals: Int, body: String,
): MutableMethod {
    check(methods.none { it.name == name }) { "Settings bridge method already exists: $name" }
    val method = MutableMethod(ImmutableMethod(type, name,
        params.map { ImmutableMethodParameter(it, emptySet(), null) }, returns,
        AccessFlags.PUBLIC.value, emptySet(), emptySet(), MutableMethodImplementation(locals + params.size + 1)))
    method.addInstructions(0, body)
    methods.add(method)
    return method
}
