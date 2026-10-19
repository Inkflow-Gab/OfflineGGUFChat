cmake_minimum_required(VERSION 3.22.1)
project(offlinegguf_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ---------------------------------------------------------------------------
# OFFLINEGGUF_WITH_LLAMA=OFF (default) builds libllama_jni.so that:
#   * really parses GGUF headers (preflight + metadata, gguf/gguf_probe.cpp)
#   * returns a typed "engine not compiled" error rather than crashing or
#     silently no-oping, so a fresh clone always builds and the UI can explain
#     itself.
# Set -Pofflinegguf.llama=true in Gradle (or -DOFFLINEGGUF_WITH_LLAMA=ON here)
# to link a real llama.cpp checkout for on-device inference.
# ---------------------------------------------------------------------------
option(OFFLINEGGUF_WITH_LLAMA "Build the llama.cpp inference backend" OFF)
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../llama-cpp"
    CACHE PATH "Path to a llama.cpp checkout (see scripts/sync-llama.sh)")

set(OFFLINEGGUF_WARNINGS -Wall -Wextra -Wno-unused-parameter)

# --- GGUF container parser: no third-party code, host-testable ---
add_library(offlinegguf_gguf STATIC gguf/gguf_probe.cpp)
target_include_directories(offlinegguf_gguf PUBLIC ${CMAKE_CURRENT_LIST_DIR})
target_compile_options(offlinegguf_gguf PRIVATE ${OFFLINEGGUF_WARNINGS})

# --- inference backend selection ---
set(OFFLINEGGUF_BRIDGE_SOURCES llama/llama_bridge_none.cpp)
set(OFFLINEGGUF_HAVE_LLAMA 0)
set(OFFLINEGGUF_LLAMA_DEFINES "")

