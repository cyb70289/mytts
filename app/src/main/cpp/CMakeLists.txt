cmake_minimum_required(VERSION 3.22)
project(espeak_jni LANGUAGES C CXX)

include(FetchContent)

# ---- Fetch espeak-ng (same fork/commit as sherpa-onnx) ----
set(espeak_ng_URL "https://github.com/csukuangfj/espeak-ng/archive/f6fed6c58b5e0998b8e68c6610125e2d07d595a7.zip")
set(espeak_ng_HASH "SHA256=70cbf4050e7a014aae19140b05e57249da4720f56128459fbe3a93beaf971ae6")

# Check for local cache to avoid re-downloading
set(possible_locations
    $ENV{HOME}/Downloads/espeak-ng-f6fed6c58b5e0998b8e68c6610125e2d07d595a7.zip
    /tmp/espeak-ng-f6fed6c58b5e0998b8e68c6610125e2d07d595a7.zip
)
foreach(f IN LISTS possible_locations)
    if(EXISTS ${f})
        set(espeak_ng_URL "${f}")
        message(STATUS "Using local espeak-ng: ${espeak_ng_URL}")
        break()
    endif()
endforeach()

# Disable everything we don't need — we only want text-to-phoneme
set(BUILD_ESPEAK_NG_EXE OFF CACHE BOOL "" FORCE)
set(BUILD_ESPEAK_NG_TESTS OFF CACHE BOOL "" FORCE)
set(USE_ASYNC OFF CACHE BOOL "" FORCE)
set(USE_MBROLA OFF CACHE BOOL "" FORCE)
set(USE_LIBSONIC OFF CACHE BOOL "" FORCE)
set(USE_LIBPCAUDIO OFF CACHE BOOL "" FORCE)
set(USE_KLATT OFF CACHE BOOL "" FORCE)
set(USE_SPEECHPLAYER OFF CACHE BOOL "" FORCE)

FetchContent_Declare(espeak_ng
    URL ${espeak_ng_URL}
    URL_HASH ${espeak_ng_HASH}
)

FetchContent_GetProperties(espeak_ng)
if(NOT espeak_ng_POPULATED)
    message(STATUS "Downloading espeak-ng from ${espeak_ng_URL}")
    FetchContent_Populate(espeak_ng)
endif()
message(STATUS "espeak-ng source: ${espeak_ng_SOURCE_DIR}")

# Build espeak-ng as static library
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(${espeak_ng_SOURCE_DIR} ${espeak_ng_BINARY_DIR})

# Make sure espeak-ng is built with PIC for shared lib linking
set_target_properties(espeak-ng PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    C_VISIBILITY_PRESET hidden
)
set_target_properties(ucd PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

# Suppress warnings from espeak-ng C code on Android
target_compile_options(espeak-ng PRIVATE
    -Wno-unused-result
    -Wno-format
    -Wno-uninitialized
)

# ---- Our JNI bridge ----
add_library(espeak_jni SHARED espeak_jni.cpp)

target_include_directories(espeak_jni PRIVATE
    ${espeak_ng_SOURCE_DIR}/src/include
)

target_link_libraries(espeak_jni PRIVATE
    espeak-ng
    ucd
    log
)
