plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.arm.aichat"
    compileSdk = 36

    ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 33

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
             abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                val enableVulkan = project.findProperty("ENABLE_VULKAN")?.toString()?.toBoolean() ?: true
                val enableOpenCL = project.findProperty("ENABLE_OPENCL")?.toString()?.toBoolean() ?: false
                val openclLibDir = project.findProperty("OPENCL_LIB_DIR")?.toString() ?: ""

                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DCMAKE_MESSAGE_LOG_LEVEL=DEBUG"
                arguments += "-DCMAKE_VERBOSE_MAKEFILE=ON"

                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"

                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"

                arguments += "-DGGML_VULKAN=${if (enableVulkan) "ON" else "OFF"}"
                arguments += "-DGGML_VULKAN_SHADERS_GEN=${if (enableVulkan) "ON" else "OFF"}"
                arguments += "-DGGML_OPENCL=${if (enableOpenCL) "ON" else "OFF"}"
                arguments += "-DGGML_OPENCL_USE_ADRENO_KERNELS=${if (enableOpenCL) "ON" else "OFF"}"
                if (openclLibDir.isNotEmpty()) {
                    arguments += "-DOPENCL_LIB_DIR=$openclLibDir"
                }
            }
        }
        aarMetadata {
            minCompileSdk = 35
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)

        compileOptions {
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    publishing {
        singleVariant("release") {
            withJavadocJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