if(OFFLINEGGUF_WITH_LLAMA)
    foreach(candidate "${LLAMA_CPP_DIR}/include/llama.h" "${LLAMA_CPP_DIR}/llama.h")
        if(EXISTS "${candidate}")
            set(OFFLINEGGUF_LLAMA_H "${candidate}")
            break()
        endif()
    endforeach()
    if(NOT OFFLINEGGUF_LLAMA_H)
        message(FATAL_ERROR
          "OFFLINEGGUF_WITH_LLAMA=ON but no llama.h was found under '${LLAMA_CPP_DIR}'.\n"
          "Run ../scripts/sync-llama.sh, or pass -DLLAMA_CPP_DIR=<path to llama.cpp>.")
    endif()

    # Lean, self-contained Android build: no server, examples, tests, curl,
    # OpenMP or dynamically loaded backends.
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_BLAS OFF CACHE BOOL "" FORCE)
    set(GGML_METAL OFF CACHE BOOL "" FORCE)
    set(GGML_CUDA OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    set(GGML_HIP OFF CACHE BOOL "" FORCE)
    set(GGML_RPC OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)

    add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama-cpp EXCLUDE_FROM_ALL)

    if(TARGET llama)
        set(OFFLINEGGUF_LLAMA_TARGET llama)
    elseif(TARGET llama_cpp)
        set(OFFLINEGGUF_LLAMA_TARGET llama_cpp)
    else()
        message(FATAL_ERROR
          "llama.cpp was added but exposes neither a 'llama' nor a 'llama_cpp' target.")
    endif()

    # --- API-shape probes -----------------------------------------------------
    # llama.cpp renames things across releases. Instead of hard-failing on a
    # version mismatch, compile-test each symbol/field we use and let
    # llama_compat.h pick the matching branch.
    include(CheckCXXSourceCompiles)
    set(OFFLINEGGUF_LLAMA_INCLUDES
        ${LLAMA_CPP_DIR}/include ${LLAMA_CPP_DIR} ${LLAMA_CPP_DIR}/ggml/include)
    # These are compile-time API-shape probes, not link tests: the snippets do
    # not link against llama, and some only take the address of a symbol. A
    # STATIC_LIBRARY try-compile checks the source without requiring those
    # symbols to resolve. Do not use -fsyntax-only here: CMake still runs a
    # linker step for its default executable probe, and syntax-only produces no
    # object file for that step (making every API probe fail on every toolchain).
    set(OFFLINEGGUF_TRY_COMPILE_OLD "${CMAKE_TRY_COMPILE_TARGET_TYPE}")
    set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
    set(OFFLINEGGUF_PROBE_FLAGS "-std=c++17 -w")

    function(ogg_probe name code)
        set(EXTRA_INCLUDES_OLD "${CMAKE_REQUIRED_INCLUDES}")
        set(FLAGS_OLD "${CMAKE_REQUIRED_FLAGS}")
        set(CMAKE_REQUIRED_FLAGS "${OFFLINEGGUF_PROBE_FLAGS}")
        set(CMAKE_REQUIRED_INCLUDES ${OFFLINEGGUF_LLAMA_INCLUDES})
        set(source "#include <llama.h>\n#include <ggml.h>\nint main() { ${code} return 0; }\n")
        check_cxx_source_compiles("${source}" ${name})
        set(CMAKE_REQUIRED_FLAGS "${FLAGS_OLD}")
        set(CMAKE_REQUIRED_INCLUDES "${EXTRA_INCLUDES_OLD}")
        if(${name})
            set(OFFLINEGGUF_LLAMA_DEFINES "${OFFLINEGGUF_LLAMA_DEFINES};${name}=1" PARENT_SCOPE)
            message(STATUS "  llama.cpp probe: ${name} = yes")
        else()
            message(STATUS "  llama.cpp probe: ${name} = no")
        endif()
    endfunction()

    message(STATUS "OfflineGGUFChat: probing llama.cpp API shape")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_MODEL_LOAD_FROM_FILE
              "(void)llama_model_load_from_file;")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_MODEL_FREE "(void)llama_model_free;")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_INIT_FROM_MODEL "(void)llama_init_from_model;")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_VOCAB_API
              "const llama_model * m = nullptr; (void)llama_model_get_vocab(m);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_VOCAB_IS_EOG
              "const llama_vocab * v = nullptr; (void)llama_vocab_is_eog(v, (llama_token)0);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_VOCAB_GET_ADD_BOS
              "const llama_vocab * v = nullptr; (void)llama_vocab_get_add_bos(v);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_LOAD_MODE
              "llama_model_params p = llama_model_default_params(); p.load_mode = LLAMA_LOAD_MODE_MMAP;")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_CTX_THREADS
              "llama_context_params p = llama_context_default_params(); p.n_threads = 4;")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_ABORT_CALLBACK
              "llama_context_params p = llama_context_default_params(); p.abort_callback = nullptr;")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_MEMORY_API
              "llama_context * c = nullptr; llama_memory_clear(llama_get_memory(c), false);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_MEMORY_SEQ_RM
              "llama_context * c = nullptr; (void)llama_memory_seq_rm(llama_get_memory(c), 0, 1, -1);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_MEMORY_SEQ_POS_MAX
              "llama_context * c = nullptr; (void)llama_memory_seq_pos_max(llama_get_memory(c), 0);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_KV_SELF_CLEAR
              "llama_context * c = nullptr; llama_kv_self_clear(c);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_KV_SELF_SEQ_RM
              "llama_context * c = nullptr; (void)llama_kv_self_seq_rm(c, 0, 1, -1);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_TOKEN_TO_PIECE_LSTRIP
              "llama_context * c = nullptr; const llama_model * m = nullptr;"
              "(void)llama_token_to_piece(llama_model_get_vocab(m), (llama_token)0, nullptr, 0, 0, false);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_SAMPLER_PENALTIES
              "(void)llama_sampler_init_penalties(10, 10, 1.0f, 0.0f, 0.0f);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_SAMPLER_GRAMMAR
              "const llama_vocab * v = nullptr; (void)llama_sampler_init_grammar(v, \"\", \"root\");")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_BATCH_GET_ONE
              "llama_token * t = nullptr; llama_batch b = llama_batch_get_one(t, 1); (void)b;")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_MODEL_DESC
              "const llama_model * m = nullptr; char buf[8]; (void)llama_model_desc(m, buf, 8);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_CHAT_TEMPLATE
              "const llama_model * m = nullptr; (void)llama_model_chat_template(m, nullptr);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_MODEL_N_CTX_TRAIN
              "const llama_model * m = nullptr; (void)llama_model_n_ctx_train(m);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_MODEL_N_PARAMS
              "const llama_model * m = nullptr; (void)llama_model_n_params(m);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_MODEL_SIZE
              "const llama_model * m = nullptr; (void)llama_model_size(m);")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_PRINT_SYSTEM_INFO
              "(void)llama_print_system_info;")
    ogg_probe(OFFLINEGGUF_LLAMA_HAS_ADAPTER_LORA
              "llama_model * m = nullptr; (void)llama_adapter_lora_init(m, \"\");")

    set(CMAKE_TRY_COMPILE_TARGET_TYPE "${OFFLINEGGUF_TRY_COMPILE_OLD}")
    unset(OFFLINEGGUF_TRY_COMPILE_OLD)

    set(OFFLINEGGUF_BRIDGE_SOURCES llama/llama_bridge_llama.cpp)
    set(OFFLINEGGUF_HAVE_LLAMA 1)
    message(STATUS "OfflineGGUFChat: llama.cpp backend ENABLED (${OFFLINEGGUF_LLAMA_TARGET})")
