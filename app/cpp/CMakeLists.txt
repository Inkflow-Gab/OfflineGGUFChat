cmake_minimum_required(VERSION 3.22.1)
project("llama_jni")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Location of llama.cpp source. Defaults to vendored copy below.
if(NOT DEFINED LLAMA_DIR)
    set(LLAMA_DIR "${CMAKE_SOURCE_DIR}/llama.cpp")
endif()

include_directories(${LLAMA_DIR})

# Collect llama.cpp source files
file(GLOB LLAMA_SOURCES "${LLAMA_DIR}/*.cpp")

add_library(llama STATIC ${LLAMA_SOURCES})
target_compile_definitions(llama PRIVATE GGML_USE_CPU=1)

# JNI bridge
add_library(llama_jni SHARED
    ${CMAKE_SOURCE_DIR}/llama_jni.cpp
)

target_link_libraries(llama_jni PRIVATE llama android log)
target_include_directories(llama_jni PRIVATE ${CMAKE_SOURCE_DIR})
