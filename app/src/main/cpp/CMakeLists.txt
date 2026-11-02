cmake_minimum_required(VERSION 3.22.1)
project(parktick_lpr LANGUAGES C CXX ASM)
include(FetchContent)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(PARKTICK_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)

function(pinned_archive name url sha)
    if(EXISTS "${PARKTICK_ROOT}/.tooling/downloads/${name}.tar.gz")
        set(archive "${PARKTICK_ROOT}/.tooling/downloads/${name}.tar.gz")
    else()
        set(archive "${url}")
    endif()
    FetchContent_Declare(${name} URL "${archive}" URL_HASH "SHA256=${sha}")
endfunction()

set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
foreach(option BUILD_TOOLS BUILD_CONVERTER BUILD_TRAIN BUILD_DEMO BUILD_TEST BUILD_BENCHMARK BUILD_QUANTOOLS JNI BUILD_OPENCV OPENCL OPENGL VULKAN NNAPI OPENMP INTERNAL)
    set(MNN_${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(MNN_BUILD_MINI OFF CACHE BOOL "" FORCE)
pinned_archive(mnn "https://codeload.github.com/alibaba/MNN/tar.gz/a74551b4f34b46ce7027c64e800d49fcab497261" "71d8b91234a3249c948c5b1f2445d5eabc9e3a9b5e5fd0a676d79e17050db3d1")
FetchContent_MakeAvailable(mnn)

set(BUILD_LIST core,imgproc CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(option
    BUILD_TESTS BUILD_PERF_TESTS BUILD_EXAMPLES BUILD_opencv_apps BUILD_JAVA
    BUILD_ANDROID_PROJECTS BUILD_ANDROID_EXAMPLES INSTALL_ANDROID_EXAMPLES
    BUILD_opencv_python2 BUILD_opencv_python3 WITH_OPENCL WITH_IPP WITH_ITT WITH_TBB WITH_LAPACK
    WITH_JPEG WITH_PNG WITH_TIFF WITH_WEBP WITH_JASPER WITH_OPENEXR WITH_OPENJPEG)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(BUILD_ZLIB ON CACHE BOOL "" FORCE)
pinned_archive(opencv "https://codeload.github.com/opencv/opencv/tar.gz/71d3237a093b60a27601c20e9ee6c3e52154e8b1" "4c7f5fb9c7e0b5fcac4363302212f11323509eccb3af0dd46783242b9bbc1da6")
FetchContent_MakeAvailable(opencv)

set(HLPR "${PARKTICK_ROOT}/third_party/hyperlpr")
add_library(parktick_lpr SHARED
    lpr_jni.cpp
    ${HLPR}/cpp/c_api/hyper_lpr_sdk.cc
    ${HLPR}/cpp/src/buffer_module/camera_buffer.cpp
    ${HLPR}/cpp/src/context_module/hyper_lpr_context.cpp
    ${HLPR}/cpp/src/inference_helper_module/inference_helper.cpp
    ${HLPR}/cpp/src/inference_helper_module/inference_helper_mnn.cpp
    ${HLPR}/cpp/src/nn_module/mnn_adapter.cpp
    ${HLPR}/cpp/src/nn_implementation_module/classification/classification_engine.cpp
    ${HLPR}/cpp/src/nn_implementation_module/classification/plate_classification.cpp
    ${HLPR}/cpp/src/nn_implementation_module/detect/det_arch.cpp
    ${HLPR}/cpp/src/nn_implementation_module/detect/det_backbone.cpp
    ${HLPR}/cpp/src/nn_implementation_module/detect/det_header.cpp
    ${HLPR}/cpp/src/nn_implementation_module/detect/plate_detector.cpp
    ${HLPR}/cpp/src/nn_implementation_module/recognition/plate_recognition.cpp
    ${HLPR}/cpp/src/nn_implementation_module/recognition/recognition_engine.cpp
)
target_compile_features(parktick_lpr PRIVATE cxx_std_17)
target_compile_definitions(parktick_lpr PRIVATE INFERENCE_HELPER_ENABLE_MNN)
target_include_directories(parktick_lpr PRIVATE
    "${HLPR}/cpp/src" "${HLPR}/cpp/c_api" "${mnn_SOURCE_DIR}/include"
    "${opencv_SOURCE_DIR}/include"
    "${opencv_SOURCE_DIR}/modules/core/include"
    "${opencv_SOURCE_DIR}/modules/imgproc/include"
    "${OPENCV_CONFIG_FILE_INCLUDE_DIR}"
)
target_link_libraries(parktick_lpr PRIVATE opencv_imgproc opencv_core "-Wl,--whole-archive" MNN "-Wl,--no-whole-archive" jnigraphics log)
target_link_options(parktick_lpr PRIVATE "-Wl,-z,max-page-size=16384")
