package jb.companion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@SuppressLint("MissingPermission", "RequestInstallPackagesPolicy")
object Utils {
    @JvmStatic
    fun sh(cmd: String?): String {
        return Shell.cmd(cmd ?: "").exec().out.joinToString("\n").trim()
    }

    @JvmStatic
    fun copyFromUri(context: Context, sourceUri: Uri, destPath: String?): Boolean {
        try {
            context.getContentResolver().openInputStream(sourceUri).use { `in` ->
                FileOutputStream(destPath).use { out ->
                    if (`in` == null) return false
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes: Long = 0
                    while ((`in`.read(buffer).also { bytesRead = it }) != -1) {
                        out.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead.toLong()
                    }
                    out.flush()
                    return totalBytes > 0
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("JBCompanion", "[x] copyFromUri failed: $sourceUri -> $destPath", e)
            return false
        }
    }

    fun uninstall(context: Context, packageName: String) {
        context.packageManager.packageInstaller.uninstall(packageName,
            PendingIntent.getActivity(context, 0,
                Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender)
    }

    fun install(context: Context, apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val session = installer.openSession(installer.createSession(params))
        session.use { session ->
            FileInputStream(apkFile).use { `in` ->
                session.openWrite("poc", 0, apkFile.length()).use { out ->
                    val buffer = ByteArray(65536)
                    var length: Int
                    while ((`in`.read(buffer).also { length = it }) != -1)
                        out.write(buffer, 0, length)
                    session.fsync(out)
                }
            }
            session.commit(PendingIntent.getActivity(context, 0,
                Intent().setPackage("android"), PendingIntent.FLAG_MUTABLE).intentSender)
        }
    }
}
