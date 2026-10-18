cmake_minimum_required(VERSION 3.22.1)
project(trustmark_native LANGUAGES CXX)

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
  IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libonnxruntime.so")

add_library(trustmark_native SHARED trustmark_native.cpp)
target_compile_features(trustmark_native PRIVATE cxx_std_17)
target_include_directories(trustmark_native PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_compile_options(trustmark_native PRIVATE -O3 -fexceptions -frtti)

find_library(log_lib log)
find_library(android_lib android)
target_link_libraries(trustmark_native PRIVATE onnxruntime ${log_lib} ${android_lib})
