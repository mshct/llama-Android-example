cmake_minimum_required(VERSION 3.31.6)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
                set(GGML_CPU_KLEIDIAI OFF) # disable KleidiAI to avoid online fetch failures
        set(GGML_OPENMP ON)
        set(GGML_VULKAN OFF CACHE BOOL "Enable Vulkan backend" FORCE) #(可选)以vulkan为后端
        set(Vulkan_GLSLC_EXECUTABLE "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe" CACHE FILEPATH "Path to glslc" FORCE)

        # Disable OpenCL to avoid missing OpenCL headers/libs during sync
        set(GGML_OPENCL OFF CACHE BOOL "Disable OpenCL backend" FORCE)

        #message(STATUS "=== Vulkan backend ENABLED (recommended for non-Snapdragon) ===")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama-source)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        common
        android
        log)

# ====================== 构建信息 ======================
message(STATUS "=== Build Summary ===")
message(STATUS "ABI            : ${ANDROID_ABI}")
message(STATUS "GGML_OPENCL    : ${GGML_OPENCL}")
message(STATUS "Adreno Kernels   : ${GGML_OPENCL_USE_ADRENO_KERNELS}")