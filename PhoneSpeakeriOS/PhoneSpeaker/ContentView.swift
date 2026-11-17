//
//  ContentView.swift
//  PhoneSpeaker
//
//  PhoneSpeaker v2.1 Studio Monospace UI for iOS.
//  100% unified with Android (activity_main.xml & MainActivity.kt) and PC Studio.
//

import SwiftUI
import UIKit

public struct ContentView: View {
    @StateObject private var engine = PhoneSpeakerEngine.shared
    @State private var selectedTab: Int = 0
    @State private var showHelpModal: Bool = false

    public var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                // Top Studio Header
                VStack(spacing: 4) {
                    HStack(alignment: .center) {
                        Text("PHONESPEAKER v2.1")
                            .font(.system(size: 18, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)

                        Spacer()

                        Text("REV: \(engine.localSyncRev)")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Color(white: 0.55))

                        Button(action: {
                            showHelpModal = true
                        }) {
                            Text("?")
                                .font(.system(size: 14, weight: .bold, design: .monospaced))
                                .foregroundColor(Color(red: 0.0, green: 1.0, blue: 0.0))
                                .frame(width: 38, height: 32)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 4)
                                        .stroke(Color(red: 0.0, green: 1.0, blue: 0.0), lineWidth: 1)
                                )
                        }
                    }

                    HStack {
                        let statusText: String = {
                            if engine.isAudioPlaying && engine.isInternalAudioServing {
                                return "ACTIVE: PLAYER: ON (5004) | SERVE: ON (5004)"
                            } else if engine.isAudioPlaying {
                                return "ACTIVE: SPEAKER PLAYER (PORT 5004)"
                            } else if engine.isInternalAudioServing {
                                return "ACTIVE: AUDIO SERVER (PORT 5004)"
                            } else {
                                return "STATUS: STOPPED"
                            }
                        }()
                        Text(statusText)
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(engine.isAudioPlaying || engine.isInternalAudioServing ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.55))
                        Spacer()
                    }
                }
                .padding(.horizontal, 14)
                .padding(.top, 8)
                .padding(.bottom, 6)

                // 4-Button Top Outlined Tab Bar: PLAY | SERVE | PARTY | SETTINGS
                HStack(spacing: 6) {
                    StudioTabButton(title: "PLAY", isSelected: selectedTab == 0) { selectedTab = 0 }
                    StudioTabButton(title: "SERVE", isSelected: selectedTab == 1) { selectedTab = 1 }
                    StudioTabButton(title: "PARTY", isSelected: selectedTab == 2) { selectedTab = 2 }
                    StudioTabButton(title: "SETTINGS", isSelected: selectedTab == 3) { selectedTab = 3 }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)

                // Tab Content
                Group {
                    switch selectedTab {
                    case 0:
                        PlayerStudioTab(engine: engine)
                    case 1:
                        ServeStudioTab(engine: engine)
                    case 2:
                        PartyStudioTab(engine: engine)
                    default:
                        SettingsStudioTab(engine: engine)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .sheet(isPresented: $showHelpModal) {
            StudioHelpModal(isPresented: $showHelpModal)
        }
    }
}

// MARK: - Monospace Outlined Tab Button
struct StudioTabButton: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(isSelected ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.55))
                .frame(maxWidth: .infinity, minHeight: 38)
                .background(isSelected ? Color(red: 0.06, green: 0.13, blue: 0.06) : Color.black)
                .overlay(
                    RoundedRectangle(cornerRadius: 4)
                        .stroke(isSelected ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.25), lineWidth: isSelected ? 2 : 1)
                )
        }
    }
}

