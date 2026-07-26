package com.butler.android

import android.content.Context
import android.graphics.Bitmap

interface ResultListener {
    fun onResult(partialResult: String, done: Boolean)
    fun onError(errorMessage: String)
}

interface LlmModelHelper {
    fun initialize(
        context: Context,
        model: Model,
        onDone: (Boolean) -> Unit
    )

    fun runInference(
        input: String,
        images: List<Bitmap> = emptyList(),
        resultListener: ResultListener
    )

    fun stopResponse()

    fun cleanUp(onDone: () -> Unit)
}
