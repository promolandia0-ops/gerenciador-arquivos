package br.geracaoai.arquivos

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.provider.Settings
import java.io.File

data class AppItem(val pkg: String, val label: String, val system: Boolean, val hidden: Boolean, val apk: String, val splits: List<String>,
                   val dataDir: String, val size: Long, val version: String, val icon: Drawable?)

object Apps {
    fun load(ctx: Context): List<AppItem> {
        val pm = ctx.packageManager
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val launcherPkgs = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet()
        return pm.getInstalledApplications(0).map { ai ->
            val apkSize = (listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).sumOf { File(it).length() }
            val total = runCatching { val s = ssm.queryStatsForPackage(ai.storageUuid, ai.packageName, Process.myUserHandle()); s.appBytes + s.dataBytes + s.cacheBytes }.getOrDefault(apkSize)
            AppItem(ai.packageName, ai.loadLabel(pm).toString(), ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, ai.packageName !in launcherPkgs,
                ai.sourceDir, ai.splitSourceDirs?.toList() ?: emptyList(), ai.dataDir ?: "", total,
                runCatching { pm.getPackageInfo(ai.packageName, 0).versionName ?: "" }.getOrDefault(""), runCatching { ai.loadIcon(pm) }.getOrNull())
        }.sortedBy { it.label.lowercase() }
    }
    fun info(ctx: Context, pkg: String) = ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun uninstall(ctx: Context, pkg: String) = ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun open(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } ?: false
    fun extract(a: AppItem): File {
        val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "APKs extraídos").apply { mkdirs() }
        val safe = a.label.replace(Regex("[^\\w .-]"), "_")
        File(a.apk).copyTo(File(d, "${safe}_${a.version}.apk"), true)
        a.splits.forEachIndexed { i, s -> File(s).copyTo(File(d, "${safe}_${a.version}_split$i.apk"), true) }
        return d
    }
    fun usageGranted(ctx: Context) = runCatching {
        val ao = ctx.getSystemService(android.app.AppOpsManager::class.java)
        ao.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
