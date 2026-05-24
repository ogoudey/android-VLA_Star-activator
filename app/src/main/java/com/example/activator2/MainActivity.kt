package com.example.activator2

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import java.net.ServerSocket
import java.security.Security

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var btnConnect: Button
    private lateinit var etPassword: EditText
    private lateinit var btnSend: Button
    private lateinit var etInput: EditText
    private lateinit var tvOutput: TextView
    private lateinit var scrollView: ScrollView

    // SSH / Socket state
    private var sshClient: SSHClient? = null
    private var chatSocket: Socket? = null
    private var chatOutputStream: java.io.OutputStream? = null
    private var chatReader: BufferedReader? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.btnConnect)
        etPassword = findViewById(R.id.etPassword)
        btnSend    = findViewById(R.id.btnSend)
        etInput    = findViewById(R.id.etInput)
        tvOutput   = findViewById(R.id.tvOutput)
        scrollView = findViewById(R.id.scrollView)

        btnSend.isEnabled = false  // disabled until connected

        btnConnect.setOnClickListener { onConnectClicked() }
        btnSend.setOnClickListener { onSendClicked() }
    }

    // ─── PHASE 1: Connect ────────────────────────────────────────────────────

    private fun onConnectClicked() {
        btnConnect.isEnabled = false
        appendOutput("Scanning for host...")

        scope.launch {
            try {
                val (username, hostname, ip) = withContext(Dispatchers.IO) { discoverHost() }
                appendOutput("Found host: $username@$hostname ($ip)")
                appendOutput("Connecting via SSH...")

                val password = findViewById<EditText>(R.id.etPassword).text.toString()
                withContext(Dispatchers.IO) { setupSsh(ip, username, password) }
                appendOutput("SSH connected. Launching agent...")

                withContext(Dispatchers.IO) { launchAgentAndForward() }
                appendOutput("Agent running. Opening chat socket...")

                withContext(Dispatchers.IO) { openChatSocket() }
                startChatReadLoop()
                appendOutput("Ready.\n")

                btnSend.isEnabled = true

            } catch (e: Exception) {
                appendOutput("Error: ${e.message}")
                btnConnect.isEnabled = true
            }
        }
    }

    private fun discoverHost(): Triple<String, String, String> {
        // returns Triple(username, hostname, ipAddress)
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        val lock = wifi.createMulticastLock("jmDNS")
        lock.acquire()

        val wifiIp = java.net.InetAddress.getByName(
            android.text.format.Formatter.formatIpAddress(wifi.connectionInfo.ipAddress)
        )

        var result: Triple<String, String, String>? = null

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

                    result = Triple(username, hostname, ip)
                }
            })

            // Poll until found (mirrors your bash `while true; sleep 1`)
            val timeout = System.currentTimeMillis() + 15_000
            while (result == null && System.currentTimeMillis() < timeout) {
                Thread.sleep(500)
            }

            jmdns.close()
        } finally {
            lock.release()
        }

        return result ?: throw Exception("No _bed._tcp host found :(")
    }

    private fun setupSsh(hostIp: String, username: String, password: String) {
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



    private fun launchAgentAndForward() {
        // Step 1: Launch the agent script
        val session = sshClient!!.startSession()
        session.exec("bash \"\$VLA_STAR_PATH\"/run_commands/host/run_minimal_vla_star.sh")

        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            val checkSession = sshClient!!.startSession()
            val check = checkSession.exec("ss -tlnp | grep 5001")
            check.join()
            val output = check.inputStream.bufferedReader().readText()
            checkSession.close()
            if (output.contains("5001")) {
                runOnUiThread { appendOutput("Agent port is up.") }
                break
            }
            Thread.sleep(1000)
        }

        // Step 2: Set up port forward in a background thread
        // (newLocalPortForwarder.listen() blocks, so it needs its own thread)
        val params = Parameters("127.0.0.1", 5001, "127.0.0.1", 5001)
        val serverSocket = java.net.ServerSocket(5001)

        Thread {
            try {
                runOnUiThread { appendOutput("DEBUG: forwarder thread started") }
                sshClient!!.newLocalPortForwarder(params, serverSocket).listen()
                runOnUiThread { appendOutput("DEBUG: forwarder listen() exited") }
            } catch (e: Exception) {
                runOnUiThread { appendOutput("DEBUG: forwarder error: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()

        // Give the forwarder a moment to start listening
        Thread.sleep(500)
    }

    private fun openChatSocket() {
        runOnUiThread { appendOutput("DEBUG: opening chat socket...") }

        chatSocket = Socket("127.0.0.1", 5001)
        chatOutputStream = chatSocket!!.getOutputStream()
        runOnUiThread { appendOutput("DEBUG: chat socket connected") }
        chatReader = BufferedReader(InputStreamReader(chatSocket!!.getInputStream()))
        runOnUiThread { appendOutput("DEBUG: reader/writer ready") }
    }

    private fun startChatReadLoop() {
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val line = chatReader!!.readLine() ?: break
                    withContext(Dispatchers.Main) {
                        appendOutput("Agent: $line")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("Connection lost: ${e.message}")
                    btnConnect.isEnabled = true
                    btnSend.isEnabled = false
                }
            }
        }
    }
    // ─── PHASE 2: Chat ───────────────────────────────────────────────────────

    private fun onSendClicked() {
        val message = etInput.text.toString().trim()
        if (message.isEmpty()) return
        etInput.text.clear()

        appendOutput("You: $message")

        scope.launch(Dispatchers.IO) {
            try {
                chatOutputStream!!.write((message + "\n").toByteArray())
                chatOutputStream!!.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("Error: connection lost — ${e.message}")
                    btnSend.isEnabled = false
                    btnConnect.isEnabled = true
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun appendOutput(text: String) {
        tvOutput.append("$text\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        chatSocket?.close()
        sshClient?.close()
    }
}