// MARK: - Tab 1: PLAYER STUDIO TAB
struct PlayerStudioTab: View {
    @ObservedObject var engine: PhoneSpeakerEngine
    @State private var pcIpInput: String = ""
    @State private var showEQSheet: Bool = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // Connections Card (#1A2630)
                VStack(alignment: .leading, spacing: 4) {
                    let hostDisplay = engine.connectedServerIp.isEmpty ? "Waiting for PC connection..." : "\(engine.connectedServerName) (\(engine.connectedServerIp))"
                    Text("💻 Connected to: \(hostDisplay)")
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundColor(Color(red: 0.22, green: 0.71, blue: 1.0))

                    Text("📱 This Phone: \(engine.deviceName)")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(Color(red: 0.60, green: 0.73, blue: 0.80))

                    HStack(spacing: 6) {
                        Text("PC IP:")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(Color(red: 0.22, green: 0.71, blue: 1.0))

                        TextField(engine.connectedServerIp.isEmpty ? "Auto-Detecting PC..." : engine.connectedServerIp, text: $pcIpInput)
                            .font(.system(size: 12, design: .monospaced))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 6)
                            .background(Color(red: 0.06, green: 0.09, blue: 0.13))
                            .foregroundColor(.white)
                            .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.2), lineWidth: 1))

                        Button(action: {
                            let trimmed = pcIpInput.trimmingCharacters(in: .whitespacesAndNewlines)
                            if !trimmed.isEmpty {
                                engine.connectedServerIp = trimmed
                                engine.broadcastBeacon()
                                engine.sendConfigSync(forcePcm: true, targetIp: trimmed)
                            }
                        }) {
                            Text("SET IP")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(Color(red: 0.22, green: 0.71, blue: 1.0))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 6)
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(red: 0.22, green: 0.71, blue: 1.0), lineWidth: 1))
                        }
                    }
                    .padding(.top, 4)
                }
                .padding(10)
                .background(Color(red: 0.10, green: 0.15, blue: 0.19))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(white: 0.2), lineWidth: 1))

                // Main Action Button
                Button(action: {
                    if engine.isAudioPlaying {
                        engine.stopSpeakerPlayer()
                    } else {
                        let manual = pcIpInput.trimmingCharacters(in: .whitespacesAndNewlines)
                        engine.startSpeakerPlayer(manualHost: manual)
                    }
                }) {
                    Text(engine.isAudioPlaying ? "[ ■ STOP SPEAKER PLAYER (PORT 5004) ]" : "[ ▶ START SPEAKER PLAYER (PORT 5004) ]")
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundColor(engine.isAudioPlaying ? Color(red: 1.0, green: 0.27, blue: 0.27) : Color(red: 0.0, green: 1.0, blue: 0.0))
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(engine.isAudioPlaying ? Color(red: 0.20, green: 0.0, blue: 0.0) : Color(red: 0.0, green: 0.13, blue: 0.0))
                        .overlay(RoundedRectangle(cornerRadius: 4).stroke(engine.isAudioPlaying ? Color(red: 1.0, green: 0.27, blue: 0.27) : Color(red: 0.0, green: 1.0, blue: 0.0), lineWidth: 2))
                }

                // Sound Mode
                VStack(alignment: .leading, spacing: 4) {
                    Text("SOUND MODE (QUALITY vs LOW DELAY):")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    HStack(spacing: 4) {
                        RadioChip(title: "Balanced (Auto)", isSelected: engine.soundMode == "BALANCED") {
                            engine.soundMode = "BALANCED"
                            engine.sendConfigSync()
                        }
                        RadioChip(title: "Gaming", isSelected: engine.soundMode == "GAMING") {
                            engine.soundMode = "GAMING"
                            engine.sendConfigSync()
                        }
                        RadioChip(title: "Quality", isSelected: engine.soundMode == "QUALITY") {
                            engine.soundMode = "QUALITY"
                            engine.sendConfigSync()
                        }
                        RadioChip(title: "Custom", isSelected: engine.soundMode == "CUSTOM") {
                            engine.soundMode = "CUSTOM"
                            engine.sendConfigSync()
                        }
                    }
                }

                // Codec & Bitrate
                VStack(alignment: .leading, spacing: 4) {
                    Text("CODEC & BITRATE:")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    HStack(spacing: 8) {
                        RadioChip(title: "Opus (PC/Android)", isSelected: false) {
                            engine.sendConfigSync(forcePcm: true)
                        }
                        RadioChip(title: "Lossless PCM16 (iOS Active)", isSelected: true) {
                            engine.sendConfigSync(forcePcm: true)
                        }
                    }

                    HStack {
                        Text("BITRATE: 192 kbps (Lossless 48kHz PCM16)")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Color(white: 0.6))
                        Spacer()
                    }

                    let expDelay = Int(engine.latencyMs + 25.0)
                    Text("Expected Delay: ~\(expDelay) ms")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(Color(red: 0.0, green: 0.93, blue: 0.93))
                }

                // Stability / Delay Buffer
                TargetBufferControlSection(engine: engine)

                // Equalizer Trigger
                Button(action: {
                    showEQSheet = true
                }) {
                    Text("[ 🎛️ EQUALIZER: \(engine.playEQ.enabled ? engine.playEQ.presetName : "OFF") ]")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(engine.playEQ.enabled ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.7))
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .background(engine.playEQ.enabled ? Color(red: 0.06, green: 0.13, blue: 0.06) : Color(white: 0.06))
                        .overlay(RoundedRectangle(cornerRadius: 3).stroke(engine.playEQ.enabled ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.25), lineWidth: 1))
                }
                .sheet(isPresented: $showEQSheet) {
                    StudioEqualizerSheetView(title: "PLAYER EQUALIZER", config: $engine.playEQ, engine: engine)
                }

                // Stream Volume (Relative to Phone)
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("STREAM VOLUME (RELATIVE TO PHONE):")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Color(white: 0.6))
                        Spacer()
                        Text("\(Int(engine.volume * 100))%")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                    }

                    HStack(spacing: 8) {
                        Slider(
                            value: Binding(
                                get: { Double(engine.volume) },
                                set: { newVal in
                                    engine.volume = Float(newVal)
                                    engine.sendConfigSync()
                                }
                            ),
                            in: 0.0...2.0
                        )
                        .accentColor(.white)

                        Button(action: {
                            engine.isMuted.toggle()
                            engine.sendConfigSync()
                        }) {
                            Text(engine.isMuted ? "MUTED" : "MUTE")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(engine.isMuted ? Color(red: 1.0, green: 0.27, blue: 0.27) : .white)
                                .frame(width: 60, height: 32)
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(engine.isMuted ? Color(red: 1.0, green: 0.27, blue: 0.27) : .white, lineWidth: 1))
                        }
                    }
                }

                // Live Delay Graph Canvas
                VStack(alignment: .leading, spacing: 4) {
                    Text("LIVE DELAY GRAPH")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    LiveDelayGraphView(
                        history: engine.delayHistory,
                        targetMs: Double(engine.bufferTargetMs),
                        currentBufMs: engine.latencyMs
                    )
                }

                // Live Telemetry (5 Hz) Table
                VStack(alignment: .leading, spacing: 4) {
                    Text("LIVE TELEMETRY (5 Hz)")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    VStack(spacing: 2) {
                        TelemetryRow(label: "BUFFER LEVEL:", value: String(format: "%.1f ms", engine.latencyMs))
                        TelemetryRow(label: "ACTIVE TARGET:", value: "\(engine.bufferTargetMs).0 ms")
                        TelemetryRow(label: "CODEC:", value: "PCM16 (48kHz Stereo)")
                        TelemetryRow(label: "RX PACKETS / SEC:", value: "\(engine.rxPps) pps")
                        TelemetryRow(label: "UNDERRUNS:", value: "\(engine.underrunCount)")
                        TelemetryRow(label: "LOST PACKETS:", value: String(format: "%.0f (%.1f%%)", (Double(engine.rxPps) * (engine.packetLossPercent / 100.0)), engine.packetLossPercent))
                        TelemetryRow(label: "LATE PACKETS:", value: "0")
                    }
                    .padding(8)
                    .background(Color(red: 0.05, green: 0.05, blue: 0.05))
                    .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.15), lineWidth: 1))
                }
            }
            .padding(14)
        }
    }
}

