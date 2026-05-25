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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
class MainActivity : BaseActivity() {

    // UI
    private lateinit var btnConnect: Button
    private lateinit var etPassword: EditText
    private lateinit var btnSend: Button
    private lateinit var etInput: EditText
    private lateinit var tvOutput: TextView
    private lateinit var scrollView: ScrollView

    // SSH / Socket state
    private lateinit var sshManager: SshManager

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

        setupSpinner(currentIndex = 0)

        sshManager = SshManager(this)

    }

    // ─── PHASE 1: Connect ────────────────────────────────────────────────────

    private fun onConnectClicked() {
        btnConnect.isEnabled = false
        appendOutput("Scanning for host...")

        scope.launch {
            try {
                val (username, hostname, ip) = sshManager.discoverHost()
                appendOutput("Found host: $username@$hostname ($ip)")
                appendOutput("Connecting via SSH...")

                withContext(Dispatchers.IO) {
                    sshManager.setupSsh(ip, username, etPassword.text.toString())
                }
                appendOutput("SSH connected.")

                val agentName = sshManager.fetchAndSelectAgent()

                appendOutput("Launching $agentName...")

                withContext(Dispatchers.IO) { launchAgentAndForward(agentName) }
                appendOutput("Agent running. Opening chat socket...")

                withContext(Dispatchers.IO) { openChatSocket() }
                startChatReadLoop()
                appendOutput("Ready.\n")

                btnSend.isEnabled = true
                btnConnect.text = "Disconnect"
                btnConnect.setOnClickListener { onDisconnectClicked() }
                btnConnect.isEnabled = true
            } catch (e: Exception) {
                appendOutput("Error: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
                btnConnect.isEnabled = true
            }
        }
    }
    private fun onDisconnectClicked() {
        scope.launch(Dispatchers.IO) {
            try {
                chatSocket?.close()
                sshManager.disconnect()
            } catch (e: Exception) {
                // ignore errors on close
            } finally {
                chatSocket = null
                chatOutputStream = null
                chatReader = null

                withContext(Dispatchers.Main) {
                    appendOutput("Disconnected.\n")
                    btnConnect.text = "Find Host & Connect"
                    btnConnect.isEnabled = true
                    btnConnect.setOnClickListener { onConnectClicked() }
                    btnSend.isEnabled = false
                }
            }
        }
    }

    private fun launchAgentAndForward(agentName: String) {
        val session = sshManager.sshClient!!.startSession()


        // Step 1: Launch the agent script
        session.exec("bash \"\$VLA_STAR_PATH\"/run_commands/host/run_minimal_vla_star.sh \"$agentName\"")

        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            val checkSession = sshManager.sshClient!!.startSession()
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
                sshManager.sshClient!!.newLocalPortForwarder(params, serverSocket).listen()
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
        sshManager.sshClient?.close()
    }
}