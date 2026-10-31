// ThrottleVpnService.kt
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ThrottleVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var proxy: ThrottledSocksServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (tun != null) return

        // 1) Local throttled proxy. Its outgoing sockets MUST bypass the VPN via protect().
        proxy = ThrottledSocksServer(
            service = this,
            bytesPerSecond = 500_000L // ~4 Mbit/s download limit
        ).also { it.start() }

        // 2) TUN interface.
        tun = Builder()
            .setSession("ThrottleVPN")
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .apply {
                // Prevent routing loops from this app's own traffic.
                try { addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
            .establish()

        val fd = tun ?: return stopSelf()

        // 3) IMPORTANT: Without this step, nothing reads the TUN and the internet "dies".
        // Packets from `fd` must be fed into a user-space TCP/IP stack (tun2socks / lwIP-based),
        // configured to forward all TCP/UDP to 127.0.0.1:proxy.port (SOCKS5).
        // Example (native lib of your choice):
        //   Tun2Socks.start(fd.detachFd(), mtu = 1500, socksAddr = "127.0.0.1:${proxy!!.port}")
    }

    private fun stopVpn() {
        proxy?.stop(); proxy = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.throttlevpn.STOP"
    }
}

/** Token bucket shared by all connections (global limit). */
class TokenBucket(private val bytesPerSecond: Long) {
    private var tokens = bytesPerSecond.toDouble()
    private var last = System.nanoTime()

    @Synchronized
    fun take(n: Int) {
        var need = n.toDouble()
        while (need > 0) {
            val now = System.nanoTime()
            tokens = minOf(bytesPerSecond.toDouble(), tokens + (now - last) / 1e9 * bytesPerSecond)
            last = now
            val use = minOf(tokens, need)
            tokens -= use
            need -= use
            if (need > 0) {
                val waitMs = ((need / bytesPerSecond) * 1000).toLong().coerceIn(1, 100)
                (this as Object).wait(waitMs)
            }
        }
    }
}

/** Minimal SOCKS5 (CONNECT only), loopback-only, throttles server -> client direction. */
class ThrottledSocksServer(
    private val service: VpnService,
    bytesPerSecond: Long
) {
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private val bucket = TokenBucket(bytesPerSecond)
    private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: -1

    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        running.set(true)
        pool.execute {
            while (running.get()) {
                val c = try { server!!.accept() } catch (_: Exception) { break }
                pool.execute { handle(c) }
            }
        }
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        try {
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // Greeting
            if (cin.read() != 5) return
            val nMethods = cin.read()
            cin.skipFully(nMethods)
            cout.write(byteArrayOf(5, 0)); cout.flush()

            // Request
            val hdr = ByteArray(4); cin.readFully(hdr)
            if (hdr[1].toInt() != 1) { // CONNECT only
                cout.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); return
            }
            val host: String = when (hdr[3].toInt()) {
                1 -> ByteArray(4).also { cin.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                3 -> ByteArray(cin.read()).also { cin.readFully(it) }.toString(Charsets.US_ASCII)
                4 -> ByteArray(16).also { cin.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                else -> return
            }
            val p = ByteArray(2).also { cin.readFully(it) }
            val port = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)

            // Outgoing socket bypasses the VPN.
            remote = Socket()
            if (!service.protect(remote)) return
            remote.connect(InetSocketAddress(host, port), 10_000)

            cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); cout.flush()

            val r = remote
            pool.execute { pipe(client.getInputStream(), r.getOutputStream(), null) }   // upload: unthrottled
            pipe(r.getInputStream(), cout, bucket)                                      // download: throttled
        } catch (_: Exception) {
        } finally {
            try { remote?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, limiter: TokenBucket?) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                limiter?.take(n)
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun InputStream.readFully(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = read(b, off, b.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun InputStream.skipFully(n: Int) = readFully(ByteArray(n))
}
