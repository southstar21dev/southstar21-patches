package app.liquidglass.patches.instagram.navbar

import app.liquidglass.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption

@Suppress("unused")
val liquidGlassNavBarPatch = bytecodePatch(
    name = "Liquid Glass navigation bar",
    description = "Turns the bottom tab bar into a floating iOS 26 style Liquid Glass pill " +
            "with live background blur and edge refraction. " +
            "Refraction needs Android 13+, blur needs Android 12+, older versions get a tinted pill.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith("extensions/extension.mpe")

    val blurRadius by intOption(
        key = "blurRadius",
        default = 6,
        values = mapOf(
            "Clear" to 3,
            "Default" to 6,
            "Frosted" to 14,
        ),
        title = "Blur radius",
        description = "Blur applied to content behind the bar, in dp.",
        required = true,
    ) { it!! in 0..40 }

    val refractionStrength by intOption(
        key = "refractionStrength",
        default = 60,
        values = mapOf(
            "Off" to 0,
            "Subtle" to 30,
            "Default" to 60,
            "Strong" to 100,
        ),
        title = "Refraction strength",
        description = "How strongly the glass edge bends the content behind it, from 0 to 100.",
        required = true,
    ) { it!! in 0..100 }

    val extendContent by booleanOption(
        key = "extendContentBehindBar",
        default = true,
        title = "Extend content behind bar",
        description = "Lets feeds scroll underneath the bar so there is something to see through the glass. " +
                "Turn off if the last item of a list ends up hidden.",
        required = true,
    )

    execute {
        BlurRadiusFingerprint.method.addInstructions(
            0,
            """
                const/16 v0, $blurRadius
                return v0
            """
        )

        RefractionStrengthFingerprint.method.addInstructions(
            0,
            """
                const/16 v0, $refractionStrength
                return v0
            """
        )

        ExtendContentFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, ${if (extendContent == true) 1 else 0}
                return v0
            """
        )

        MainActivityWindowFocusFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onWindowFocusChanged(Landroid/app/Activity;)V"
        )
    }
}
