# =============================================================================
# PyBridge JNI 桥接层 CMake 构建配置
# =============================================================================
#
# 本文件配置 libpybridge.so 共享库的编译，该库将 JNI 桥接层 C++ 代码与
# 预编译的 CPython 3.12 共享库链接在一起。
#
# 构建依赖：
#   - Python 源码头文件（Include/ 与 android_include/）
#   - 预编译的 libpython3.12.so
#   - Android log 库（liblog.so）
#
# 使用方式（在模块级 build.gradle 中）：
#
#   android {
#       defaultConfig {
#           ndk {
#               abiFilters 'arm64-v8a', 'armeabi-v7a'
#           }
#           externalNativeBuild {
#               cmake {
#                   arguments "-DPYTHON_SRC_DIR=${rootDir}/python-src"
#               }
#           }
#       }
#       externalNativeBuild {
#           cmake {
#               path "src/main/cpp/CMakeLists.txt"
#           }
#       }
#   }
#
# 其中 PYTHON_SRC_DIR 应指向包含以下结构的目录：
#   ${PYTHON_SRC_DIR}/
#   ├── Include/               # Python 标准头文件目录
#   │   ├── Python.h
#   │   ├── object.h
#   │   └── ...
#   ├── android_include/       # Android 平台专用头文件（如 pyconfig.h）
#   │   ├── pyconfig.h
#   │   └── ...
#   └── libs/                  # 预编译的共享库（按 ABI 分目录）
#       ├── arm64-v8a/
#       │   └── libpython3.12.so
#       └── armeabi-v7a/
#           └── libpython3.12.so
# =============================================================================

# CMake 最低版本要求（3.18.1+ 支持 Android NDK 的现代特性）
cmake_minimum_required(VERSION 3.18.1)

# 定义项目
project(pybridge LANGUAGES CXX)

# -----------------------------------------------------------------------------
# 构建选项与变量
# -----------------------------------------------------------------------------

# PYTHON_SRC_DIR: Python 源码及预编译库的根目录
# 由 Gradle externalNativeBuild 通过 -D 参数传入
# 若未指定则使用默认路径（相对于 CMakeLists.txt 所在目录）
if(NOT DEFINED PYTHON_SRC_DIR)
    set(PYTHON_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../python-src
        CACHE STRING "Python source and prebuilt library directory")
endif()

message(STATUS "PyBridge CMake configuration")
message(STATUS "  PYTHON_SRC_DIR = ${PYTHON_SRC_DIR}")
message(STATUS "  ANDROID_ABI    = ${ANDROID_ABI}")
message(STATUS "  CMAKE_LIBRARY_OUTPUT_DIRECTORY = ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")

# Python 版本号（用于确定库名）
set(PYTHON_VERSION_MAJOR 3)
set(PYTHON_VERSION_MINOR 12)
set(PYTHON_LIB_NAME python${PYTHON_VERSION_MAJOR}.${PYTHON_VERSION_MINOR})

# 头文件包含路径
set(PYTHON_INCLUDE_DIR     ${PYTHON_SRC_DIR}/Include)
set(PYTHON_ANDROID_INCLUDE_DIR ${PYTHON_SRC_DIR}/android_include)

# 预编译库路径（按 ABI 分目录存放）
set(PYTHON_LIBS_DIR ${PYTHON_SRC_DIR}/libs/${ANDROID_ABI})

# -----------------------------------------------------------------------------
# 验证头文件路径是否存在
# -----------------------------------------------------------------------------
if(NOT EXISTS ${PYTHON_INCLUDE_DIR})
    message(WARNING
        "Python include directory not found: ${PYTHON_INCLUDE_DIR}\n"
        "Please ensure PYTHON_SRC_DIR is set correctly.")
endif()

if(NOT EXISTS ${PYTHON_ANDROID_INCLUDE_DIR})
    message(WARNING
        "Python android_include directory not found: ${PYTHON_ANDROID_INCLUDE_DIR}\n"
        "Please ensure PYTHON_SRC_DIR is set correctly.")
endif()

# -----------------------------------------------------------------------------
# 编译选项
# -----------------------------------------------------------------------------

# C++ 标准
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 编译标志：
#   -fPIC: 生成位置无关代码（共享库必需）
#   -O2:   优化级别 2（发布构建推荐）
#   -Wall: 启用常见警告
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC -O2 -Wall")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fPIC -O2 -Wall")

# -----------------------------------------------------------------------------
# 共享库定义
# -----------------------------------------------------------------------------

# 编译 pybridge_jni.cpp 为 libpybridge.so 共享库
add_library(pybridge
    SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/pybridge_jni.cpp
)

# 设置输出目录（由 Android Gradle 插件通过 CMAKE_LIBRARY_OUTPUT_DIRECTORY 传入）
# 确保 .so 文件输出到正确的 jniLibs 目录
set_target_properties(pybridge PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
)

# -----------------------------------------------------------------------------
# 包含路径
# -----------------------------------------------------------------------------

target_include_directories(pybridge PRIVATE
    ${PYTHON_INCLUDE_DIR}
    ${PYTHON_ANDROID_INCLUDE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# -----------------------------------------------------------------------------
# 链接预编译的 libpython3.12.so
# -----------------------------------------------------------------------------

# 导入预编译的 Python 共享库
add_library(${PYTHON_LIB_NAME} SHARED IMPORTED)

# 设置库的位置（按当前 ABI 选择）
set_target_properties(${PYTHON_LIB_NAME} PROPERTIES
    IMPORTED_LOCATION
    ${PYTHON_LIBS_DIR}/lib${PYTHON_LIB_NAME}.so
)

# 验证预编译库文件是否存在
if(EXISTS "${PYTHON_LIBS_DIR}/lib${PYTHON_LIB_NAME}.so")
    message(STATUS "Found prebuilt: ${PYTHON_LIBS_DIR}/lib${PYTHON_LIB_NAME}.so")
else()
    message(WARNING
        "Prebuilt Python library not found: "
        "${PYTHON_LIBS_DIR}/lib${PYTHON_LIB_NAME}.so\n"
        "The build may fail at link time.")
endif()

# -----------------------------------------------------------------------------
# 链接 Android log 库
# -----------------------------------------------------------------------------

# find_library 在 Android NDK 中查找系统库
# log: Android 日志库（__android_log_print 所需）
find_library(log-lib
    log
)

# -----------------------------------------------------------------------------
# 链接库到目标
# -----------------------------------------------------------------------------

target_link_libraries(pybridge
    ${PYTHON_LIB_NAME}    # 预编译的 CPython 共享库
    ${log-lib}            # Android log 库
)

# -----------------------------------------------------------------------------
# 支持的 ABI 架构
# -----------------------------------------------------------------------------
# arm64-v8a:   64 位 ARM（现代设备，推荐）
# armeabi-v7a: 32 位 ARM（兼容旧设备）
#
# ABI 过滤在 Gradle 的 ndk.abiFilters 中配置，CMake 会为每个 ABI
# 分别执行一次构建。ANDROID_ABI 变量由 CMake 自动设置。
# -----------------------------------------------------------------------------

message(STATUS "PyBridge CMake configuration complete for ABI: ${ANDROID_ABI}")