// MARK: - Tab 2: SERVE STUDIO TAB (Internal System Audio Streamer)
struct ServeStudioTab: View {
    @ObservedObject var engine: PhoneSpeakerEngine
    @State private var targetIpInput: String = ""
    @State private var showEQSheet: Bool = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Header & Target IP card
                VStack(alignment: .leading, spacing: 6) {
                    Text("📡 INTERNAL SYSTEM AUDIO SENDER (PORT 5004)")
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundColor(Color(red: 0.22, green: 0.71, blue: 1.0))

                    Text("Streams iOS internal audio (apps, games, YouTube) in real-time to PC, Mac, Linux, or another phone.")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(Color(white: 0.6))

                    HStack(spacing: 6) {
                        Text("TARGET IP:")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(Color(red: 0.22, green: 0.71, blue: 1.0))

                        TextField(engine.connectedServerIp.isEmpty ? "255.255.255.255 (Broadcast)" : engine.connectedServerIp, text: $targetIpInput)
                            .font(.system(size: 11, design: .monospaced))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 6)
                            .background(Color(red: 0.06, green: 0.09, blue: 0.13))
                            .foregroundColor(.white)
                            .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.2), lineWidth: 1))

                        Button(action: {
                            let trimmed = targetIpInput.trimmingCharacters(in: .whitespacesAndNewlines)
                            if !trimmed.isEmpty {
                                engine.connectedServerIp = trimmed
                            }
                        }) {
                            Text("SET")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(Color(red: 0.22, green: 0.71, blue: 1.0))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(red: 0.22, green: 0.71, blue: 1.0), lineWidth: 1))
                        }
                    }
                    .padding(.top, 4)
                }
                .padding(10)
                .background(Color(red: 0.10, green: 0.15, blue: 0.19))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(white: 0.2), lineWidth: 1))

                // Big Action Button
                Button(action: {
                    let target = targetIpInput.trimmingCharacters(in: .whitespacesAndNewlines)
                    engine.toggleServingInternalAudio()
                }) {
                    Text(engine.isInternalAudioServing ? "[ ■ STOP SERVING AUDIO ]" : "[ 📡 START SERVING AUDIO (PORT 5004) ]")
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundColor(engine.isInternalAudioServing ? Color(red: 1.0, green: 0.27, blue: 0.27) : Color(red: 0.0, green: 1.0, blue: 0.0))
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(engine.isInternalAudioServing ? Color(red: 0.20, green: 0.0, blue: 0.0) : Color(red: 0.0, green: 0.13, blue: 0.0))
                        .overlay(RoundedRectangle(cornerRadius: 4).stroke(engine.isInternalAudioServing ? Color(red: 1.0, green: 0.27, blue: 0.27) : Color(red: 0.0, green: 1.0, blue: 0.0), lineWidth: 2))
                }

                // Sound Mode Chips
                VStack(alignment: .leading, spacing: 4) {
                    Text("STREAM PROFILE:")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    HStack(spacing: 4) {
                        RadioChip(title: "Balanced", isSelected: engine.soundMode == "BALANCED") {
                            engine.soundMode = "BALANCED"
                            engine.sendConfigSync()
                        }
                        RadioChip(title: "Gaming", isSelected: engine.soundMode == "GAMING") {
                            engine.soundMode = "GAMING"
                            engine.sendConfigSync()
                        }
                        RadioChip(title: "Quality", isSelected: engine.soundMode == "QUALITY") {
                            engine.soundMode = "QUALITY"
                            engine.sendConfigSync()
                        }
                        RadioChip(title: "Custom", isSelected: engine.soundMode == "CUSTOM") {
                            engine.soundMode = "CUSTOM"
                            engine.sendConfigSync()
                        }
                    }
                }

                // Codec & Bitrate Selection
                VStack(alignment: .leading, spacing: 4) {
                    Text("STREAM CODEC:")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    HStack(spacing: 8) {
                        RadioChip(title: "Lossless PCM16 [Default]", isSelected: engine.activeCodecName == "PCM16") {
                            engine.activeCodecName = "PCM16"
                        }
                        RadioChip(title: "Opus Codec", isSelected: engine.activeCodecName == "OPUS") {
                            engine.activeCodecName = "OPUS"
                        }
                    }
                }

                // Target Buffer Control
                TargetBufferControlSection(engine: engine)

                // Equalizer Trigger
                Button(action: {
                    showEQSheet = true
                }) {
                    Text("[ 🎛️ EQUALIZER: \(engine.serveEQ.enabled ? engine.serveEQ.presetName : "OFF") ]")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(engine.serveEQ.enabled ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.7))
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .background(engine.serveEQ.enabled ? Color(red: 0.06, green: 0.13, blue: 0.06) : Color(white: 0.06))
                        .overlay(RoundedRectangle(cornerRadius: 3).stroke(engine.serveEQ.enabled ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.25), lineWidth: 1))
                }
                .sheet(isPresented: $showEQSheet) {
                    StudioEqualizerSheetView(title: "SERVE EQUALIZER", config: $engine.serveEQ, engine: engine)
                }

                // Live Audio Level & Telemetry
                VStack(alignment: .leading, spacing: 6) {
                    Text("LIVE AUDIO STREAM METERS:")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    HStack {
                        Text("PEAK LEVEL:")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Color(white: 0.6))
                        Spacer()
                        Text(String(format: "%.1f dB", 20.0 * log10(max(0.0001, Double(engine.servePeakLevel)))))
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(Color(red: 0.0, green: 0.93, blue: 0.93))
                    }

                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 3)
                                .fill(Color(white: 0.15))
                            RoundedRectangle(cornerRadius: 3)
                                .fill(engine.servePeakLevel > 0.9 ? Color.red : Color(red: 0.0, green: 1.0, blue: 0.0))
                                .frame(width: max(0, min(geo.size.width, geo.size.width * CGFloat(engine.servePeakLevel))))
                        }
                    }
                    .frame(height: 10)

                    HStack {
                        Text("TX PACKETS / SEC:")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Color(white: 0.6))
                        Spacer()
                        Text("\(engine.serveTxPps) pps")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                    }

                    HStack {
                        Text("DESTINATION:")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Color(white: 0.6))
                        Spacer()
                        Text(engine.connectedServerIp.isEmpty ? "255.255.255.255:5004" : "\(engine.connectedServerIp):5004")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Color(red: 0.22, green: 0.71, blue: 1.0))
                    }
                }
                .padding(10)
                .background(Color(red: 0.05, green: 0.05, blue: 0.05))
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.15), lineWidth: 1))

                VStack(alignment: .leading, spacing: 4) {
                    Text("LIVE DELAY GRAPH")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    LiveDelayGraphView(
                        history: engine.delayHistory,
                        targetMs: Double(engine.bufferTargetMs),
                        currentBufMs: engine.latencyMs
                    )
                }
            }
            .padding(14)
        }
    }
}

