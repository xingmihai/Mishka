package top.yukonga.mishka.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.database.ImportedDao
import top.yukonga.mishka.data.database.ImportedEntity
import top.yukonga.mishka.domain.model.ProfileType
import java.io.File
import kotlin.time.Clock

/**
 * 订阅自动更新闹钟的唯一调度点：**闹钟集合是 imported 表的派生态**。
 *
 * [start] 之后跟着表的 Flow 持续对账——新增订阅、改更新间隔立刻生效，删除订阅自动撤掉闹钟，
 * 增删改各路径都不需要记得调度（漏一处就是「设了自动更新却永远不更新」，漏另一处就是
 * 删掉的订阅仍到点唤醒 ProfileWorker）。
 *
 * 开机时 app 进程可能只为收广播而起，[ProfileReceiver] 经 ProfileWorker 调 [reconcileNow]
 * 显式对账一次，把前台服务的存活窗口借给这次 DB 读取。
 */
class ProfileUpdateScheduler(
    private val context: Context,
    private val importedDao: ImportedDao,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null

    /**
     * 已布置闹钟的 uuid。进程重启后为空——此时 DB 里已不存在的孤儿闹钟无从枚举，
     * 但它至多空跑一次 ProfileWorker（uuid 查不到即返回）且不会续期，不需要额外持久化。
     */
    private val armed = mutableSetOf<String>()

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            importedDao.getAllFlow().collect { reconcile(it) }
        }
    }

    /** 一次性对账，完成后返回。开机 / 时间变更路径用。 */
    suspend fun reconcileNow() {
        reconcile(importedDao.queryAll())
    }

    private fun reconcile(entities: List<ImportedEntity>) {
        // 只有 Url 有订阅地址可拉取；其它类型的 source 不是 http 地址
        val eligible = entities.filter { it.type == ProfileType.Url && it.interval >= MIN_INTERVAL_MS }
        val keep = eligible.mapTo(mutableSetOf()) { it.uuid }
        // 撤销：本进程布置过但已失效的（删除订阅），以及 DB 里仍在但不该有闹钟的（间隔改 0）
        ((armed + entities.map { it.uuid }) - keep).forEach { cancelNext(context, it) }
        eligible.forEach { scheduleNext(context, it) }
        armed.clear()
        armed += keep
    }

    companion object {
        private const val TAG = "ProfileUpdateScheduler"

        const val MIN_INTERVAL_MS = 15 * 60 * 1000L // 15 分钟

        /**
         * 为指定配置调度下次更新。间隔小于 [MIN_INTERVAL_MS] 或 config.yaml 尚未落盘时只撤销不布置。
         */
        fun scheduleNext(context: Context, imported: ImportedEntity) {
            val intent = pendingIntentOf(context, imported.uuid)
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return

            alarmManager.cancel(intent)
            if (imported.interval < MIN_INTERVAL_MS) return

            val current = Clock.System.now().toEpochMilliseconds()
            val configFile = File(ProfileFileOps.getImportedDir(context, imported.uuid), "config.yaml")
            val lastModified = if (configFile.exists()) configFile.lastModified() else -1L
            if (lastModified < 0) return

            val delay = (imported.interval - (current - lastModified)).coerceAtLeast(0)
            Log.i(TAG, "Schedule ${imported.uuid} (${imported.name}) in ${delay / 1000}s")
            scheduleAlarm(alarmManager, current + delay, intent)
        }

        fun cancelNext(context: Context, uuid: String) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntentOf(context, uuid))
        }

        /**
         * API 31+ 精确闹钟进入 FGS 启动豁免白名单；
         * 若用户在系统"闹钟和提醒"撤销 SCHEDULE_EXACT_ALARM，退化为 inexact，
         * 并依赖 [ProfileReceiver] 的 catch + re-arm 兜底。
         */
        fun scheduleAlarm(alarmManager: AlarmManager, triggerAt: Long, pi: PendingIntent) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }

        fun pendingIntentOf(context: Context, uuid: String): PendingIntent {
            val intent = Intent(ProfileReceiver.ACTION_PROFILE_REQUEST_UPDATE).apply {
                setPackage(context.packageName)
                data = Uri.parse("uuid://$uuid")
            }
            return PendingIntent.getBroadcast(
                context,
                uuid.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
