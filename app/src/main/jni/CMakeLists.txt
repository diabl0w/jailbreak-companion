cmake_minimum_required(VERSION 3.22)
project(jbcompanion_native)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Put executables alongside .so files so Android packages them
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})

add_library(jbcompanion_maps STATIC maps.cpp)
target_include_directories(jbcompanion_maps PUBLIC .)

# Ptrace injector — built as an executable named libinject.so so the
# Android Gradle plugin packages it under lib/arm64-v8a/ in the APK.
add_executable(libinject.so injector.cpp utils.cpp)
target_include_directories(libinject.so PRIVATE .)
target_link_libraries(libinject.so PRIVATE jbcompanion_maps log)

# Payload shared library injected into system_server
add_library(payload SHARED payload.cpp)
target_compile_options(payload PRIVATE -fno-plt -fno-stack-protector)
target_link_libraries(payload PRIVATE log dl)
