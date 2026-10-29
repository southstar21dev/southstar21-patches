group = "app.liquidglass"

patches {
    about {
        name = "Liquid Glass Patches"
        description = "Ports the iOS 26 Liquid Glass tab bar to Instagram for Android"
        source = "https://github.com/southstar21dev/instagram-liquid-glass"
        author = "southstar21dev"
        contact = "na"
        website = "https://github.com/southstar21dev/instagram-liquid-glass"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
