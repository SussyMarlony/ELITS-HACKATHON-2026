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
# arm64 ships three builds of this library, one per CPU level; NativeEngine loads the best one
# the phone supports (CpuFeatures.kt). PATNUB_VARIANT picks which one this configure builds:
#   ""      libpatnub_llm.so        armv8.2-a+dotprod            most phones since ~2019 (A55/A76+)
#   armv8   libpatnub_llm_armv8.so  armv8-a                      older phones (A53-only, A57/A72/A73)
#   i8mm    libpatnub_llm_i8mm.so   armv8.2-a+dotprod+fp16+i8mm  2021+ phones (X2/A710/A510 and newer): faster matmul
# One static library per level (not ggml's GGML_CPU_ALL_VARIANTS): that needs shared ggml
# backends, where msr_engine's weak hooks in ggml-cpu would silently resolve to nothing.
set(PATNUB_VARIANT "" CACHE STRING "CPU level of this build: empty (dotprod), armv8 or i8mm")
set(PATNUB_DEFS "")
if(ANDROID_ABI STREQUAL "arm64-v8a")
  if(PATNUB_VARIANT STREQUAL "armv8")
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
  elseif(PATNUB_VARIANT STREQUAL "i8mm")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
    set(PATNUB_DEFS PATNUB_NEEDS_DOTPROD PATNUB_NEEDS_FP16 PATNUB_NEEDS_I8MM)
  else()
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
    set(PATNUB_DEFS PATNUB_NEEDS_DOTPROD)
  endif()
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
target_compile_definitions(patnub_llm PRIVATE MSR_BUILDING_LIBRARY ${PATNUB_DEFS})
if(PATNUB_VARIANT)
  set_target_properties(patnub_llm PROPERTIES OUTPUT_NAME "patnub_llm_${PATNUB_VARIANT}")
endif()
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

# The main arm64 build also builds the other two CPU levels as separate configures of this same
# file (each its own static llama.cpp), writing next to libpatnub_llm.so so Gradle packages them.
if(ANDROID_ABI STREQUAL "arm64-v8a" AND NOT PATNUB_VARIANT)
  include(ExternalProject)
  foreach(v armv8 i8mm)
    ExternalProject_Add(patnub_llm_${v}
      SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}"
      BINARY_DIR "${CMAKE_BINARY_DIR}/variant-${v}"
      CMAKE_GENERATOR "${CMAKE_GENERATOR}"
      CMAKE_ARGS
        -DPATNUB_VARIANT=${v}
        -DLLAMA_DIR=${LLAMA_DIR}
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_STL=${ANDROID_STL}
        -DANDROID_NDK=${ANDROID_NDK}
        -DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
      BUILD_COMMAND ${CMAKE_COMMAND} --build . --target patnub_llm
      INSTALL_COMMAND ""
      BUILD_ALWAYS ON # rebuild when sources change; the sub-build is incremental itself
    )
    add_dependencies(patnub_llm patnub_llm_${v})
  endforeach()
endif()
