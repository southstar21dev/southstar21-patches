package app.southstar21.patches.instagram.misc.extension

import app.morphe.patcher.patch.bytecodePatch

/**
 * Bundles the Instagram extension (extensions/instagram) into the app.
 * Every Instagram patch that runs extension code depends on this.
 */
val sharedExtensionPatch = bytecodePatch {
    extendWith("extensions/instagram.mpe")
}
