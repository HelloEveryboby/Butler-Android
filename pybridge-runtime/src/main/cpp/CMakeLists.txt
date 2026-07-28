# PyBridge - CMake 构建配置
# 构建 libpybridge.so，包含：
#   1. Python 运行时嵌入 (pybridge_core.c)
#   2. Python↔Java 双向互调桥 (pybridge_java_bridge.c)

cmake_minimum_required(VERSION 3.18.1)
project("pybridge")

# ─── 查找 Python 3 ──────────────────────────────────────────────
# Android 交叉编译时，Python 需要预编译。使用 PYTHON_INCLUDE_DIR 和 PYTHON_LIBRARY 指定路径。
# 如果未设置，尝试自动查找系统 Python（仅用于本地开发/测试）。

set(PYTHON_INCLUDE_DIR "" CACHE PATH "Path to Python include directory")
set(PYTHON_LIBRARY "" CACHE FILEPATH "Path to libpython3.x.so")

if(PYTHON_INCLUDE_DIR AND PYTHON_LIBRARY)
    message(STATUS "Using Python from: ${PYTHON_INCLUDE_DIR}")
    set(PYTHON_FOUND TRUE)
    set(PYTHON_INCLUDE_DIRS ${PYTHON_INCLUDE_DIR})
    set(PYTHON_LIBRARIES ${PYTHON_LIBRARY})
else()
    # 尝试自动查找（主机开发用）
    find_package(Python3 COMPONENTS Development)
    if(Python3_FOUND)
        set(PYTHON_INCLUDE_DIRS ${Python3_INCLUDE_DIRS})
        set(PYTHON_LIBRARIES ${Python3_LIBRARIES})
        message(STATUS "Found Python3: ${Python3_VERSION}")
    else()
        message(WARNING "Python3 not found. Set PYTHON_INCLUDE_DIR and PYTHON_LIBRARY for cross-compilation.")
        message(WARNING "For Android NDK build, run: export PYTHON_INCLUDE_DIR=/path/to/python/include")
        message(WARNING "                       export PYTHON_LIBRARY=/path/to/libpython3.11.so")
    endif()
endif()

# ─── 源文件 ─────────────────────────────────────────────────────

file(GLOB pybridge_sources
    ${CMAKE_CURRENT_SOURCE_DIR}/*.c
)

add_library(pybridge SHARED ${pybridge_sources})

# ─── 编译选项 ───────────────────────────────────────────────────

target_compile_options(pybridge PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fvisibility=hidden
    -fPIC
)

# ─── 包含目录 ──────────────────────────────────────────────────

target_include_directories(pybridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${PYTHON_INCLUDE_DIRS}
)

# ─── 链接库 ─────────────────────────────────────────────────────

target_link_libraries(pybridge
    # Android 系统库
    log

    # Python 运行时
    ${PYTHON_LIBRARIES}
)

# ─── 导出符号 ──────────────────────────────────────────────────
# pybridge_java_bridge.c 中标记了 visibility("default") 的函数需要被 ctypes 调用

set_target_properties(pybridge PROPERTIES
    LINK_FLAGS "-Wl,--exclude-libs,ALL"
)

# ─── 构建后处理 ─────────────────────────────────────────────────
# 将 .so 复制到 jniLibs 目录（可选，Gradle 插件会处理）

if(ANDROID_ABI)
    set(JNILIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
    add_custom_command(TARGET pybridge POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory ${JNILIBS_DIR}
        COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:pybridge> ${JNILIBS_DIR}/
        COMMENT "Copying libpybridge.so to ${JNILIBS_DIR}"
    )
endif()