package com.macropad.next

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** Khoá ADB lưu lại trong bộ nhớ app để ghép đôi một lần dùng mãi */
private class KeyedManager(dir: File) : AbsAdbConnectionManager() {
    private val pk: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val kf = File(dir, "adb_key.pk8")
        val cf = File(dir, "adb_cert.der")
        var k: PrivateKey? = null
        var c: Certificate? = null
        try {
            if (kf.exists() && cf.exists()) {
                k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                c = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cf.readBytes()))
            }
        } catch (e: Exception) { k = null; c = null }

        if (k == null || c == null) {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048, SecureRandom())
            val kp = gen.generateKeyPair()
            val priv = kp.private
            val name = X500Name("CN=MacroPad2")
            val notBefore = Date(System.currentTimeMillis() - 86400000L)
            val notAfter = Date(System.currentTimeMillis() + 3650L * 86400000L)
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(System.currentTimeMillis()), notBefore, notAfter, name, kp.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(priv))
            val x = JcaX509CertificateConverter().getCertificate(holder)
            k = priv
            c = x
            try {
                kf.writeBytes(priv.encoded)
                cf.writeBytes(x.encoded)
            } catch (e: Exception) { }
        }
        pk = k!!
        cert = c!!
    }

    override fun getPrivateKey(): PrivateKey = pk
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "MacroPad2"
}

/** Cổng duy nhất để dùng ADB trong app */
object Adb {
    @Volatile private var mgr: AbsAdbConnectionManager? = null
    @Volatile var connected = false

    private fun manager(ctx: Context): AbsAdbConnectionManager {
        mgr?.let { return it }
        synchronized(this) {
            mgr?.let { return it }
            val m = KeyedManager(ctx.applicationContext.filesDir)
            mgr = m
            return m
        }
    }

    fun pair(ctx: Context, port: Int, code: String) {
        val r: Any? = manager(ctx).pair("127.0.0.1", port, code)
        if (r == false) throw IllegalStateException("Ghép đôi bị từ chối (sai mã?)")
    }

    fun connect(ctx: Context, port: Int) {
        val r: Any? = manager(ctx).connect("127.0.0.1", port)
        if (r == false) throw IllegalStateException("Kết nối bị từ chối")
        connected = true
    }

    fun shell(ctx: Context, cmd: String): String {
        val st = manager(ctx).openStream("shell:$cmd")
        try {
            return st.openInputStream().bufferedReader().readText()
        } catch (e: Exception) {
            connected = false
            throw e
        } finally {
            try { st.close() } catch (_: Exception) { }
        }
    }

    /** Tìm cổng kết nối rồi kết nối (dùng sau khi đã ghép đôi). Trả về true nếu thành công. */
    fun ensureConnected(ctx: Context): Boolean {
        if (connected) return true
        val m = Mdns(ctx, "_adb-tls-connect._tcp")
        return try {
            m.start()
            val port = m.waitPort(12000)
            if (port < 0) {
                UiLog.add("Không thấy cổng kết nối. Bật Gỡ lỗi không dây và Wi-Fi.")
                false
            } else {
                connect(ctx, port)
                UiLog.add("Đã kết nối ADB ✓")
                true
            }
        } catch (e: Throwable) {
            UiLog.add("Lỗi kết nối: ${e.javaClass.simpleName}: ${e.message}")
            connected = false
            false
        } finally {
            m.stop()
        }
    }
}
