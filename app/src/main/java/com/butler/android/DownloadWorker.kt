package com.butler.android

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelName = inputData.getString("modelName") ?: return Result.failure()
        val downloadUrl = inputData.getString("url") ?: return Result.failure()
        val targetFileName = inputData.getString("fileName") ?: "model.tflite"
        val version = inputData.getString("version") ?: "v1"
        val token = inputData.getString("token") // Optional HF OAuth token

        val normalizedName = modelName.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val targetDir = File(applicationContext.getExternalFilesDir(null), "$normalizedName/$version")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val targetFile = File(targetDir, targetFileName)

        Log.i("DownloadWorker", "Starting download for $modelName from $downloadUrl to ${targetFile.absolutePath}")

        var connection: HttpURLConnection? = null
        try {
            val url = URL(downloadUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000

            if (!token.isNullOrEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                Log.e("DownloadWorker", "Server returned non-OK status: $responseCode")
                return Result.failure()
            }

            val fileLength = connection.contentLengthLong
            connection.inputStream.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    val data = ByteArray(8192)
                    var total: Long = 0
                    var count: Int
                    var lastProgressUpdate: Long = 0

                    while (inputStream.read(data).also { count = it } != -1) {
                        total += count
                        outputStream.write(data, 0, count)

                        if (fileLength > 0) {
                            val progress = (total * 100 / fileLength).toInt()
                            val now = System.currentTimeMillis()
                            // Throttle progress updates to UI to avoid overloading
                            if (now - lastProgressUpdate > 500 || progress == 100) {
                                setProgress(workDataOf("progress" to progress, "modelName" to modelName))
                                lastProgressUpdate = now
                            }
                        }
                    }
                }
            }
            Log.i("DownloadWorker", "Download complete for $modelName")
            return Result.success(workDataOf("filePath" to targetFile.absolutePath))
        } catch (e: Exception) {
            Log.e("DownloadWorker", "Error downloading model $modelName: ${e.message}", e)
            return Result.failure()
        } finally {
            connection?.disconnect()
        }
    }
}
