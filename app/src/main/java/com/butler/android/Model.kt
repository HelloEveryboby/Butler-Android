package com.butler.android

enum class RuntimeType {
    LITERT_LM,
    AICORE
}

data class Model(
    val name: String,
    val displayName: String,
    val url: String,
    val sizeInBytes: Long,
    val downloadFileName: String,
    val version: String,
    val isLlm: Boolean = true,
    val runtimeType: RuntimeType,
    val minDeviceMemoryInGb: Int? = null,
    val localFileRelativeDirPathOverride: String? = null
)
