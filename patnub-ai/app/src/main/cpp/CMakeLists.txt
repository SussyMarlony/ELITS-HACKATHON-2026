# Native LLM runtime for the patient app: llama.cpp (with the msr_engine patch) + mtmd + msr_engine.
# LLAMA_DIR must point at llama.cpp checked out at msr_engine's pinned commit with
# msr_engine/patches/0001-msr-external-buffer-hook.patch applied (see scripts/prepare-native.sh).
cmake_minimum_required(VERSION 3.22)
project(patnub_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(MSR_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../msr_engine" ABSOLUTE)
set(LLAMA_DIR "${MSR_DIR}/third_party/llama.cpp" CACHE PATH "patched llama.cpp source tree")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
  message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}. Run patnub-ai/scripts/prepare-native.sh")
endif()

# Static llama.cpp so its weak msr hooks resolve to the provider linked into this library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
  # dotprod: Cortex-A55/A76 and newer (nearly all arm64 phones from ~2019). Not A53-only phones.
  set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
endif()
add_subdirectory("${LLAMA_DIR}" llama EXCLUDE_FROM_ALL)

# msr_engine runtime, minus integrity.cpp (OpenSSL, only used by the model-inspect tool).
file(GLOB MSR_SOURCES "${MSR_DIR}/native/src/*/*.cpp")
list(FILTER MSR_SOURCES EXCLUDE REGEX ".*/model/integrity\\.cpp$")

add_library(patnub_llm SHARED
  patnub_llm.cpp
  ${MSR_SOURCES}
  "${MSR_DIR}/native/tools/msr_external_preload.cpp"
)
target_compile_definitions(patnub_llm PRIVATE MSR_BUILDING_LIBRARY)
target_include_directories(patnub_llm PRIVATE
  "${MSR_DIR}/native/include"
  "${LLAMA_DIR}/include"
  "${LLAMA_DIR}/ggml/include"
  "${LLAMA_DIR}/tools/mtmd"
)
target_compile_options(patnub_llm PRIVATE -O3 -fvisibility=hidden)
if(ANDROID)
  target_link_libraries(patnub_llm PRIVATE mtmd llama ggml android log m)
  # 16 KB page sizes (Android 15+ devices).
  target_link_options(patnub_llm PRIVATE "-Wl,-z,max-page-size=16384")
else()
  # Host build for scripts/test-native-host.sh: same code, desktop JNI.
  find_package(JNI REQUIRED COMPONENTS JVM) # headers only; headless JDKs have no AWT
  target_include_directories(patnub_llm PRIVATE ${JNI_INCLUDE_DIRS})
  set_target_properties(patnub_llm PROPERTIES POSITION_INDEPENDENT_CODE ON)
  set(CMAKE_POSITION_INDEPENDENT_CODE ON)
  target_link_libraries(patnub_llm PRIVATE mtmd llama ggml m)
endif()
