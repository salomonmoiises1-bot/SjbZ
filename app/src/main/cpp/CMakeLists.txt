cmake_minimum_required(VERSION 3.22.1)
project("sb_dsp_engine")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -mfpu=neon -ffast-math")
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math")
elseif(ANDROID_ABI MATCHES "^x86")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")
endif()

find_package(oboe REQUIRED CONFIG)

add_library(sb_dsp_engine SHARED oboe_dsp_engine.cpp)
target_include_directories(sb_dsp_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(sb_dsp_engine PRIVATE oboe::oboe log android)