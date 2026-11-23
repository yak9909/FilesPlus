package app.filesplus.patches.browse

import app.filesplus.patches.screens.openStackPatch
import app.filesplus.patches.settings.settingsInjectionPatch
import app.filesplus.patches.shared.Constants
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

val viewSwitchPatch = bytecodePatch(
    name = "Switch view",
    description = "Switch between the app's storage and media browsers from the top overflow.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FILES)
    dependsOn(browserHooksPatch, openStackPatch, settingsInjectionPatch)

    execute {
        mutableClassDefBy("${Constants.EXT}browse/ViewSwitch;").methods.single {
            it.name == "installed" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
    }
}
