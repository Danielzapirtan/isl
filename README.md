# DataCap

DataCap is an Android 10+ app that applies one aggregate download-rate limit to device traffic without root. It uses Android's user-approved `VpnService` API and routes IPv4 and IPv6 through a local userspace tunnel. Uploads are not limited.

Enter a positive integer in bits per second (for example, `1000000` for 1 Mbps), then start the VPN. Android allows only one active VPN at a time; stop DataCap to use another VPN. The app itself is excluded from its VPN so its local tunnel can make protected upstream connections. Traffic is not decrypted or inspected.

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35 installed, or run `./gradlew :app:assembleDebug`. On first start, approve Android's VPN permission prompt.

The bundled `app/libs/hev-socks5-tunnel-2.19.3.aar` provides the userspace IP tunnel. Its upstream MIT license is in `app/libs/HEV-LICENSE`; source and release: <https://github.com/heiher/hev-socks5-tunnel/releases/tag/2.19.3>.
