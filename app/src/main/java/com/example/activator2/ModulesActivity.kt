package com.example.activator2

import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import android.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModulesActivity : BaseActivity() {
    private lateinit var moduleSelector: LinearLayout
    private lateinit var tvModuleSelected: TextView
    private var selectedModule: String? = null
    private lateinit var btnConnect: Button
    private lateinit var etPassword: EditText
    private lateinit var sshManager: SshManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_modules)

        setupSpinner(currentIndex = 2)

        moduleSelector = findViewById(R.id.moduleSelector)
        tvModuleSelected = findViewById(R.id.tvModuleSelected)

        moduleSelector.setOnClickListener { showModuleMenu() }
        btnConnect = findViewById(R.id.btnConnect)
        etPassword = findViewById(R.id.etPassword)
        sshManager = SshManager(this)
    }

    private fun showModuleMenu() {
        val popup = PopupMenu(this, moduleSelector)
        popup.menu.add(0, 0, 0, "Teleop")
        // add more here later: popup.menu.add(0, 1, 1, "AnotherModule")

        popup.setOnMenuItemClickListener { item ->
            selectedModule = item.title.toString()
            tvModuleSelected.text = selectedModule
            true
        }
        popup.show()
    }

    private fun onConnectClicked() {
        btnConnect.isEnabled = false

        scope.launch {
            try {
                val (username, hostname, ip) = sshManager.discoverHost()

                withContext(Dispatchers.IO) {
                    sshManager.setupSsh(ip, username, etPassword.text.toString())
                }

                val agentName = sshManager.fetchAndSelectAgent()


                withContext(Dispatchers.IO) { forwardModule(agentName) }

                // Open socket...


                btnConnect.text = "Disconnect"
                btnConnect.setOnClickListener { onDisconnectClicked() }
                btnConnect.isEnabled = true
            } catch (e: Exception) {
                e.printStackTrace()
                btnConnect.isEnabled = true
            }
        }
    }

    private fun onDisconnectClicked() {
        scope.launch(Dispatchers.IO) {
            try {
                sshManager.disconnect()
            } catch (e: Exception) {
                // ignore errors on close
            } finally {
                withContext(Dispatchers.Main) {
                    btnConnect.text = "Find Host & Connect"
                    btnConnect.isEnabled = true
                    btnConnect.setOnClickListener { onConnectClicked() }
                }
            }
        }
    }

    private fun forwardModule(agentName: String){
        // Should forward the port
    }
}