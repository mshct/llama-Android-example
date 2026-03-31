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
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
        # don't FORCE here — lets the gradle property (-DGGML_VULKAN=ON/OFF) win
        set(GGML_VULKAN OFF CACHE BOOL "Enable Vulkan backend") #(可选)以vulkan为后端

        # pick the right glslc from the NDK based on build host OS
        if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
            set(_glslc_subdir "windows-x86_64/glslc.exe")
        elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
            set(_glslc_subdir "darwin-x86_64/glslc")
        else()
            set(_glslc_subdir "linux-x86_64/glslc")
        endif()
        set(Vulkan_GLSLC_EXECUTABLE "${CMAKE_ANDROID_NDK}/shader-tools/${_glslc_subdir}" CACHE FILEPATH "Path to glslc" FORCE)

        if(GGML_OPENCL)
          set(GGML_OPENCL ON CACHE BOOL "Enable OpenCL backend for Adreno 740" FORCE) #(可选)以opencl为后端

                  # OPENCL_LIB_DIR can be set via gradle property; defaults to the app jniLibs dir
                  if(NOT DEFINED OPENCL_LIB_DIR OR OPENCL_LIB_DIR STREQUAL "")
                      set(OPENCL_LIB_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../app/src/main/jniLibs/arm64-v8a")
                  endif()
                  set(OPENCL_LIB_FILE "${OPENCL_LIB_DIR}/libOpenCL.so")

                  add_library(opencl_prebuilt SHARED IMPORTED)
                  set_target_properties(opencl_prebuilt PROPERTIES IMPORTED_LOCATION ${OPENCL_LIB_FILE})

                  set(OPENCL_INCLUDE_DIR "${CMAKE_CURRENT_LIST_DIR}/llama-source/ggml/src/ggml-opencl/third-party" CACHE PATH "" FORCE)
                  set(OPENCL_LIBRARY opencl_prebuilt CACHE FILEPATH "" FORCE)
          set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "Use Adreno optimized kernels" FORCE)
          set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "Embed OpenCL kernels into binary" FORCE)
        endif()

        set(GGML_HEXAGON OFF CACHE BOOL "Hexagon DSP backend disabled now" FORCE)

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

if(GGML_OPENCL)
    target_link_libraries(${CMAKE_PROJECT_NAME} opencl_prebuilt)
endif()


# ====================== 构建信息 ======================
message(STATUS "=== Build Summary ===")
message(STATUS "ABI            : ${ANDROID_ABI}")
message(STATUS "GGML_OPENCL    : ${GGML_OPENCL}")
message(STATUS "Adreno Kernels   : ${GGML_OPENCL_USE_ADRENO_KERNELS}")
