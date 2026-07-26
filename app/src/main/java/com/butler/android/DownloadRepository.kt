package com.butler.android

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.*
import java.util.UUID

class DownloadRepository(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    fun startModelDownload(model: Model, hfToken: String? = null): UUID {
        val inputData = Data.Builder()
            .putString("modelName", model.name)
            .putString("url", model.url)
            .putString("fileName", model.downloadFileName)
            .putString("version", model.version)
            .apply {
                if (hfToken != null) {
                    putString("token", hfToken)
                }
            }
            .build()

        val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(inputData)
            .addTag("MODEL_NAME_TAG:${model.name}")
            .build()

        workManager.enqueueUniqueWork(
            model.name,
            ExistingWorkPolicy.REPLACE,
            downloadWorkRequest
        )

        return downloadWorkRequest.id
    }

    fun getDownloadWorkInfo(id: UUID): LiveData<WorkInfo> {
        return workManager.getWorkInfoByIdLiveData(id)
    }

    fun cancelDownload(modelName: String) {
        workManager.cancelUniqueWork(modelName)
    }
}
