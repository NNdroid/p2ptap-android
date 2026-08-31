# P2PTap for Android

<p align="center">
  <img src="logo.svg" width="128" height="128" alt="P2PTap Logo">
</p>

<p align="center">
  <b>High-Performance Peer-to-Peer L2/L3 Overlay Mesh Network for Android</b>
</p>

<p align="center">
  <a href="https://www.gnu.org/licenses/gpl-3.0"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPLv3"></a>
  <a href="https://developer.android.com/about/versions/oreo"><img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg" alt="Android 8.0+"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-1.9-purple.svg" alt="Kotlin"></a>
  <a href="https://github.com/NNdroid/p2ptap"><img src="https://img.shields.io/badge/Go%20Engine-libp2p-cyan.svg" alt="Go Core"></a>
</p>

---

## Overview

**P2PTap** is a modern, high-performance Android client for Peer-to-Peer (P2P) virtual overlay mesh networks. Built on top of Android `VpnService` and a Go-native libp2p engine, P2PTap enables your Android devices to connect directly with PCs, servers, or other mobile devices into a secure, encrypted, decentralized mesh network without relying on central servers.

P2PTap supports **NAT traversal direct connections**, **dynamic exit node gateway relaying**, **advertised subnets**, **traffic obfuscation**, and a **built-in Web Console**.

---

## Key Features

### 🚀 High-Performance P2P Mesh & Transports
- **Multi-Protocol Transports**: Simultaneous support for QUIC-v1 (UDP), WebRTC Direct, WebTransport, and TCP port reuse.
- **NAT Traversal & Relay Fallback**: Automatic P2P hole punching direct connections with seamless fallback to Circuit Relays.
- **mDNS LAN Auto-Discovery**: Automatically discover and connect to local mesh peers in the same Wi-Fi or local network within seconds.

### 🌐 Dynamic Exit Node Gateway
- **Global Gateway Routing**: Select any active online peer as an Exit Gateway (Exit Node) to route default internet traffic (`0.0.0.0/0` & `::/0`) securely through the mesh.
- **Seamless Hot-Reload**: Switch exit nodes while the VPN is running with zero downtime or disconnection.
- **Anti-DNS & IPv6 Leak Protection**: Encrypted tunneled DNS servers (`1.1.1.1`, `8.8.8.8`) and dual-stack IPv4/IPv6 CIDR routing prevent local network leaks.

### 🔒 End-to-End Security & Obfuscation
- **Anti-DPI Traffic Obfuscation**: Obfuscates P2P packet signatures to bypass Deep Packet Inspection (DPI) and ISP throttling.
- **Strict Perfect Forward Secrecy (PFS)**: Enforces ephemeral per-peer session key isolation.
- **Anti-Replay Defense (SeqSync)**: Dynamic sliding window sequence number synchronization protects against replay attacks.

### 📇 Advanced Address & Peer Manager
- **Card & Text Batch Management**: Easily manage Bootstrap peers, Static peers, Advertised subnets, and Allowed peer whitelists.
- **📷 QR Code Share & Scan**: Share connection info via QR codes or import node configurations using camera/gallery scanner.
- **💾 Full Backup & Restore**: Export and import complete backup bundles containing node identity keys and configurations.

### 📊 Built-in Web Console & Diagnostics
- **Web Console**: Embedded HTTP server running on port 15858 providing a visual network topology dashboard.
- **Terminal Log Viewer**: Diagnostic log viewer with pause, keyword search, one-tap copy, and dark/light theme switching.

### 🌍 Full Localization (i18n)
- 100% localized in 10 languages: **English**, **Simplified Chinese**, **Traditional Chinese (HK & TW)**, **German**, **Spanish**, **French**, **Japanese**, **Korean**, and **Russian**.

---

## Architecture

```
+-------------------------------------------------------------+
|                     Android Application                     |
|        (UI Layer: Jetpack / Material 3 / ViewBinding)        |
+-------------------------------------------------------------+
                              |
                              v
+-------------------------------------------------------------+
|             Android OS VpnService TUN Interface             |
|   Routes: 10.0.0.0/8, fd00::/8, 0.0.0.0/1, 128.0.0.0/1, DNS  |
+-------------------------------------------------------------+
                              |
                              v
+-------------------------------------------------------------+
|                Go Native Engine (libp2p Core)               |
|      (QUIC / WebRTC / WebTransport / TCP / Obfuscation)     |
+-------------------------------------------------------------+
                              |
                              v
+-------------------------------------------------------------+
|             Encrypted Peer-to-Peer Overlay Mesh             |
|          [Peer A]  <-------->  [Peer B]  <--------> [Peer C]|
+-------------------------------------------------------------+
```

---

## Building from Source

### Prerequisites
- **Android Studio**: Ladybug / 2024.2+
- **JDK**: Java 17
- **Android SDK**: API 34+ (Min SDK 31 / Android 12+)

### Command Line Build

```bash
# Clone the repository
git clone https://github.com/NNdroid/p2ptap.git
cd p2ptap

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

Generated APKs will be located at `app/build/outputs/apk/`.

---

## CI/CD Workflow & GitHub Release

This repository includes a GitHub Actions workflow (`.github/workflows/release.yml`) for automated building, signing, and releasing.

### Triggering Conditions
1. **Pushing a Tag (`v*`)**: Pushing a git tag like `v1.0.0` automatically compiles, signs, and publishes a new **GitHub Release** with the attached APK assets.
2. **Manual Trigger (`workflow_dispatch`)**: Manually running the workflow from GitHub Actions generates the signed APKs and uploads them as **Build Artifacts**.

### Setting Up GitHub Repository Secrets for APK Signing
To enable automatic APK signing in GitHub Actions, navigate to **Settings -> Secrets and variables -> Actions** in your GitHub repository and configure the following secrets:

| Secret Name | Description | Example / Instructions |
| :--- | :--- | :--- |
| `KEYSTORE_BASE64` | Base64 encoded string of your `.jks` or `.keystore` file | `base64 -w 0 release.jks` |
| `KEYSTORE_PASSWORD` | Password for your keystore file | `YourKeystorePassword` |
| `KEY_ALIAS` | Alias name for the signing key | `p2ptap-release` |
| `KEY_PASSWORD` | Password for the key alias | `YourKeyAliasPassword` |

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**.

```text
P2PTap for Android
Copyright (C) 2026 NNdroid

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

See the [LICENSE](LICENSE) file for full details.
