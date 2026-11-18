package app.southstar21.patches.youtubemusic.misc.extension

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.southstar21.patches.youtubemusic.shared.MusicActivityWindowFocusFingerprint

internal const val YOUTUBE_MUSIC_HOOKS_CLASS = "Lapp/southstar21/extension/youtubemusic/YouTubeMusicHooks;"

/**
 * Bundles the YouTube Music extension (extensions/youtubemusic) into the app and calls its
 * shared hook, which starts every included patch. Every YouTube Music patch depends on this.
 */
val youTubeMusicSharedExtensionPatch = bytecodePatch {
    extendWith("extensions/youtubemusic.mpe")

    execute {
        MusicActivityWindowFocusFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $YOUTUBE_MUSIC_HOOKS_CLASS->onMainActivityWindowFocusChanged(Landroid/app/Activity;)V"
        )
    }
}

/** Makes the extension class's `isIncluded()` return true, so the patch's code runs. */
context(patchContext: BytecodePatchContext)
internal fun markYouTubeMusicPatchIncluded(extensionClass: String) {
    Fingerprint(
        definingClass = extensionClass,
        name = "isIncluded",
        returnType = "Z",
        parameters = listOf(),
    ).method.addInstructions(
        0,
        """
            const/4 v0, 0x1
            return v0
        """
    )
}
