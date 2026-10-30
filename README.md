# Instagram Liquid Glass

A [Morphe](https://morphe.software) patch that brings Instagram's iOS 26 Liquid Glass design to Instagram for Android, matched against screen recordings of the iOS app.

- **Floating glass tab bar**: a frosted pill with live background blur, a subtle refracting rim and a grey capsule behind the selected tab.
- **Shrinks while scrolling**: scrolling down scales the whole bar down (all tabs stay visible and tappable), scrolling up restores it.
- **Gliding selection**: drag along the bar to slide the selection between tabs as clear glass, with a haptic tick per tab; release to open the tab underneath.
- **Glass header**: the feed and Explore headers let content scroll under them; the + and heart buttons turn into glass circles once there's content behind them.
- **Glass search field** on Explore.
- **Adaptive tint**: the glass frosts more when the content behind it would make the icons hard to read, and turns dark on Reels and in dark mode.
- Taps, double taps and long presses on the tabs still go to Instagram untouched.

Android 12 gets blur without refraction, older versions get a translucent tinted pill.

## How to use

Add this repo as a patch source in Morphe Manager:

https://morphe.software/add-source?github=southstar21dev/instagram-liquid-glass

Or download `patches-x.y.z.mpp` from [Releases](https://github.com/southstar21dev/instagram-liquid-glass/releases) and add it as a local file.

Then patch the Instagram **APKM** bundle from APKMirror (version listed below). The patched app is signed with a different key, so the Play Store version has to be uninstalled first.

> [!WARNING]
> Instagram's terms don't allow modified clients. This patch is cosmetic only, but use it at your own risk.

### Options

| Option | Default | |
|---|---|---|
| Blur radius | 12 dp | How frosted the glass is (0 to 40). |
| Refraction strength | 60 | How strongly the edge bends what's behind it (0 to 100). |
| Extend content behind bar | on | Lets feeds scroll under the bar so there's something to see through the glass. |

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.1.0](https://github.com/southstar21dev/instagram-liquid-glass/releases/tag/v1.1.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 Instagram&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 439.0.0.37.89 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Liquid Glass navigation bar](#liquid-glass-navigation-bar) | Turns the bottom tab bar into a floating iOS 26 style Liquid Glass pill with live background blur and edge refraction. Refraction needs Android 13+, blur needs Android 12+, older versions get a tinted pill. | • Blur radius<br>• Refraction strength<br>• Extend content behind bar |

</details>

<!-- PATCHES_END -->

## How it works

- A single hook in `InstagramMainActivity.onWindowFocusChanged` (an unobfuscated method, so it should survive app updates) installs the extension.
- The extension finds the `tab_bar` view by resource name, turns it into a floating pill, and draws it with a background drawable that re-records the views behind the bar into a `RenderNode` each frame, then runs it through a blur and an AGSL refraction shader.
- The selection droplet is a separate view stacked above the bar. Drags are routed to it through a window callback wrapper, because Instagram's swipe navigation would otherwise take them.

## Building

```
./gradlew :patches:buildAndroid
```

Needs a GitHub token with `read:packages` (for the Morphe Gradle plugin), see the
[Morphe setup docs](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_1_setup.md#-prepare-the-environment).

Based on the [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template). Licensed under GPLv3.