else()
    message(STATUS "OfflineGGUFChat: llama.cpp backend DISABLED - GGUF tooling only")
endif()

# --- JNI surface ---
add_library(llama_jni SHARED
    llama_jni.cpp
    ${OFFLINEGGUF_BRIDGE_SOURCES}
)
target_include_directories(llama_jni PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}
    ${CMAKE_CURRENT_LIST_DIR}/llama
)
target_compile_definitions(llama_jni PRIVATE
    OFFLINEGGUF_HAVE_LLAMA=${OFFLINEGGUF_HAVE_LLAMA}
    ${OFFLINEGGUF_LLAMA_DEFINES}
)
target_compile_options(llama_jni PRIVATE ${OFFLINEGGUF_WARNINGS})
target_link_libraries(llama_jni PRIVATE
    offlinegguf_gguf
    android
    log
    c++_shared
)

if(OFFLINEGGUF_HAVE_LLAMA)
    target_link_libraries(llama_jni PRIVATE ${OFFLINEGGUF_LLAMA_TARGET})
    target_compile_definitions(llama_jni PRIVATE ${OFFLINEGGUF_LLAMA_DEFINES})
endif()

# 16 KB pages are required for Android 15+ devices on arm64.
target_link_options(llama_jni PRIVATE "-Wl,-z,max-page-size=16384")

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(llama_jni PRIVATE -O2 -fvisibility=hidden
                                            -ffunction-sections -fdata-sections)
    target_link_options(llama_jni PRIVATE -Wl,--gc-sections)
endif()

# --- host-side tests (used by scripts/check-native.sh) ---
# Host test harness: plain g++, no NDK, no llama.cpp. scripts/check-native.sh
# builds the same two binaries directly when CMake is not available.
option(OFFLINEGGUF_HOST_TESTS "Build the native host test harness" OFF)
if(OFFLINEGGUF_HOST_TESTS)
    enable_testing()

    add_executable(gguf_probe_test tests/gguf_probe_test.cpp)
    target_link_libraries(gguf_probe_test PRIVATE offlinegguf_gguf)
    add_test(NAME gguf_probe_test COMMAND gguf_probe_test)

    add_executable(utf8_test tests/utf8_test.cpp)
    target_include_directories(utf8_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    add_test(NAME utf8_test COMMAND utf8_test)

    # If the optional engine checkout is present, compile and exercise the real
    # bridge too. This host smoke test needs no Android JNI headers and no model
    # download; it proves the native bridge calls the pinned llama.cpp library,
    # reports its version, owns a session, and fails an invalid model load cleanly.
    if(OFFLINEGGUF_HAVE_LLAMA)
        add_executable(llama_bridge_smoke
            tests/llama_bridge_smoke.cpp
            llama/llama_bridge_llama.cpp)
        target_include_directories(llama_bridge_smoke PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}
            ${CMAKE_CURRENT_SOURCE_DIR}/llama)
        target_compile_definitions(llama_bridge_smoke PRIVATE
            OFFLINEGGUF_HAVE_LLAMA=1
            ${OFFLINEGGUF_LLAMA_DEFINES})
        target_compile_options(llama_bridge_smoke PRIVATE ${OFFLINEGGUF_WARNINGS})
        target_link_libraries(llama_bridge_smoke PRIVATE ${OFFLINEGGUF_LLAMA_TARGET})
        add_test(NAME llama_bridge_smoke COMMAND llama_bridge_smoke)
    endif()
endif()