// MARK: - Tab 3: PARTY STUDIO TAB
struct PartyStudioTab: View {
    @ObservedObject var engine: PhoneSpeakerEngine
    @State private var showScanner: Bool = false
    @State private var manualJoinIp: String = ""
    @State private var manualPeerInput: String = ""
    @State private var showHostEQSheet: Bool = false
    @State private var showJoinEQSheet: Bool = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if engine.partyScreen == "CHOOSER" {
                    VStack(alignment: .center, spacing: 12) {
                        Text("🎉 PARTY MODE — MULTI-DEVICE WIRELESS AUDIO")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundColor(Color(red: 0.22, green: 0.71, blue: 1.0))
                            .multilineTextAlignment(.center)

                        Text("Independent from Normal 1-to-1 Mode.\nChoose how you want to use Party Mode:")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Color(white: 0.55))
                            .multilineTextAlignment(.center)

                        Button(action: {
                            engine.hostParty()
                        }) {
                            VStack(spacing: 4) {
                                Text("[ 👑 HOST A PARTY ]")
                                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                                    .foregroundColor(Color(red: 0.0, green: 1.0, blue: 0.0))
                                Text("Create Host IP & QR Code to Broadcast Sound")
                                    .font(.system(size: 10, design: .monospaced))
                                    .foregroundColor(Color(white: 0.7))
                            }
                            .frame(maxWidth: .infinity, minHeight: 64)
                            .background(Color(red: 0.0, green: 0.13, blue: 0.0))
                            .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(red: 0.0, green: 1.0, blue: 0.0), lineWidth: 1.5))
                        }

                        Button(action: {
                            engine.partyScreen = "JOIN"
                        }) {
                            VStack(spacing: 4) {
                                Text("[ 🎧 JOIN A PARTY ]")
                                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                                    .foregroundColor(Color(red: 0.0, green: 0.93, blue: 0.93))
                                Text("Scan QR or Join from Discovered Party Hosts")
                                    .font(.system(size: 10, design: .monospaced))
                                    .foregroundColor(Color(white: 0.7))
                            }
                            .frame(maxWidth: .infinity, minHeight: 64)
                            .background(Color(red: 0.0, green: 0.09, blue: 0.13))
                            .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(red: 0.0, green: 0.93, blue: 0.93), lineWidth: 1.5))
                        }
                    }
                    .padding(14)
                    .background(Color(red: 0.04, green: 0.05, blue: 0.08))
                    .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(white: 0.2), lineWidth: 1))

                } else if engine.partyScreen == "HOST" {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Button(action: {
                                engine.leaveParty()
                            }) {
                                Text("[ ← BACK ]")
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.4), lineWidth: 1))
                            }
                            Spacer()
                            Text("👑 HOST MODE")
                                .font(.system(size: 12, weight: .bold, design: .monospaced))
                                .foregroundColor(Color(red: 1.0, green: 0.85, blue: 0.0))
                        }

                        let hostQrUri = engine.makePartyQrUri(isExtended: false)
                        VStack(spacing: 6) {
                            Text("👑 HOST IP: \(engine.localIpAddress)")
                                .font(.system(size: 12, weight: .bold, design: .monospaced))
                                .foregroundColor(Color(red: 1.0, green: 0.85, blue: 0.0))

                            if let qrImg = QRCodeHelper.generateQRCode(from: hostQrUri) {
                                Image(uiImage: qrImg)
                                    .interpolation(.none)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 160, height: 160)
                                    .background(Color.white)
                                    .padding(4)
                            }

                            HStack(spacing: 12) {
                                Button("↻ REFRESH HOST IP & QR") {
                                    engine.broadcastBeacon()
                                }
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                .foregroundColor(Color(red: 0.0, green: 0.93, blue: 0.93))

                                Button("📋 COPY LINK") {
                                    UIPasteboard.general.string = hostQrUri
                                }
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                .foregroundColor(Color(red: 1.0, green: 0.85, blue: 0.0))
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(10)
                        .background(Color(red: 0.05, green: 0.05, blue: 0.05))
                        .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(white: 0.2), lineWidth: 1))

                        Button(action: {
                            if engine.isAudioPlaying {
                                engine.stopSpeakerPlayer()
                            } else {
                                engine.startSpeakerPlayer()
                                engine.broadcastBeacon()
                            }
                        }) {
                            Text(engine.isAudioPlaying ? "[ ■ STOP PARTY BROADCAST (HOST) ]" : "[ 👑 START PARTY BROADCAST (HOST) ]")
                                .font(.system(size: 13, weight: .bold, design: .monospaced))
                                .foregroundColor(engine.isAudioPlaying ? Color(red: 1.0, green: 0.27, blue: 0.27) : Color(red: 0.0, green: 1.0, blue: 0.0))
                                .frame(maxWidth: .infinity, minHeight: 46)
                                .background(engine.isAudioPlaying ? Color(red: 0.20, green: 0.0, blue: 0.0) : Color(red: 0.0, green: 0.17, blue: 0.0))
                                .overlay(RoundedRectangle(cornerRadius: 4).stroke(engine.isAudioPlaying ? Color(red: 1.0, green: 0.27, blue: 0.27) : Color(red: 0.0, green: 1.0, blue: 0.0), lineWidth: 1.5))
                        }

                        Text(engine.isAudioPlaying ? "PARTY STATUS: HOSTING / RELAYING (\(engine.partyMembers.count) peer(s) connected)" : "PARTY STATUS: STANDBY — Tap Start Party Broadcast")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(engine.isAudioPlaying ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.55))

                        // Target Buffer Control & Host Equalizer
                        TargetBufferControlSection(engine: engine)

                        Button(action: {
                            showHostEQSheet = true
                        }) {
                            Text("[ 🎛️ HOST EQUALIZER: \(engine.partyHostEQ.enabled ? engine.partyHostEQ.presetName : "OFF") ]")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(engine.partyHostEQ.enabled ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.7))
                                .frame(maxWidth: .infinity, minHeight: 34)
                                .background(engine.partyHostEQ.enabled ? Color(red: 0.06, green: 0.13, blue: 0.06) : Color(white: 0.06))
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(engine.partyHostEQ.enabled ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.25), lineWidth: 1))
                        }
                        .sheet(isPresented: $showHostEQSheet) {
                            StudioEqualizerSheetView(title: "PARTY HOST EQUALIZER", config: $engine.partyHostEQ, engine: engine)
                        }

                        VStack(alignment: .leading, spacing: 4) {
                            Text("LIVE DELAY GRAPH")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(.white)

                            LiveDelayGraphView(
                                history: engine.delayHistory,
                                targetMs: Double(engine.bufferTargetMs),
                                currentBufMs: engine.latencyMs
                            )
                        }

                        // Manual Add Peer
                        HStack(spacing: 6) {
                            TextField("Peer IP (e.g. 192.168.1.55)", text: $manualPeerInput)
                                .font(.system(size: 11, design: .monospaced))
                                .padding(6)
                                .background(Color(red: 0.07, green: 0.07, blue: 0.07))
                                .foregroundColor(.white)
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.2), lineWidth: 1))

                            Button("ADD IP") {
                                if !manualPeerInput.isEmpty {
                                    engine.addManualPeer(ip: manualPeerInput)
                                    manualPeerInput = ""
                                }
                            }
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(Color(red: 0.0, green: 0.93, blue: 0.93))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 6)
                            .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(red: 0.0, green: 0.93, blue: 0.93), lineWidth: 1))

                            Button("CLEAR") {
                                engine.partyMembers.removeAll()
                                engine.partyPeerIps.removeAll()
                            }
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(Color(red: 1.0, green: 0.27, blue: 0.27))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 6)
                            .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(red: 1.0, green: 0.27, blue: 0.27), lineWidth: 1))
                        }

                        // Connected Members
                        VStack(alignment: .leading, spacing: 4) {
                            Text("CONNECTED JOINERS (\(engine.partyMembers.count)):")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(.white)

                            if engine.partyMembers.isEmpty {
                                Text("No phones joined yet. Friends can scan the QR code to connect.")
                                    .font(.system(size: 10, design: .monospaced))
                                    .foregroundColor(Color(white: 0.5))
                            } else {
                                ForEach(engine.partyMembers) { member in
                                    HStack {
                                        Text("\(member.name) — \(member.ip)")
                                            .font(.system(size: 11, design: .monospaced))
                                            .foregroundColor(.white)
                                        Spacer()
                                        Button("KICK") {
                                            engine.kickMember(ip: member.ip)
                                        }
                                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                                        .foregroundColor(Color(red: 1.0, green: 0.27, blue: 0.27))
                                    }
                                    .padding(4)
                                }
                            }
                        }
                    }

                } else if engine.partyScreen == "JOIN" {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Button(action: {
                                engine.leaveParty()
                            }) {
                                Text("[ ← BACK ]")
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.4), lineWidth: 1))
                            }
                            Spacer()
                            Button("↻ SCAN") {
                                engine.broadcastBeacon()
                            }
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(Color(red: 0.0, green: 0.93, blue: 0.93))
                        }

                        // Discovered Hosts List
                        VStack(alignment: .leading, spacing: 4) {
                            Text("DISCOVERED PARTY HOSTS:")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(.white)

                            if engine.discoveredPartyHosts.isEmpty {
                                Text("Scanning local network for Party Hosts...")
                                    .font(.system(size: 10, design: .monospaced))
                                    .foregroundColor(Color(white: 0.5))
                            } else {
                                ForEach(engine.discoveredPartyHosts) { host in
                                    Button(action: {
                                        engine.joinParty(hostIp: host.ip, port: host.port)
                                    }) {
                                        HStack {
                                            Text("👑 \(host.name) (\(host.ip):\(host.port))")
                                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                                .foregroundColor(host.isExtended ? Color(red: 1.0, green: 0.85, blue: 0.0) : Color(red: 0.0, green: 1.0, blue: 0.0))
                                            Spacer()
                                            Text("TAP TO JOIN")
                                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                                .foregroundColor(Color(red: 0.0, green: 0.93, blue: 0.93))
                                        }
                                        .padding(6)
                                        .background(Color(red: 0.08, green: 0.08, blue: 0.08))
                                        .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.2), lineWidth: 1))
                                    }
                                }
                            }
                        }

                        // Host IP & Port Manual input
                        HStack(spacing: 6) {
                            TextField("Host IP", text: $manualJoinIp)
                                .font(.system(size: 11, design: .monospaced))
                                .padding(6)
                                .background(Color(red: 0.07, green: 0.07, blue: 0.07))
                                .foregroundColor(.white)
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.2), lineWidth: 1))

                            Button("📷 SCAN QR") {
                                showScanner = true
                            }
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundColor(Color(red: 0.0, green: 0.93, blue: 0.93))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 6)
                            .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(red: 0.0, green: 0.93, blue: 0.93), lineWidth: 1))

                            Button("📋 PASTE") {
                                if let pasted = UIPasteboard.general.string {
                                    let (ip, _) = PhoneSpeakerEngine.parseHostIpFromUri(pasted)
                                    if !ip.isEmpty { manualJoinIp = ip }
                                }
                            }
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 6)
                            .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.4), lineWidth: 1))
                        }

                        Button(action: {
                            if engine.isAudioPlaying && engine.isPartyJoined {
                                engine.leaveParty()
                            } else {
                                let target = manualJoinIp.trimmingCharacters(in: .whitespacesAndNewlines)
                                if !target.isEmpty {
                                    let (ip, port) = PhoneSpeakerEngine.parseHostIpFromUri(target)
                                    engine.joinParty(hostIp: ip, port: port)
                                } else if let firstHost = engine.discoveredPartyHosts.first {
                                    manualJoinIp = firstHost.ip
                                    engine.joinParty(hostIp: firstHost.ip, port: firstHost.port)
                                }
                            }
                        }) {
                            Text(engine.isAudioPlaying && engine.isPartyJoined ? (engine.isPartyExtending ? "[ ■ STOP EXTENDING & LEAVE PARTY ]" : "[ ■ LEAVE PARTY STREAM ]") : "[ 🎧 JOIN PARTY STREAM ]")
                                .font(.system(size: 13, weight: .bold, design: .monospaced))
                                .foregroundColor(engine.isAudioPlaying && engine.isPartyJoined ? Color(red: 1.0, green: 0.27, blue: 0.27) : Color(red: 0.0, green: 1.0, blue: 0.0))
                                .frame(maxWidth: .infinity, minHeight: 46)
                                .background(engine.isAudioPlaying && engine.isPartyJoined ? Color(red: 0.20, green: 0.0, blue: 0.0) : Color(red: 0.0, green: 0.17, blue: 0.0))
                                .overlay(RoundedRectangle(cornerRadius: 4).stroke(engine.isAudioPlaying && engine.isPartyJoined ? Color(red: 1.0, green: 0.27, blue: 0.27) : Color(red: 0.0, green: 1.0, blue: 0.0), lineWidth: 1.5))
                        }

                        Text(engine.isAudioPlaying && engine.isPartyJoined ? "PARTY JOINER: Connected to \(engine.currentPartyHostIp):5004 • Playing host sound in sync" : "PARTY JOINER: Standby — Select a host above or enter Host IP")
                            .font(.system(size: 11, design: .monospaced))
                            .padding(8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(red: 0.07, green: 0.07, blue: 0.07))
                            .foregroundColor(engine.isAudioPlaying && engine.isPartyJoined ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.55))

                        // Target Buffer Control & Join Equalizer
                        TargetBufferControlSection(engine: engine)

                        Button(action: {
                            showJoinEQSheet = true
                        }) {
                            Text("[ 🎛️ JOIN EQUALIZER: \(engine.partyJoinEQ.enabled ? engine.partyJoinEQ.presetName : "OFF") ]")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(engine.partyJoinEQ.enabled ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.7))
                                .frame(maxWidth: .infinity, minHeight: 34)
                                .background(engine.partyJoinEQ.enabled ? Color(red: 0.06, green: 0.13, blue: 0.06) : Color(white: 0.06))
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(engine.partyJoinEQ.enabled ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.25), lineWidth: 1))
                        }
                        .sheet(isPresented: $showJoinEQSheet) {
                            StudioEqualizerSheetView(title: "PARTY JOIN EQUALIZER", config: $engine.partyJoinEQ, engine: engine)
                        }

                        VStack(alignment: .leading, spacing: 4) {
                            Text("LIVE DELAY GRAPH")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(.white)

                            LiveDelayGraphView(
                                history: engine.delayHistory,
                                targetMs: Double(engine.bufferTargetMs),
                                currentBufMs: engine.latencyMs
                            )
                        }

                        // Post-Join: Extend This Party (Hotspot Repeater)
                        if engine.isPartyJoined {
                            VStack(alignment: .leading, spacing: 8) {
                                Toggle(isOn: Binding(
                                    get: { engine.isPartyExtending },
                                    set: { _ in engine.toggleExtendParty() }
                                )) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("📡 EXTEND THIS PARTY (HOTSPOT REPEATER)")
                                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                                            .foregroundColor(Color(red: 1.0, green: 0.85, blue: 0.0))
                                        Text("Re-broadcast incoming party sound to phones connected to your hotspot.")
                                            .font(.system(size: 9, design: .monospaced))
                                            .foregroundColor(Color(white: 0.6))
                                    }
                                }
                                .toggleStyle(SwitchToggleStyle(tint: Color(red: 1.0, green: 0.85, blue: 0.0)))

                                if engine.isPartyExtending {
                                    let extQrUri = engine.makePartyQrUri(isExtended: true)
                                    VStack(spacing: 4) {
                                        if let repQr = QRCodeHelper.generateQRCode(from: extQrUri) {
                                            Image(uiImage: repQr)
                                                .interpolation(.none)
                                                .resizable()
                                                .scaledToFit()
                                                .frame(width: 130, height: 130)
                                                .background(Color.white)
                                                .padding(4)
                                        }
                                        Text("REPEATER IP: \(engine.localIpAddress)")
                                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                                            .foregroundColor(Color(red: 1.0, green: 0.85, blue: 0.0))
                                    }
                                    .frame(maxWidth: .infinity)
                                }
                            }
                            .padding(10)
                            .background(Color(red: 0.07, green: 0.07, blue: 0.05))
                            .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(red: 1.0, green: 0.85, blue: 0.0), lineWidth: 1))
                        }
                    }
                }
            }
            .padding(14)
        }
        .sheet(isPresented: $showScanner) {
            QRScannerView { code in
                let (hostIp, port) = PhoneSpeakerEngine.parseHostIpFromUri(code)
                if !hostIp.isEmpty {
                    engine.joinParty(hostIp: hostIp, port: port)
                }
            }
        }
    }
}

