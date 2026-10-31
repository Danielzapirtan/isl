package dev.datacap

import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

internal class LocalSocksServer(
    private val limiter: DownloadRateLimiter,
) : Closeable {
    @Volatile
    private var closed = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(LOOPBACK, PORT))
        }
        serverSocket = server
        thread(name = "datacap-socks-accept") {
            while (!closed) {
                try {
                    val client = server.accept()
                    thread(name = "datacap-socks-client") { handleClient(client) }
                } catch (_: SocketException) {
                    if (!closed) throw IOException("SOCKS listener closed unexpectedly")
                } catch (error: IOException) {
                    if (!closed) throw error
                }
            }
        }
    }

    override fun close() {
        closed = true
        serverSocket?.close()
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                if (input.readUnsignedByte() != SOCKS_VERSION) return
                val methods = ByteArray(input.readUnsignedByte())
                input.readFully(methods)
                if (methods.none { (it.toInt() and 0xff) == NO_AUTH }) {
                    output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_METHOD.toByte()))
                    return
                }
                output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_AUTH.toByte()))

                if (input.readUnsignedByte() != SOCKS_VERSION) return
                val command = input.readUnsignedByte()
                input.readUnsignedByte()
                val destination = readAddress(input)
                val port = input.readUnsignedShort()
                socket.soTimeout = 0

                when (command) {
                    CONNECT -> handleConnect(socket, input, output, destination, port)
                    UDP_ASSOCIATE -> handleUdpAssociate(socket, input, output)
                    else -> writeReply(output, REPLY_COMMAND_UNSUPPORTED)
                }
            } catch (_: EOFException) {
                // The client closed its connection during SOCKS negotiation.
            } catch (_: SocketException) {
                // Closing the VPN interrupts active client sockets.
            } catch (_: IOException) {
                // The affected SOCKS connection cannot be served; the listener remains available.
            }
        }
    }

    private fun handleConnect(
        client: Socket,
        input: DataInputStream,
        output: java.io.OutputStream,
        host: String,
        port: Int,
    ) {
        val upstream = Socket()
        var connected = false
        try {
            upstream.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            writeReply(output, REPLY_OK)
            connected = true

            thread(name = "datacap-socks-upload") {
                try {
                    input.copyTo(upstream.getOutputStream())
                } catch (_: IOException) {
                    // This direction ends when either peer closes.
                } finally {
                    try {
                        upstream.shutdownOutput()
                    } catch (_: IOException) {
                    }
                }
            }

            val buffer = ByteArray(TCP_BUFFER_SIZE)
            while (true) {
                val count = upstream.getInputStream().read(buffer)
                if (count < 0) break
                limiter.acquire(count)
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (error: IOException) {
            Log.e(TAG, "Failed to connect through SOCKS", error)
            if (!connected) {
                try {
                    writeReply(output, REPLY_GENERAL_FAILURE)
                } catch (_: IOException) {
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try {
                upstream.close()
            } catch (_: IOException) {
            }
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun handleUdpAssociate(
        client: Socket,
        input: DataInputStream,
        output: java.io.OutputStream,
    ) {
        val relay = DatagramSocket(null)
        var relayThread: Thread? = null
        try {
            relay.bind(InetSocketAddress(LOOPBACK, 0))
            relay.soTimeout = UDP_RECEIVE_TIMEOUT_MILLIS
            writeReply(output, REPLY_OK, LOOPBACK.address, relay.localPort)
            relayThread = thread(name = "datacap-socks-udp") {
                runUdpRelay(relay)
            }

            while (input.read() != -1) {
                // SOCKS5 control connection remains open for the UDP association lifetime.
            }
        } catch (_: IOException) {
            try {
                writeReply(output, REPLY_GENERAL_FAILURE)
            } catch (_: IOException) {
            }
        } finally {
            relay.close()
            relayThread?.interrupt()
        }
    }

    private fun runUdpRelay(relay: DatagramSocket) {
        var clientAddress: InetSocketAddress? = null
        val buffer = ByteArray(MAX_UDP_PACKET_SIZE)
        while (!closed && !relay.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                relay.receive(packet)
                val sender = packet.socketAddress as InetSocketAddress
                if (clientAddress == null) clientAddress = sender

                if (sender == clientAddress) {
                    val request = parseUdpRequest(packet.data, packet.offset, packet.length) ?: continue
                    relay.send(DatagramPacket(request.payload, request.payload.size, request.destination))
                } else {
                    val peer = clientAddress ?: continue
                    val framed = frameUdpResponse(packet)
                    limiter.acquire(packet.length)
                    relay.send(DatagramPacket(framed, framed.size, peer))
                }
            } catch (_: SocketException) {
                if (!relay.isClosed) throw IOException("UDP relay failed")
                return
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun parseUdpRequest(data: ByteArray, offset: Int, length: Int): UdpRequest? {
        if (length < 4 || data[offset] != 0.toByte() || data[offset + 1] != 0.toByte() ||
            data[offset + 2] != 0.toByte()
        ) return null

        var index = offset + 3
        val addressType = data[index++].toInt() and 0xff
        val addressBytes: ByteArray
        val host: String
        when (addressType) {
            ATYP_IPV4 -> {
                if (length < index - offset + 4 + 2) return null
                addressBytes = data.copyOfRange(index, index + 4)
                host = InetAddress.getByAddress(addressBytes).hostAddress
                    ?: throw IOException("Could not decode IPv4 address")
                index += 4
            }
            ATYP_IPV6 -> {
                if (length < index - offset + 16 + 2) return null
                addressBytes = data.copyOfRange(index, index + 16)
                host = InetAddress.getByAddress(addressBytes).hostAddress
                    ?: throw IOException("Could not decode IPv6 address")
                index += 16
            }
            ATYP_DOMAIN -> {
                if (length < index - offset + 1) return null
                val domainLength = data[index++].toInt() and 0xff
                if (length < index - offset + domainLength + 2) return null
                host = String(data, index, domainLength, StandardCharsets.UTF_8)
                index += domainLength
            }
            else -> return null
        }

        if (length < index - offset + 2) return null
        val port = ((data[index].toInt() and 0xff) shl 8) or (data[index + 1].toInt() and 0xff)
        val payloadOffset = index + 2
        val payloadLength = offset + length - payloadOffset
        if (payloadLength <= 0) return null
        return try {
            UdpRequest(
                InetSocketAddress(host, port),
                data.copyOfRange(payloadOffset, payloadOffset + payloadLength),
            )
        } catch (_: IOException) {
            null
        }
    }

    private fun frameUdpResponse(packet: DatagramPacket): ByteArray {
        val address = packet.address.address
        val addressType = if (address.size == 4) ATYP_IPV4 else ATYP_IPV6
        val framed = ByteArray(3 + 1 + address.size + 2 + packet.length)
        framed[3] = addressType.toByte()
        address.copyInto(framed, 4)
        var index = 4 + address.size
        framed[index++] = (packet.port shr 8).toByte()
        framed[index++] = packet.port.toByte()
        packet.data.copyInto(framed, index, packet.offset, packet.offset + packet.length)
        return framed
    }

    private fun readAddress(input: DataInputStream): String {
        return when (input.readUnsignedByte()) {
            ATYP_IPV4 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
            ATYP_IPV6 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
            ATYP_DOMAIN -> {
                val length = input.readUnsignedByte()
                val name = ByteArray(length).also(input::readFully)
                String(name, StandardCharsets.UTF_8)
            }
            else -> throw IOException("Unsupported SOCKS address type")
        }
    }

    private fun writeReply(
        output: java.io.OutputStream,
        code: Int,
        address: ByteArray = byteArrayOf(0, 0, 0, 0),
        port: Int = 0,
    ) {
        output.write(
            byteArrayOf(
                SOCKS_VERSION.toByte(),
                code.toByte(),
                0,
                ATYP_IPV4.toByte(),
                *address,
                (port shr 8).toByte(),
                port.toByte(),
            ),
        )
        output.flush()
    }

    private data class UdpRequest(val destination: InetSocketAddress, val payload: ByteArray)

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        private const val TAG = "DataCapSocks"
        private const val PORT = 10_808
        private const val SOCKS_VERSION = 5
        private const val NO_AUTH = 0
        private const val NO_METHOD = 0xff
        private const val CONNECT = 1
        private const val UDP_ASSOCIATE = 3
        private const val REPLY_OK = 0
        private const val REPLY_GENERAL_FAILURE = 1
        private const val REPLY_COMMAND_UNSUPPORTED = 7
        private const val ATYP_IPV4 = 1
        private const val ATYP_DOMAIN = 3
        private const val ATYP_IPV6 = 4
        private const val SOCKET_TIMEOUT_MILLIS = 30_000
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val TCP_BUFFER_SIZE = 4_096
        private const val MAX_UDP_PACKET_SIZE = 65_535
        private const val UDP_RECEIVE_TIMEOUT_MILLIS = 250
    }
}
