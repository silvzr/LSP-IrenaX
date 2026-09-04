plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "io.github.libxposed.api"

    sourceSets {
        val main by getting
        main.apply {
            // Vendored superset of the libxposed API 100 + 101 surfaces. The upstream
            // submodule (api/api) is kept purely as a reference and is no longer compiled.
            setRoot("src/main")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    androidResources {
        enable = false
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
}
