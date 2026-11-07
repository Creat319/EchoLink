cmake_minimum_required(VERSION 3.22.1)
project(echolink_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe 由 Maven 依赖 com.google.oboe:oboe 通过 prefab 提供
find_package(oboe REQUIRED CONFIG)

add_library(echolink_audio SHARED
    src/jni_bridge.cpp
    src/audio_engine.cpp
)

target_include_directories(echolink_audio PRIVATE src)

target_link_libraries(echolink_audio PRIVATE
    oboe::oboe
    android
    log
)

# 音频回调是热路径：release/debug 都开优化，并放宽浮点运算便于向量化
target_compile_options(echolink_audio PRIVATE -O3 -ffast-math -funroll-loops)
