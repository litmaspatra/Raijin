cmake_minimum_required(VERSION 3.18)
project(raijin_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp is fetched into ./whisper.cpp by CI (pinned tag + commit check).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp whisper_build)

add_library(raijin_whisper SHARED jni.cpp)
target_include_directories(raijin_whisper PRIVATE
    whisper.cpp/include
    whisper.cpp/ggml/include)
target_link_libraries(raijin_whisper whisper log)
