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
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.schmizz.sshj.connection.channel.direct.Parameters
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableException
import java.io.DataOutputStream
import java.net.InetSocketAddress
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.SurfaceView
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

class ModulesActivity : BaseActivity() {
    private lateinit var moduleSelector: LinearLayout
    private lateinit var tvModuleSelected: TextView
    private var selectedModule: String? = null
    val modulesToPorts: Map<String, Int> = mapOf("Teleop" to 5004)
    private lateinit var tvOutput: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnConnect: Button
    private lateinit var etPassword: EditText
    private lateinit var sshManager: SshManager

    private var moduleSocket: Socket? = null
    private var moduleSocketOutputStream: java.io.OutputStream? = null
    private var moduleSocketReader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var arSession: Session? = null
    private var arStreamJob: Job? = null
    private var arThread: Thread? = null
    private var eglDisplay: android.opengl.EGLDisplay? = null
    private var eglContext: android.opengl.EGLContext? = null
    private var eglSurface: android.opengl.EGLSurface? = null
    private var cameraTextureId: Int = -1
    private val CAMERA_PERMISSION_CODE = 1001
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_modules)

        setupSpinner(currentIndex = 2)

        moduleSelector = findViewById(R.id.moduleSelector)
        tvModuleSelected = findViewById(R.id.tvModuleSelected)
        moduleSelector.setOnClickListener { showModuleMenu() }
        tvOutput   = findViewById(R.id.tvOutput)
        scrollView = findViewById(R.id.scrollView)
        btnConnect = findViewById(R.id.btnConnect)
        btnConnect.setOnClickListener { onConnectClicked() }
        btnConnect.isEnabled = false
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
            btnConnect.isEnabled = true
            appendOutput("Selected $selectedModule module")
            true
        }
        popup.show()

    }

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

                val agentName = sshManager.fetchAndSelectAgent()

                // Here we should map from selected module to a task which does all this
                withContext(Dispatchers.IO) { forwardPort() }

                withContext(Dispatchers.IO) { openModuleSocket() }
                withContext(Dispatchers.Main) {
                    requestCameraPermissionIfNeeded { initArCoreAndStream() }
                }
                appendOutput("Sensors on.")

                btnConnect.text = "Disconnect"
                btnConnect.setOnClickListener { onDisconnectClicked() }
                btnConnect.isEnabled = true
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    appendOutput("Connection error: ${e.message}")
                    stopArStream() // ← clean up ARCore if anything in the connect flow fails
                }
                btnConnect.isEnabled = true
            }
        }
    }

    private fun onDisconnectClicked() {
        scope.launch(Dispatchers.IO) {
            try {
                sshManager.disconnect()
                stopArStream()
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

    private fun forwardPort(){
        val modulePort = modulesToPorts.getOrDefault(selectedModule, -1)
        val params = Parameters("127.0.0.1", modulePort, "127.0.0.1", modulePort)
        val serverSocket = java.net.ServerSocket(modulePort)

        Thread {
            try {
                sshManager.sshClient!!.newLocalPortForwarder(params, serverSocket).listen()
                runOnUiThread { appendOutput("DEBUG: forwarder listen() exited") }
            } catch (e: Exception) {
                // Nothing...
            }
        }.start()

        // Give the forwarder a moment to start listening
        Thread.sleep(500)
    }

    private fun openModuleSocket() {
        val modulePort = modulesToPorts.getOrDefault(selectedModule, -1)
        runOnUiThread { appendOutput("DEBUG: opening moduleSocket socket...") }

        moduleSocket = Socket().apply {
            tcpNoDelay = true                          // ← Critical for low latency
            connect(InetSocketAddress("127.0.0.1", modulePort))
        }
        moduleSocketOutputStream = DataOutputStream(moduleSocket!!.getOutputStream())
        runOnUiThread { appendOutput("DEBUG: moduleSocket socket connected") }
    }
    private fun appendOutput(text: String) {
        tvOutput.append("$text\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun initArCoreAndStream() {
        when (ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
            ArCoreApk.Availability.UNKNOWN_ERROR,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> {
                appendOutput("ERROR: ARCore not available")
                return
            }
            else -> {}
        }
        runOnUiThread { appendOutput("DEBUG: ARCore available") }
        val arSurface = findViewById<SurfaceView>(R.id.arSurface)
        if (arSurface == null) {
            appendOutput("ERROR: arSurface not found in layout")
            return
        }
        appendOutput("DEBUG: arSurface found, making visible")
        arSurface.visibility = View.VISIBLE
        runOnUiThread { appendOutput("DEBUG: arSurface visible...") }
        // Wait for surface to be ready before creating GL context
        arSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    val textureId = createGlTexture(arSurface)
                    arSession = Session(this@ModulesActivity).also { session ->
                        session.setCameraTextureName(textureId)
                        val config = Config(session).apply {
                            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            focusMode = Config.FocusMode.AUTO
                        }
                        session.configure(config)
                        session.resume()
                    }
                    appendOutput("ARCore session started")
                    startArStream()
                } catch (e: Exception) {
                    appendOutput("ERROR starting ARCore: ${e.message}")
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                arSession?.setDisplayGeometry(Surface.ROTATION_0, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopArStream()
            }
        })
    }


    private fun startArStream() {
        val out = moduleSocketOutputStream as? DataOutputStream ?: return

        arThread = Thread {
            // Bind GL context to this exact thread
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            try {
                while (!Thread.currentThread().isInterrupted) {
                    val frame = try {
                        arSession?.update() ?: break
                    } catch (e: Exception) {
                        runOnUiThread { appendOutput("AR update error: ${e.message}") }
                        break
                    }

                    val camera = frame.camera
                    if (camera.trackingState != TrackingState.TRACKING) {
                        Thread.sleep(100)
                        continue
                    }

                    val pose = camera.pose
                    val t = pose.translation
                    val q = pose.rotationQuaternion

                    try {
                        out.writeFloat(t[0])
                        out.writeFloat(t[1])
                        out.writeFloat(t[2])
                        out.writeFloat(q[0])
                        out.writeFloat(q[1])
                        out.writeFloat(q[2])
                        out.writeFloat(q[3])
                        out.flush()
                    } catch (e: Exception) {
                        // Socket closed or broken — stop streaming silently
                        runOnUiThread { appendOutput("Socket write failed: ${e.message}") }
                        break
                    }

                    Thread.sleep(20) // ~50Hz
                }
            } catch (e: InterruptedException) {
                // Normal shutdown
            } finally {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
        }.also { it.start() }
    }

    private fun stopArStream() {
        arThread?.interrupt()
        arThread = null
        arSession?.pause()
        arSession?.close()
        arSession = null
        destroyGlContext()

        runOnUiThread {
            findViewById<SurfaceView>(R.id.arSurface)?.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        arSession?.resume()
    }

    override fun onPause() {
        super.onPause()
        arSession?.pause()
    }

    private fun createGlTexture(surfaceView: SurfaceView): Int {
        // 1. Get EGL display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY).also { display ->
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
        }

        // 2. Choose EGL config
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,    EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_ALPHA_SIZE,      8,
            EGL14.EGL_BLUE_SIZE,       8,
            EGL14.EGL_GREEN_SIZE,      8,
            EGL14.EGL_RED_SIZE,        8,
            EGL14.EGL_DEPTH_SIZE,      16,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        // 3. Create EGL context
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        // 4. Create window surface from the SurfaceView's holder
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surfaceView.holder.surface, intArrayOf(EGL14.EGL_NONE), 0)

        // 5. Make current
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // 6. Generate the OES texture ARCore will write camera frames into
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        cameraTextureId = textures[0]

        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
        return cameraTextureId
    }

    private fun destroyGlContext() {
        if (cameraTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
            cameraTextureId = -1
        }
        eglSurface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
        eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
        eglDisplay?.let { EGL14.eglTerminate(it) }
        eglSurface = null
        eglContext = null
        eglDisplay = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                pendingOnGranted?.invoke()
            } else {
                appendOutput("ERROR: Camera permission denied")
            }
            pendingOnGranted = null
        }
    }
    private var pendingOnGranted: (() -> Unit)? = null
    private fun requestCameraPermissionIfNeeded(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            pendingOnGranted = onGranted  // store to call after user responds
        }
    }
}