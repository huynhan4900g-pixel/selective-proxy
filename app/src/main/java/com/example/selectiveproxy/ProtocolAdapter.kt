package com.example.selectiveproxy

import android.net.VpnService
import android.util.Base64
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ProtocolAdapter(
    private val config: SecureProxyConfig,
    private val vpnService: VpnService,
    private val tracker: ConnectionTracker,
    private val context: android.content.Context
) {
    companion object {
        private const val TAG = "ProtocolAdapter"
    }

    private val executor = Executors.newCachedThreadPool()
    private val localPort = AtomicInteger(1080)
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    fun start(): Int {
        return try {
            serverSocket = ServerSocket(0).apply {
                reuseAddress = true
                soTimeout = 5000
            }
            localPort.set(serverSocket!!.localPort)

            executor.submit {
                isRunning.set(true)
                while (isRunning.get() && !serverSocket!!.isClosed) {
                    try {
                        val client = serverSocket!!.accept()
                        vpnService.protect(client)
                        handleClient(client)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: IOException) {
                        if (isRunning.get()) Log.e(TAG, "Accept error", e)
                    }
                }
            }
            localPort.get()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start adapter", e)
            -1
        }
    }

    private fun handleClient(client: Socket) {
        executor.submit {
            try {
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // Read SOCKS5 handshake
                // Format: VER (1) | NMETHODS (1) | METHODS (N)
                val header = ByteArray(2)
                if (!readFully(input, header)) return@submit

                val nMethods = header[1].toInt() and 0xFF
                val methods = ByteArray(nMethods)
                if (!readFully(input, methods)) return@submit

                // SOCKS5 response (Select No Authentication)
                output.write(byteArrayOf(0x05, 0x00))

                // Read SOCKS5 request
                val request = ByteArray(4)
                if (!readFully(input, request)) return@submit

                val cmd = request[1].toInt() and 0xFF
                val addrType = request[3].toInt() and 0xFF

                // Read destination address
                val (targetHost, targetPort) = when (addrType) {
                    0x01 -> { // IPv4
                        val ipBytes = ByteArray(4)
                        if (!readFully(input, ipBytes)) return@submit
                        InetAddress.getByAddress(ipBytes).hostAddress to readPort(input)
                    }
                    0x03 -> { // Domain name
                        val len = input.read()
                        if (len <= 0) return@submit
                        val domainBytes = ByteArray(len)
                        if (!readFully(input, domainBytes)) return@submit
                        String(domainBytes, StandardCharsets.UTF_8) to readPort(input)
                    }
                    0x04 -> { // IPv6
                        val ipBytes = ByteArray(16)
                        if (!readFully(input, ipBytes)) return@submit
                        val ip6 = InetAddress.getByAddress(ipBytes)
                        ip6.hostAddress to readPort(input)
                    }
                    else -> return@submit
                } ?: return@submit

                // Connect to upstream proxy
                val proxySocket = when (config.type) {
                    SecureProxyConfig.ProxyType.HTTP -> connectToHttpProxy(targetHost, targetPort)
                    SecureProxyConfig.ProxyType.SOCKS5 -> connectToSocks5Proxy(targetHost, targetPort)
                } ?: return@submit

                vpnService.protect(proxySocket)

                // Send SOCKS5 success response
                val response = ByteArray(10).apply {
                    this[0] = 0x05
                    this[1] = 0x00
                    this[2] = 0x00
                    this[3] = 0x01
                    this[8] = (targetPort shr 8).toByte()
                    this[9] = (targetPort and 0xFF).toByte()
                }
                output.write(response)
                output.flush()

                // Tunnel traffic
                tunnelTraffic(client, proxySocket)

            } catch (e: Exception) {
                Log.e(TAG, "Client handling error", e)
            } finally {
                safeClose(client)
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val result = input.read(buffer, read, buffer.size - read)
            if (result == -1) return false
            read += result
        }
        return true
    }

    private fun readPort(input: InputStream): Int {
        val high = input.read()
        val low = input.read()
        return if (high == -1 || low == -1) -1 else (high shl 8) or low
    }

    private fun connectToHttpProxy(targetHost: String, targetPort: Int): Socket? {
        return try {
                val socket = Socket()
                vpnService.protect(socket)
                socket.connect(java.net.InetSocketAddress(config.host, config.port), 30000)
                socket.soTimeout = 30000
                socket.keepAlive = true

            val authHeader = config.username?.let { user ->
                config.getPasswordDecrypted(context)?.let { pass ->
                    try {
                        val userBytes = user.toByteArray(StandardCharsets.UTF_8)
                        val colon = ":".toByteArray(StandardCharsets.UTF_8)
                        val passBytes = ByteArray(pass.size) { i -> pass[i].code.toByte() }

                        val creds = ByteArray(userBytes.size + colon.size + passBytes.size).apply {
                            System.arraycopy(userBytes, 0, this, 0, userBytes.size)
                            System.arraycopy(colon, 0, this, userBytes.size, colon.size)
                            System.arraycopy(passBytes, 0, this, userBytes.size + colon.size, passBytes.size)
                        }

                        val encoded = Base64.encodeToString(creds, Base64.NO_WRAP)
                        java.util.Arrays.fill(passBytes, 0.toByte())
                        java.util.Arrays.fill(creds, 0.toByte())
                        java.util.Arrays.fill(pass, '\u0000')

                        "Proxy-Authorization: Basic $encoded"
                    } catch (e: Exception) {
                        Log.e(TAG, "Auth header creation failed", e)
                        null
                    }
                }
            }

            val request = buildString {
                append("CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
                append("Host: $targetHost:$targetPort\r\n")
                authHeader?.let { append("$it\r\n") }
                append("Proxy-Connection: keep-alive\r\n")
                append("\r\n")
            }

            socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))

            val reader = socket.getInputStream().bufferedReader()
            val response = reader.readLine()
            if (!response.contains("200")) {
                socket.close()
                return null
            }

            while (reader.readLine().takeIf { it.isNotEmpty() } != null) {
                // Skip headers
            }

            socket
        } catch (e: Exception) {
            Log.e(TAG, "HTTP proxy connection failed", e)
            null
        }
    }

    private fun connectToSocks5Proxy(targetHost: String, targetPort: Int): Socket? {
        return try {
            val socket = Socket()
            vpnService.protect(socket)
            socket.connect(java.net.InetSocketAddress(config.host, config.port), 30000)
            socket.soTimeout = 30000

            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            // SOCKS5 handshake
            output.write(byteArrayOf(0x05, 0x01, 0x00))

            val handshakeResponse = ByteArray(2)
            if (!readFully(input, handshakeResponse) || handshakeResponse[0] != 0x05.toByte() || handshakeResponse[1] != 0x00.toByte()) {
                socket.close()
                return null
            }

            // SOCKS5 connect request
            val domainBytes = targetHost.toByteArray(StandardCharsets.UTF_8)
            val request = ByteArray(4 + 1 + domainBytes.size + 2).apply {
                this[0] = 0x05 // Version
                this[1] = 0x01 // CONNECT
                this[2] = 0x00 // Reserved
                this[3] = 0x03 // Domain name
                this[4] = domainBytes.size.toByte()
                System.arraycopy(domainBytes, 0, this, 5, domainBytes.size)
                this[5 + domainBytes.size] = (targetPort shr 8).toByte()
                this[6 + domainBytes.size] = (targetPort and 0xFF).toByte()
            }
            output.write(request)

            // Read response
            val response = ByteArray(10)
            if (!readFully(input, response) || response[1] != 0x00.toByte()) {
                socket.close()
                return null
            }

            socket
        } catch (e: Exception) {
            Log.e(TAG, "SOCKS5 proxy connection failed", e)
            null
        }
    }

    private fun tunnelTraffic(client: Socket, proxy: Socket) {
        thread {
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val proxyIn = proxy.getInputStream()
            val proxyOut = proxy.getOutputStream()

            // Client -> Proxy
            thread {
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val read = clientIn.read(buffer)
                        if (read <= 0) break
                        proxyOut.write(buffer, 0, read)
                    }
                } catch (e: IOException) {
                    // Connection closed
                } finally {
                    safeClose(proxy)
                }
            }

            // Proxy -> Client
            thread {
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val read = proxyIn.read(buffer)
                        if (read <= 0) break
                        clientOut.write(buffer, 0, read)
                    }
                } catch (e: IOException) {
                    // Connection closed
                } finally {
                    safeClose(client)
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        safeClose(serverSocket)
        executor.shutdownNow()
    }

    private fun safeClose(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (e: IOException) {
            // Ignore
        }
    }

    fun getLocalPort() = localPort.get()
}
