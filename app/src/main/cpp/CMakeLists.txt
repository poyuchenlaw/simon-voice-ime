cmake_minimum_required(VERSION 3.22.1)
project("smpunctllm" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build only the llama.cpp library (no examples/tools/server/tests/common).
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

# ARM-optimised int4/int8 GEMM kernels for arm64-v8a (Snapdragon 8 Gen 3).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(smpunctllm SHARED smpunct_llm.cpp)
target_include_directories(smpunctllm PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include)
target_link_libraries(smpunctllm llama android log)