// MARK: - Tab 4: SETTINGS STUDIO TAB
struct SettingsStudioTab: View {
    @ObservedObject var engine: PhoneSpeakerEngine
    @State private var showUniversalEQSheet: Bool = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Audio Quality (Synced with PC)
                VStack(alignment: .leading, spacing: 6) {
                    Text("AUDIO QUALITY (SYNCED WITH PC):")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    RadioLine(title: "Compressed (Opus)", isSelected: false) {
                        engine.sendConfigSync(forcePcm: true)
                    }
                    RadioLine(title: "Uncompressed (Lossless PCM16 48 kHz)", isSelected: true) {
                        engine.sendConfigSync(forcePcm: true)
                    }

                    Text("OPUS BITRATE: 192 kbps")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(Color(white: 0.55))
                }

                // Audio Output Engine
                VStack(alignment: .leading, spacing: 6) {
                    Text("AUDIO OUTPUT ENGINE:")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    RadioLine(title: "AVAudioEngine Low-Latency", isSelected: engine.outputEngineMode == "OBOE_LOW_LATENCY") {
                        engine.outputEngineMode = "OBOE_LOW_LATENCY"
                        engine.sendConfigSync()
                    }
                    RadioLine(title: "Standard AudioUnit Output", isSelected: engine.outputEngineMode == "AUDIOTRACK_LEGACY") {
                        engine.outputEngineMode = "AUDIOTRACK_LEGACY"
                        engine.sendConfigSync()
                    }
                }

