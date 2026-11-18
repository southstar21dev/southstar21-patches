package app.southstar21.patches.youtubemusic.layout.liquidglass

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.southstar21.patches.youtubemusic.misc.extension.markYouTubeMusicPatchIncluded
import app.southstar21.patches.youtubemusic.misc.extension.youTubeMusicSharedExtensionPatch
import app.southstar21.patches.youtubemusic.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/southstar21/extension/youtubemusic/liquidglass/LiquidGlassNavBarPatch;"

private fun extensionMethod(name: String, returnType: String) = Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = name,
    returnType = returnType,
    parameters = listOf(),
)

@Suppress("unused")
val youTubeMusicLiquidGlassNavBarPatch = bytecodePatch(
    name = "Liquid Glass navigation bar",
    description = "Turns the bottom tab bar and the mini player above it into floating iOS 26 style " +
            "Liquid Glass with live background blur and edge refraction, with the content scrolling on behind them. " +
            "Refraction needs Android 13+, blur needs Android 12+, older versions get a tinted pill.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    dependsOn(youTubeMusicSharedExtensionPatch)

    val blurRadius by intOption(
        key = "blurRadius",
        default = 4,
        values = mapOf(
            "Clear" to 2,
            "Default" to 4,
            "Frosted" to 12,
        ),
        title = "Blur radius",
        description = "Blur applied to content behind the bar, in dp.",
        required = true,
    ) { it!! in 0..40 }

    val refractionStrength by intOption(
        key = "refractionStrength",
        default = 100,
        values = mapOf(
            "Off" to 0,
            "Subtle" to 30,
            "Medium" to 60,
            "Default" to 100,
        ),
        title = "Refraction strength",
        description = "How strongly the glass edge bends the content behind it, from 0 to 100.",
        required = true,
    ) { it!! in 0..100 }

    val southstar21Style by booleanOption(
        key = "southstar21Style",
        default = true,
        title = "southstar21 style",
        description = "Thicker glass, and a press swells the selection into a magnifying drop of glass " +
                "that wobbles and stretches as you drag it.",
        required = true,
    )

    execute {
        extensionMethod("blurRadiusDp", "I").method.addInstructions(
            0,
            """
                const/16 v0, $blurRadius
                return v0
            """
        )
        extensionMethod("refractionPercent", "I").method.addInstructions(
            0,
            """
                const/16 v0, $refractionStrength
                return v0
            """
        )
        extensionMethod("southstar21Style", "Z").method.addInstructions(
            0,
            """
                const/4 v0, ${if (southstar21Style == true) 1 else 0}
                return v0
            """
        )

        // The content's height is worked out as `sub-int height, full, barHeight` right before
        // it's applied with a static (View, int, int) helper. Let the extension give the full
        // height instead, so the content runs on behind the glass bar.
        WatchWhileLayoutOnMeasureFingerprint.method.apply {
            val subIndex = instructions.withIndex().indexOfFirst { (i, instruction) ->
                if (instruction.opcode != Opcode.SUB_INT) return@indexOfFirst false
                val next = instructions.getOrNull(i + 1) ?: return@indexOfFirst false
                val ref = (next as? ReferenceInstruction)?.reference as? MethodReference
                next.opcode == Opcode.INVOKE_STATIC && ref != null && ref.returnType == "V" &&
                        ref.parameterTypes.map { it.toString() } == listOf("Landroid/view/View;", "I", "I")
            }
            if (subIndex < 0) throw PatchException("Couldn't find the content height in WatchWhileLayout.onMeasure")
            val sub = instructions[subIndex] as ThreeRegisterInstruction
            val height = sub.registerA
            val full = sub.registerB
            if (height > 15 || full > 15) throw PatchException("Unexpected registers in WatchWhileLayout.onMeasure")
            addInstructions(
                subIndex + 1,
                """
                    invoke-static { v$height, v$full }, $EXTENSION_CLASS->contentHeight(II)I
                    move-result v$height
                """
            )
        }

        markYouTubeMusicPatchIncluded(EXTENSION_CLASS)
    }
}
