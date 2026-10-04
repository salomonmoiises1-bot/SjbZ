cmake_minimum_required(VERSION 3.22.1)
project("sb_dsp_engine")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimizaciones de rendimiento para arquitecturas Android
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -mfpu=neon -ffast-math")
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math")
elseif(ANDROID_ABI MATCHES "^x86")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")
endif()

# Integrar Oboe automáticamente desde el repositorio oficial de Google
include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG        1.9.0
)
FetchContent_MakeAvailable(oboe)

# Crear tu librería compartida del motor DSP
add_library(
    sb_dsp_engine
    SHARED
    oboe_dsp_engine.cpp
)

# Directorios de inclusión locales
target_include_directories(
    sb_dsp_engine
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Enlazar Oboe, el registro de Android y las librerías nativas
target_link_libraries(
    sb_dsp_engine
    PRIVATE
    oboe::oboe
    log
    android
)