                // Audio Effects
                Toggle(isOn: Binding(
                    get: { engine.enableAudioEffects },
                    set: { newVal in
                        engine.enableAudioEffects = newVal
                        engine.sendConfigSync()
                    }
                )) {
                    Text("Enable Audio Effects / Equalizer")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(.white)
                }
                .toggleStyle(SwitchToggleStyle(tint: Color(red: 0.0, green: 1.0, blue: 0.0)))

                // Universal Device Equalizer (5-Band)
                VStack(alignment: .leading, spacing: 6) {
                    Text("UNIVERSAL DEVICE EQUALIZER (5-BAND):")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    Button(action: {
                        showUniversalEQSheet = true
                    }) {
                        Text("[ 🎛️ UNIVERSAL EQUALIZER: \(engine.universalEQ.enabled ? engine.universalEQ.presetName : "OFF") ]")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(engine.universalEQ.enabled ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.7))
                            .frame(maxWidth: .infinity, minHeight: 34)
                            .background(engine.universalEQ.enabled ? Color(red: 0.06, green: 0.13, blue: 0.06) : Color(white: 0.06))
                            .overlay(RoundedRectangle(cornerRadius: 3).stroke(engine.universalEQ.enabled ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.25), lineWidth: 1))
                    }
                    .sheet(isPresented: $showUniversalEQSheet) {
                        StudioEqualizerSheetView(title: "UNIVERSAL EQUALIZER", config: $engine.universalEQ, engine: engine)
                    }

                    Toggle(isOn: $engine.universalApplyTx) {
                        Text("Apply Universal EQ to Outbound Audio (SERVE / HOST)")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundColor(Color(white: 0.8))
                    }
                    .toggleStyle(SwitchToggleStyle(tint: Color(red: 0.0, green: 1.0, blue: 0.0)))

                    Toggle(isOn: $engine.universalApplyRx) {
                        Text("Apply Universal EQ to Inbound Audio (PLAYER / JOIN)")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundColor(Color(white: 0.8))
                    }
                    .toggleStyle(SwitchToggleStyle(tint: Color(red: 0.0, green: 1.0, blue: 0.0)))
                }
                .padding(10)
                .background(Color(red: 0.05, green: 0.05, blue: 0.05))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(white: 0.2), lineWidth: 1))

                // Exclusive Audio
                VStack(alignment: .leading, spacing: 6) {
                    Text("EXCLUSIVE AUDIO (AUDIO FOCUS):")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)

                    RadioLine(title: "Mix With Others", isSelected: engine.exclusiveAudioMode == "MIX_WITH_OTHERS") {
                        engine.exclusiveAudioMode = "MIX_WITH_OTHERS"
                        engine.sendConfigSync()
                    }
                    RadioLine(title: "Duck Others When Playing", isSelected: engine.exclusiveAudioMode == "DUCK_OTHERS") {
                        engine.exclusiveAudioMode = "DUCK_OTHERS"
                        engine.sendConfigSync()
                    }
                }

                // Headset Disconnect
                Toggle(isOn: Binding(
                    get: { engine.stopOnHeadsetDisconnect },
                    set: { newVal in
                        engine.stopOnHeadsetDisconnect = newVal
                        engine.sendConfigSync()
                    }
                )) {
                    Text("Pause stream on headset disconnection")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(.white)
                }
                .toggleStyle(SwitchToggleStyle(tint: Color(red: 0.0, green: 1.0, blue: 0.0)))

                // Available Adapter IPs
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("AVAILABLE ADAPTER IPS:")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                        Spacer()
                        Button("[ REFRESH ]") {
                            engine.refreshAdapterIps()
                        }
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundColor(Color(red: 0.0, green: 0.93, blue: 0.93))
                    }

                    Text(engine.availableIpListText.isEmpty ? "en0        : 127.0.0.1" : engine.availableIpListText)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(Color(red: 0.0, green: 1.0, blue: 0.0))
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(red: 0.05, green: 0.05, blue: 0.05))
                        .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.2), lineWidth: 1))
                }
            }
            .padding(14)
        }
    }
}

