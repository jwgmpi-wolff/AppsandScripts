package com.jerrywolff.phonesyncusbc.recovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

object AndroidPeerTransferServer {
    private const val PORT = 8765
    private const val SERVICE_TYPE = "_phonesync._tcp"
    private val started = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        ioScope.launch {
            try {
                server = ServerSocket(PORT)
                registerService(context)
                while (!server!!.isClosed) {
                    val socket = server!!.accept()
                    ioScope.launch { handleClient(socket) }
                }
            } catch (_: Exception) {
                started.set(false)
            }
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        registrationListener?.let { nsdManager?.unregisterService(it) }
        registrationListener = null
        started.set(false)
    }

    private fun registerService(context: Context) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "PhoneSyncCompanion-${android.os.Build.MODEL ?: "Android"}"
            serviceType = SERVICE_TYPE
            port = PORT
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }

        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private suspend fun handleClient(socket: Socket) {
        val connection = socket.getInputStream().bufferedReader()
        val requestLine = connection.readLine() ?: ""
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            socket.close()
            return
        }

        val method = parts[0]
        val path = parts[1]
        if (method != "GET") {
            socket.close()
            return
        }

        val response = when {
            path == "/api/status" -> buildStatusResponse()
            path == "/api/files" -> buildFilesResponse()
            path.startsWith("/api/file/") -> buildFileResponse(path)
            else -> mapOf("error" to "not_found")
        }

        val payload = Json.encodeToString(response)
        val http = buildHttpResponse(payload)
        socket.getOutputStream().use { it.write(http.toByteArray(Charsets.UTF_8)) }
        socket.close()
    }

    private fun buildStatusResponse(): Map<String, Any> = mapOf(
        "status" to "online",
        "version" to "android-peer",
        "device" to (android.os.Build.MODEL ?: "Android"),
        "available" to listOf("files"),
    )

    private fun buildFilesResponse(): Map<String, Any> {
        val sharedDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "PhoneSyncShared")
        sharedDir.mkdirs()
        val files = sharedDir.walkTopDown().filter { it.isFile }.map { file ->
            val relative = file.relativeTo(sharedDir).invariantSeparatorsPath
            mapOf(
                "path" to relative,
                "size" to file.length(),
                "modified" to file.lastModified(),
            )
        }.toList()
        return mapOf(
            "type" to "files",
            "count" to files.size,
            "data" to files,
        )
    }

    private fun buildFileResponse(path: String): Map<String, Any> {
        val rawPath = path.removePrefix("/api/file/")
        val decoded = java.net.URLDecoder.decode(rawPath, Charsets.UTF_8.name())
        val sharedDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "PhoneSyncShared")
        val target = File(sharedDir, decoded.removePrefix("/").replace("/", File.separator))
        return if (target.exists() && target.isFile) {
            mapOf("status" to "ok", "content" to String(target.readBytes(), Charsets.UTF_8))
        } else {
            mapOf("error" to "not_found")
        }
    }

    private fun buildHttpResponse(payload: String): String {
        return "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n" +
            payload
    }
}

@Serializable
private data class AndroidPeerFileResponse(
    val status: String,
    val content: String,
)
