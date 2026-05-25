package com.example.activator2

import android.app.AlertDialog
import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import kotlinx.coroutines.*

import kotlinx.coroutines.suspendCancellableCoroutine
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import java.security.Security
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class SshManager(private val context: Context) {

    var sshClient: SSHClient? = null

    fun setupSsh(hostIp: String, username: String, password: String) {
        Security.removeProvider("BC")
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())

        sshClient = SSHClient().apply {
            addHostKeyVerifier(PromiscuousVerifier())
            useCompression()
            connect(hostIp)
            authPassword(username, password)
            connection.keepAlive.keepAliveInterval = 60
        }
    }

    suspend fun discoverHost(): Triple<String, String, String> {
        val results = mutableListOf<Triple<String, String, String>>()

        withContext(Dispatchers.IO) {  // ← wrap all the network work
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("jmDNS")
            lock.acquire()

            val rawIp = wifi.connectionInfo.ipAddress
            if (rawIp == 0) throw Exception("Could not get device IP — are you on WiFi?")
            val wifiIp = java.net.InetAddress.getByName(Formatter.formatIpAddress(rawIp))

            try {
                val jmdns = JmDNS.create(wifiIp)

                jmdns.addServiceListener("_bed._tcp.local.", object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        jmdns.requestServiceInfo(event.type, event.name)
                    }
                    override fun serviceRemoved(event: ServiceEvent) {}
                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info
                        val username = info.getPropertyString("username") ?: return
                        val hostname = info.server
                        val ip = info.inetAddresses
                            .filterIsInstance<java.net.Inet4Address>()
                            .firstOrNull()?.hostAddress ?: return
                        synchronized(results) {
                            results.add(Triple(username, hostname, ip))
                        }
                    }
                })

                val timeout = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < timeout) {
                    Thread.sleep(500)
                }
                jmdns.close()
            } finally {
                lock.release()
            }
        }

        if (results.isEmpty()) throw Exception("No _bed._tcp hosts found")
        if (results.size == 1) return results[0]

        // UI interaction — needs an Activity context for the dialog
        val activity = context as? android.app.Activity
            ?: throw Exception("Context must be an Activity to show host picker")

        return suspendCoroutine { continuation ->
            activity.runOnUiThread {
                val labels = results.map { (username, hostname, _) -> "$username@$hostname" }
                AlertDialog.Builder(context)
                    .setTitle("Select Host")
                    .setItems(labels.toTypedArray()) { _, index ->
                        continuation.resume(results[index])
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    suspend fun fetchAndSelectAgent(): String {
        val names = withContext(Dispatchers.IO) {
            val session = sshClient!!.startSession()
            val cmd = session.exec("cat \$HOME/.vla_stars.jsonl")
            cmd.join()
            val output = cmd.inputStream.bufferedReader().readText()
            session.close()

            output.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try { org.json.JSONObject(line).getString("name") }
                    catch (e: Exception) { null }
                }
        }

        if (names.isEmpty()) throw Exception("No agents found in vla_stars.jsonl")
        if (names.size == 1) return names[0]

        val activity = context as? android.app.Activity
            ?: throw Exception("Context must be an Activity to show agent picker")

        return suspendCoroutine { continuation ->
            activity.runOnUiThread {
                AlertDialog.Builder(context)
                    .setTitle("Select Agent")
                    .setItems(names.toTypedArray()) { _, index ->
                        continuation.resume(names[index])
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    fun disconnect() {
        sshClient?.disconnect()
        sshClient = null
    }
}