// MARK: - Live Delay Graph View (Canvas)
struct LiveDelayGraphView: View {
    let history: [Double]
    let targetMs: Double
    let currentBufMs: Double

    var body: some View {
        VStack(spacing: 2) {
            HStack {
                let estTotal = Int(currentBufMs + 35.0)
                Text(String(format: "BUF: %.1f ms | EST: ~%d ms | TGT: %.0f ms", currentBufMs, estTotal, targetMs))
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(Color(red: 0.0, green: 0.93, blue: 0.93))
                Spacer()
            }
            .padding(.horizontal, 4)

            GeometryReader { geo in
                let w = geo.size.width
                let h = geo.size.height
                let maxMs: Double = 160.0

                ZStack {
                    Color(red: 0.04, green: 0.04, blue: 0.04)

                    // Grid lines at 40, 80, 120 ms
                    Path { path in
                        for ms in [40.0, 80.0, 120.0] {
                            let y = CGFloat(h - (ms / maxMs) * h)
                            path.move(to: CGPoint(x: 0, y: y))
                            path.addLine(to: CGPoint(x: w, y: y))
                        }
                    }
                    .stroke(Color(white: 0.15), lineWidth: 1)

                    // Dashed target line
                    Path { path in
                        let targetY = CGFloat(h - (min(targetMs, maxMs) / maxMs) * h)
                        path.move(to: CGPoint(x: 0, y: targetY))
                        path.addLine(to: CGPoint(x: w, y: targetY))
                    }
                    .stroke(Color(white: 0.4), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))

                    // Buffer curve
                    if history.count > 1 {
                        Path { path in
                            let step = w / CGFloat(max(1, history.count - 1))
                            for (idx, val) in history.enumerated() {
                                let x = CGFloat(idx) * step
                                let clamped = min(max(val, 0.0), maxMs)
                                let y = CGFloat(h - (clamped / maxMs) * h)
                                if idx == 0 {
                                    path.move(to: CGPoint(x: x, y: y))
                                } else {
                                    path.addLine(to: CGPoint(x: x, y: y))
                                }
                            }
                        }
                        .stroke(currentBufMs > targetMs + 20.0 ? Color(red: 1.0, green: 0.53, blue: 0.0) : Color(red: 0.0, green: 1.0, blue: 0.0), lineWidth: 2)
                    }
                }
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.2), lineWidth: 1))
            }
            .frame(height: 80)
        }
    }
}

// MARK: - Reusable Monospace UI Components
struct RadioChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 10, weight: isSelected ? .bold : .regular, design: .monospaced))
                .foregroundColor(isSelected ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.6))
                .padding(.horizontal, 6)
                .padding(.vertical, 4)
                .background(isSelected ? Color(red: 0.06, green: 0.13, blue: 0.06) : Color(white: 0.05))
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(isSelected ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.2), lineWidth: 1))
        }
    }
}

struct RadioLine: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(isSelected ? "[●]" : "[○]")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(isSelected ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.5))

                Text(title)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(isSelected ? .white : Color(white: 0.7))
                Spacer()
            }
        }
    }
}

struct TelemetryRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.system(size: 10, design: .monospaced))
                .foregroundColor(Color(white: 0.55))
            Spacer()
            Text(value)
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundColor(.white)
        }
    }
}

// MARK: - Target Buffer Control Section (Reusable across tabs)
struct TargetBufferControlSection: View {
    @ObservedObject var engine: PhoneSpeakerEngine

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("STABILITY / DELAY BUFFER:")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(.white)

            HStack(spacing: 4) {
                RadioChip(title: "20 ms (Low)", isSelected: engine.bufferMode == "FIXED" && engine.bufferTargetMs == 20) {
                    engine.bufferMode = "FIXED"
                    engine.bufferTargetMs = 20
                    engine.sendConfigSync()
                }
                RadioChip(title: "40 ms (Med)", isSelected: engine.bufferMode == "FIXED" && engine.bufferTargetMs == 40) {
                    engine.bufferMode = "FIXED"
                    engine.bufferTargetMs = 40
                    engine.sendConfigSync()
                }
                RadioChip(title: "80 ms (High)", isSelected: engine.bufferMode == "FIXED" && engine.bufferTargetMs == 80) {
                    engine.bufferMode = "FIXED"
                    engine.bufferTargetMs = 80
                    engine.sendConfigSync()
                }
                RadioChip(title: "Auto", isSelected: engine.bufferMode == "AUTO") {
                    engine.bufferMode = "AUTO"
                    engine.sendConfigSync()
                }
            }

            HStack {
                Text("TARGET BUFFER:")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(Color(white: 0.6))
                Spacer()
                Text("\(engine.bufferTargetMs) ms")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(.white)
            }

            Slider(
                value: Binding(
                    get: { Double(engine.bufferTargetMs) },
                    set: { newVal in
                        engine.bufferTargetMs = Int(newVal)
                        engine.sendConfigSync()
                    }
                ),
                in: 10.0...200.0,
                step: 2.5
            )
            .accentColor(.white)
        }
    }
}

// MARK: - Studio 5-Band Equalizer Sheet View
struct StudioEqualizerSheetView: View {
    let title: String
    @Binding var config: StudioEQConfig
    @ObservedObject var engine: PhoneSpeakerEngine
    @Environment(\.presentationMode) var presentationMode
    @State private var newPresetName: String = ""

