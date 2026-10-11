package jb.companion

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.util.Log

/***
 * boilerplate for reflective access to some PackageManagerService logic
 */
@SuppressLint("PrivateApi")
class RPMS {
    private var mSettings: Any
    private var mService: Any? = null

    init {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
        val aidl = getServiceMethod.invoke(null, "package")!!
        for (field in aidl.javaClass.declaredFields) {
            if (field.getType().getName() == "com.android.server.pm.PackageManagerService") {
                field.isAccessible = true
                mService = field.get(aidl)!!
                break
            }
        }
        val mSettingsField = mService!!.javaClass.getDeclaredField("mSettings")
        mSettingsField.isAccessible = true
        mSettings = mSettingsField.get(mService)!!
    }

    private fun findField(obj: Any, vararg names: String): java.lang.reflect.Field? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            for (name in names) {
                runCatching { c!!.getDeclaredField(name).also { it.isAccessible = true } }
                    .getOrNull()?.let { return it }
            }
            c = c!!.superclass
        }
        return null
    }

    fun getPackageLPr(packageName: String?): Any {
        val m = mSettings.javaClass.getDeclaredMethod("getPackageLPr", String::class.java)
        m.isAccessible = true
        return m.invoke(mSettings, packageName)!!
    }

    fun getSharedUserLPw(userName: String): Any {
        val m = mSettings.javaClass.declaredMethods.first { it.name == "getSharedUserLPw" }
        m.isAccessible = true
        val args: kotlin.Array<Any?> = m.parameterTypes.map { type ->
            when (type) {
                String::class.java -> userName
                Int::class.javaPrimitiveType -> 0
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        return m.invoke(mSettings, *args)!!
    }

    fun getSigningDetails(setting: Any): Any {
        // try method first, walking the class hierarchy
        var cls: Class<*>? = setting.javaClass
        while (cls != null) {
            val m = cls.declaredMethods.firstOrNull { it.name == "getSigningDetails" }
            if (m != null) {
                m.isAccessible = true
                val result = m.invoke(setting)!!
                // PackageSignatures is the wrong type — keep searching
                if (!result.javaClass.name.endsWith("PackageSignatures")) return result
            }
            cls = cls.superclass
        }
        // fall back to SigningDetails-typed field only (not PackageSignatures)
        for (name in listOf("signingDetails", "mSigningDetails")) {
            var c: Class<*>? = setting.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    val v = f.get(setting)
                    if (v != null && !v.javaClass.name.endsWith("PackageSignatures")) return v
                } catch (_: NoSuchFieldException) {}
                c = c.superclass
            }
        }
        // walk through PackageSignatures wrapper: setting.signatures.mSigningDetails
        var c2: Class<*>? = setting.javaClass
        while (c2 != null) {
            try {
                val sigsF = c2.getDeclaredField("signatures")
                sigsF.isAccessible = true
                val pkgSigs = sigsF.get(setting)
                if (pkgSigs != null && pkgSigs.javaClass.name.endsWith("PackageSignatures")) {
                    var c3: Class<*>? = pkgSigs.javaClass
                    while (c3 != null) {
                        try {
                            val sdF = c3.getDeclaredField("mSigningDetails")
                            sdF.isAccessible = true
                            val sd = sdF.get(pkgSigs)
                            if (sd != null) return sd
                        } catch (_: NoSuchFieldException) {}
                        c3 = c3.superclass
                    }
                }
                break
            } catch (_: NoSuchFieldException) {}
            c2 = c2.superclass
        }
        // enumerate all fields to aid debugging
        val fields = buildString {
            var c: Class<*>? = setting.javaClass
            while (c != null) {
                c.declaredFields.forEach { append("${c.simpleName}.${it.name}:${it.type.simpleName} ") }
                c = c.superclass
            }
        }
        Log.e(TAG, "[~] getSigningDetails fields on ${setting.javaClass.simpleName}: $fields")
        throw NoSuchMethodException("getSigningDetails not found on ${setting.javaClass.name}")
    }

    fun writeSettings() {
        val m = mService!!.javaClass.getDeclaredMethod("writeSettings", Boolean::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(mService, true)
    }

    fun getSignatures(signingDetails: Any): List<Any> {
        val arr = signingDetails.javaClass.getMethod("getSignatures").invoke(signingDetails)!!
        val len = java.lang.reflect.Array.getLength(arr)
        return (0 until len).mapNotNull { java.lang.reflect.Array.get(arr, it) }
    }

    fun getSignatureBytes(sig: Any): ByteArray? = runCatching {
        sig.javaClass.getMethod("toByteArray").invoke(sig) as ByteArray
    }.getOrElse {
        findField(sig, "mSignature")?.get(sig) as? ByteArray
    }

    fun getPastSigningCertificates(sd: Any): List<Any> {
        val arr = findField(sd, "mPastSigningCertificates", "pastSigningCertificates")?.get(sd)
            ?: return emptyList()
        val len = java.lang.reflect.Array.getLength(arr)
        return (0 until len).mapNotNull { java.lang.reflect.Array.get(arr, it) }
    }

    fun setPastSigningCertificates(signingDetails: Any, value: Any?) {
        (findField(signingDetails, "mPastSigningCertificates", "pastSigningCertificates")
            ?: throw NoSuchFieldException("pastSigningCertificates not found on ${signingDetails.javaClass.name}"))
            .set(signingDetails, value)
    }

    fun getSharedUserPackages(sysUser: Any): List<String> {
        val packagesField = findField(sysUser, "packages", "mPackages", "mPackageSettings", "packageSettings")
            ?: return emptyList()
        val packages = packagesField.get(sysUser)
        val inner = packages?.let { findField(it, "mStorage")?.get(it) } ?: packages ?: return emptyList()
        val count = runCatching { inner.javaClass.getMethod("size").invoke(inner) }.getOrNull() as? Int
            ?: return emptyList()
        val valueAtFn = runCatching { inner.javaClass.getMethod("valueAt", java.lang.Integer.TYPE) }.getOrNull()
            ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            val pkg = valueAtFn.invoke(inner, i) ?: return@mapNotNull null
            val flags = findField(pkg, "mPkgFlags", "pkgFlags")?.get(pkg) as? Int ?: return@mapNotNull null
            if (flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
            (runCatching { pkg.javaClass.getMethod("getPackageName").invoke(pkg) }.getOrNull()
                ?: runCatching { pkg.javaClass.getMethod("getName").invoke(pkg) }.getOrNull()
                ?: pkg.javaClass.simpleName).toString()
        }
    }

    fun setSignatureFlags(sig: Any, flags: Int) {
        sig.javaClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(sig, flags)
    }

    fun createPastSigningCertsArray(cert: Any): Any {
        // checkCapability() loops 0..length-2, skipping last — 1 cert means loop never runs
        // and capability check fails. Index 0 is checked, index 1 is the dummy tail.
        val arr = java.lang.reflect.Array.newInstance(cert.javaClass, 2)
        java.lang.reflect.Array.set(arr, 0, cert)
        java.lang.reflect.Array.set(arr, 1, cert)
        return arr
    }

    fun setBlockUninstallLPw(userId: Int, packageName: String?, blockUninstall: Boolean) {
        val m = mSettings!!.javaClass.getDeclaredMethod(
            "setBlockUninstallLPw", Int::class.javaPrimitiveType,
            String::class.java, Boolean::class.javaPrimitiveType
        )
        m.isAccessible = true
        m.invoke(mSettings, userId, packageName, blockUninstall)
    }
}
