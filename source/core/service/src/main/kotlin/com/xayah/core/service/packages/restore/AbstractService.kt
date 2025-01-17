package com.xayah.core.service.packages.restore

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.OpType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.util.withIOContext
import com.xayah.core.service.R
import com.xayah.core.service.util.PackagesRestoreUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.PreparationUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi

@AndroidEntryPoint
internal abstract class AbstractService : Service() {
    companion object {
        private const val TAG = "PackagesRestoreServiceImpl"
    }

    private val binder = OperationLocalBinder()

    override fun onBind(intent: Intent): IBinder {
        startForeground(1, NotificationUtil.getForegroundNotification(applicationContext))
        return binder
    }

    inner class OperationLocalBinder : Binder() {
        fun getService(): AbstractService = this@AbstractService
    }

    private val mutex = Mutex()
    private val context by lazy { applicationContext }

    internal fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    abstract val pathUtil: PathUtil
    abstract val taskDao: TaskDao
    abstract val packageDao: PackageDao
    abstract val packagesRestoreUtil: PackagesRestoreUtil
    abstract val taskRepository: TaskRepository

    private val notificationBuilder by lazy { NotificationUtil.getProgressNotificationBuilder(context) }
    private var startTimestamp: Long = 0
    private var endTimestamp: Long = 0
    internal val taskEntity by lazy {
        TaskEntity(
            id = 0,
            opType = OpType.RESTORE,
            taskType = TaskType.PACKAGE,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            backupDir = context.localBackupSaveDir(),
            rawBytes = 0.toDouble(),
            availableBytes = 0.toDouble(),
            totalBytes = 0.toDouble(),
            totalCount = 0,
            successCount = 0,
            failureCount = 0,
            isProcessing = true,
        )
    }

    suspend fun preprocessing() = withIOContext {
        mutex.withLock {
            startTimestamp = DateUtil.getTimestamp()

            NotificationUtil.notify(context, notificationBuilder, context.getString(R.string.restoring), context.getString(R.string.preprocessing))
            log { "Preprocessing is starting." }

            log { "Trying to enable adb install permissions." }
            PreparationUtil.setInstallEnv()
        }
    }

    abstract suspend fun restorePackage(p: PackageEntity)

    @ExperimentalSerializationApi
    suspend fun processing() = withIOContext {
        mutex.withLock {
            log { "Processing is starting." }

            // createTargetDirs() before readStatFs().
            taskEntity.also {
                it.startTimestamp = startTimestamp
                it.rawBytes = taskRepository.getRawBytes(TaskType.PACKAGE)
                it.availableBytes = taskRepository.getAvailableBytes(OpType.RESTORE)
                it.totalBytes = taskRepository.getTotalBytes(OpType.RESTORE)
                it.id = taskDao.upsert(it)
            }

            val packages = packageDao.queryActivated()
            log { "Task count: ${packages.size}." }
            taskEntity.also {
                it.totalCount = packages.size
                taskDao.upsert(it)
            }

            packages.forEachIndexed { index, currentPackage ->
                NotificationUtil.notify(
                    context,
                    notificationBuilder,
                    context.getString(R.string.restoring),
                    currentPackage.packageInfo.label,
                    packages.size,
                    index
                )
                log { "Current package: $currentPackage" }

                restorePackage(currentPackage)
            }
        }
    }

    @ExperimentalSerializationApi
    suspend fun postProcessing() = withIOContext {
        mutex.withLock {
            NotificationUtil.notify(
                context,
                notificationBuilder,
                context.getString(R.string.restoring),
                context.getString(R.string.wait_for_remaining_data_processing)
            )
            log { "PostProcessing is starting." }

            packageDao.clearActivated()
            endTimestamp = DateUtil.getTimestamp()
            taskEntity.also {
                it.endTimestamp = endTimestamp
                it.isProcessing = false
                taskDao.upsert(it)
            }
            val time = DateUtil.getShortRelativeTimeSpanString(context = context, time1 = startTimestamp, time2 = endTimestamp)
            NotificationUtil.notify(
                context,
                notificationBuilder,
                context.getString(R.string.restore_completed),
                "${time}, ${taskEntity.successCount} ${context.getString(R.string.succeed)}, ${taskEntity.failureCount} ${context.getString(R.string.failed)}",
                ongoing = false
            )
        }
    }
}