    private let bandLabels = ["60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz"]

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 12) {
                // Header
                HStack {
                    Text("🎛️ \(title)")
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundColor(Color(red: 0.0, green: 1.0, blue: 0.0))
                    Spacer()
                    Button("✕ CLOSE") {
                        presentationMode.wrappedValue.dismiss()
                    }
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(.white)
                }

                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        // Enable Toggle
                        Toggle(isOn: $config.enabled) {
                            Text("Enable Equalizer")
                                .font(.system(size: 12, weight: .bold, design: .monospaced))
                                .foregroundColor(.white)
                        }
                        .toggleStyle(SwitchToggleStyle(tint: Color(red: 0.0, green: 1.0, blue: 0.0)))

                        // Presets
                        VStack(alignment: .leading, spacing: 6) {
                            Text("PRESETS:")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(Color(white: 0.6))

                            // Built-in presets
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 6) {
                                    ForEach(Array(PhoneSpeakerEngine.builtinEQPresets.keys.sorted()), id: \.self) { pName in
                                        Button(action: {
                                            if let pGains = PhoneSpeakerEngine.builtinEQPresets[pName] {
                                                config.presetName = pName
                                                config.bands = pGains
                                                config.enabled = true
                                            }
                                        }) {
                                            Text(pName)
                                                .font(.system(size: 10, weight: config.presetName == pName ? .bold : .regular, design: .monospaced))
                                                .foregroundColor(config.presetName == pName ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.7))
                                                .padding(.horizontal, 8)
                                                .padding(.vertical, 4)
                                                .background(config.presetName == pName ? Color(red: 0.06, green: 0.13, blue: 0.06) : Color(white: 0.06))
                                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(config.presetName == pName ? Color(red: 0.0, green: 1.0, blue: 0.0) : Color(white: 0.2), lineWidth: 1))
                                        }
                                    }
                                }
                            }

                            // Custom presets if any
                            if !engine.customEQPresets.isEmpty {
                                Text("CUSTOM PRESETS:")
                                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                                    .foregroundColor(Color(white: 0.5))
                                    .padding(.top, 4)

                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 6) {
                                        ForEach(Array(engine.customEQPresets.keys.sorted()), id: \.self) { cName in
                                            HStack(spacing: 2) {
                                                Button(action: {
                                                    if let cGains = engine.customEQPresets[cName] {
                                                        config.presetName = cName
                                                        config.bands = cGains
                                                        config.enabled = true
                                                    }
                                                }) {
                                                    Text(cName)
                                                        .font(.system(size: 10, weight: config.presetName == cName ? .bold : .regular, design: .monospaced))
                                                        .foregroundColor(config.presetName == cName ? Color(red: 0.22, green: 0.71, blue: 1.0) : Color(white: 0.7))
                                                }
                                                Button(action: {
                                                    engine.deleteCustomEQPreset(name: cName)
                                                }) {
                                                    Text("×")
                                                        .font(.system(size: 12, weight: .bold))
                                                        .foregroundColor(Color(red: 1.0, green: 0.27, blue: 0.27))
                                                }
                                            }
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 3)
                                            .background(Color(white: 0.06))
                                            .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.2), lineWidth: 1))
                                        }
                                    }
                                }
                            }

                            // Save new preset
                            HStack(spacing: 6) {
                                TextField("New Preset Name", text: $newPresetName)
                                    .font(.system(size: 11, design: .monospaced))
                                    .padding(6)
                                    .background(Color(white: 0.08))
                                    .foregroundColor(.white)
                                    .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.2), lineWidth: 1))

                                Button("SAVE") {
                                    let trimmed = newPresetName.trimmingCharacters(in: .whitespacesAndNewlines)
                                    if !trimmed.isEmpty {
                                        engine.saveCustomEQPreset(name: trimmed, bands: config.bands)
                                        config.presetName = trimmed
                                        newPresetName = ""
                                    }
                                }
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(Color(red: 0.0, green: 0.93, blue: 0.93))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(red: 0.0, green: 0.93, blue: 0.93), lineWidth: 1))

                                Button("FLAT RESET") {
                                    config.bands = [0.0, 0.0, 0.0, 0.0, 0.0]
                                    config.presetName = "Flat"
                                }
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(Color(white: 0.7))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 6)
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.3), lineWidth: 1))
                            }
                            .padding(.top, 4)
                        }

                        // 5 Sliders
                        VStack(alignment: .leading, spacing: 8) {
                            Text("FREQUENCY BANDS (-12 dB to +12 dB):")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(.white)

                            ForEach(0..<5, id: \.self) { idx in
                                VStack(alignment: .leading, spacing: 2) {
                                    HStack {
                                        Text(bandLabels[idx])
                                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                                            .foregroundColor(Color(red: 0.22, green: 0.71, blue: 1.0))
                                        Spacer()
                                        Text(String(format: "%+.1f dB", idx < config.bands.count ? config.bands[idx] : 0.0))
                                            .font(.system(size: 11, design: .monospaced))
                                            .foregroundColor(.white)
                                    }
                                    Slider(
                                        value: Binding(
                                            get: { Double(idx < config.bands.count ? config.bands[idx] : 0.0) },
                                            set: { newVal in
                                                while config.bands.count < 5 { config.bands.append(0.0) }
                                                config.bands[idx] = Float(newVal)
                                                config.presetName = "Custom"
                                            }
                                        ),
                                        in: -12.0...12.0,
                                        step: 0.5
                                    )
                                    .accentColor(Color(red: 0.0, green: 1.0, blue: 0.0))
                                }
                            }
                        }
                    }
                    .padding(.top, 4)
                }
            }
            .padding(14)
        }
    }
}

// MARK: - Studio Help Modal
struct StudioHelpModal: View {
    @Binding var isPresented: Bool

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("PHONESPEAKER v2.1 STUDIO GUIDE")
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundColor(Color(red: 0.0, green: 1.0, blue: 0.0))
                    Spacer()
                    Button("✕ CLOSE") {
                        isPresented = false
                    }
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(.white)
                }
                .padding(.bottom, 6)

                ScrollView {
                    Text("""
============================================================
PHONESPEAKER v2.1 STUDIO — CROSS-PLATFORM GUIDE
============================================================

PLATFORMS:
• iOS 16.0–18+ (iPhone & iPad with AVAudioEngine)
• Android 8.0–15+ (Oboe C++ AAudio ultra-low latency)
• Windows 11 / 10 (WASAPI Loopback, BlackHole / PipeWire)
• macOS Sonoma / Ventura / Monterey (CoreAudio / BlackHole)
• Linux: Ubuntu, Mint, Arch, Debian (PipeWire / PulseAudio)

1. WIRELESS SPEAKER PLAYER (PORT 5004):
------------------------------------------------------------
* Connect both sender and receiver to the same Wi-Fi or Hotspot.
* Tap [ ▶ START SPEAKER PLAYER (PORT 5004) ] on iPhone.
* Stream real-time lossless 48 kHz stereo PCM16 with <35 ms delay!

2. INTERNAL SYSTEM AUDIO SERVE (PORT 5004):
------------------------------------------------------------
* Tap [ 📡 START SERVING AUDIO (PORT 5004) ] in SERVE tab.
* Captures device audio (music, videos, games) via ReplayKit.
* Streams to any PC, Mac, Linux, or Phone running PhoneSpeaker.

3. MULTI-DEVICE PARTY MODE:
------------------------------------------------------------
* 👑 HOST: Tap [ 👑 HOST A PARTY ] to generate a QR code.
  Multiple phones and PCs can join the party simultaneously.
* 🎧 JOIN: Tap [ 🎧 JOIN A PARTY ] to scan QR code or select
  discovered hosts on the network.
* 📡 EXTEND: Turn on Personal Hotspot and tap 'Extend This Party'
  to act as a secondary repeater for infinite range!
============================================================
""")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(Color(white: 0.8))
                }
            }
            .padding(14)
        }
    }
}
