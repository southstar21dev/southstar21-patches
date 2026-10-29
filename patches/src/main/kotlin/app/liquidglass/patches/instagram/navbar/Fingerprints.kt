package app.liquidglass.patches.instagram.navbar

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Lifecycle methods of InstagramMainActivity are mostly obfuscated wrappers,
 * but onWindowFocusChanged is a real override. It runs once the tab bar has been
 * inflated and again after the activity is recreated, which is what we need.
 */
internal object MainActivityWindowFocusFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/mainactivity/InstagramMainActivity;",
    name = "onWindowFocusChanged",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
)

internal const val EXTENSION_CLASS =
    "Lapp/liquidglass/extension/instagram/LiquidGlassNavBarPatch;"

internal object BlurRadiusFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "blurRadiusDp",
    returnType = "I",
)

internal object RefractionStrengthFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "refractionPercent",
    returnType = "I",
)

internal object ExtendContentFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "extendContentBehindBar",
    returnType = "Z",
)
