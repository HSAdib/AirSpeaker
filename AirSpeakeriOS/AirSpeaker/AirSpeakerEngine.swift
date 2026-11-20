//
//  AirSpeakerEngine.swift
//  AirSpeaker
//
//  Full native iOS audio streaming, jitter buffering, microphone transmission,
//  multi-device party mode daisy-chain relay, and control protocol.
//  100% wire-compatible with Windows, Linux, macOS, and Android (Air Speaker v1.0).
//

import Foundation
import UIKit
import AVFoundation
import CoreMedia
import ReplayKit
import Network
import Combine

// MARK: - Party Models
public struct PartyMember: Identifiable, Equatable {
    public let id: String
    public let name: String
    public let ip: String
    public var lastSeen: Date
    public var isManual: Bool

    public init(name: String, ip: String, isManual: Bool = false) {
        self.id = "\(name)_\(ip)"
        self.name = name
        self.ip = ip
        self.lastSeen = Date()
        self.isManual = isManual
    }
}

public struct DiscoveredHost: Identifiable, Equatable {
    public let id: String
    public let name: String
    public let ip: String
    public let port: Int
    public var isExtended: Bool
    public var lastSeen: Date

    public init(name: String, ip: String, port: Int = 5004, isExtended: Bool = false) {
        self.id = "\(name)_\(ip)_\(port)"
        self.name = name
        self.ip = ip
        self.port = port
        self.isExtended = isExtended
        self.lastSeen = Date()
    }
}

// MARK: - Thread-safe Atomic Counter Helper
final class AtomicInt {
    private var val: Int = 0
    private let lock = NSLock()

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return val
    }

    func increment() {
        lock.lock()
        val += 1
        lock.unlock()
    }

    func decrement() {
        lock.lock()
        if val > 0 { val -= 1 }
        lock.unlock()
    }

    func reset() {
        lock.lock()
        val = 0
        lock.unlock()
    }
}

// MARK: - 5-Band Biquad Equalizer Config & DSP
public struct StudioEQConfig: Codable, Equatable {
    public var enabled: Bool = false
    public var presetName: String = "Flat"
    public var bands: [Float] = [0, 0, 0, 0, 0] // 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz

    public init(enabled: Bool = false, presetName: String = "Flat", bands: [Float] = [0, 0, 0, 0, 0]) {
        self.enabled = enabled
        self.presetName = presetName
        self.bands = (bands + [0, 0, 0, 0, 0]).prefix(5).map { $0 }
    }
}

public final class Swift5BandBiquadDSP {
    private struct Coeffs {
        var b0: Double = 1.0
        var b1: Double = 0.0
        var b2: Double = 0.0
        var a1: Double = 0.0
        var a2: Double = 0.0
        var active: Bool = false
    }
    private let lock = NSLock()
    private var coeffs: [Coeffs] = Array(repeating: Coeffs(), count: 5)
    private var z1 = [[Double]](repeating: [0.0, 0.0], count: 5)
    private var z2 = [[Double]](repeating: [0.0, 0.0], count: 5)
    private var isEnabled: Bool = false
    private var needsStateReset: Bool = false

    public func setGains(enabled: Bool, gains: [Float]) {
        let freqs: [Double] = [60.0, 230.0, 910.0, 3600.0, 14000.0]
        let sr: Double = 48000.0
        let q: Double = 1.15
        var anyActive = false
        var newCoeffs = Array(repeating: Coeffs(), count: 5)

        for i in 0..<5 {
            let g = i < gains.count ? Double(max(-12.0, min(12.0, gains[i]))) : 0.0
            if abs(g) <= 0.05 {
                newCoeffs[i] = Coeffs(b0: 1.0, b1: 0.0, b2: 0.0, a1: 0.0, a2: 0.0, active: false)
                continue
            }
            anyActive = true
            let A = pow(10.0, g / 40.0)
            let w0 = 2.0 * Double.pi * freqs[i] / sr
            let alpha = sin(w0) / (2.0 * q)
            let cos_w0 = cos(w0)
            let a0 = 1.0 + alpha / A

            newCoeffs[i] = Coeffs(
                b0: (1.0 + alpha * A) / a0,
                b1: (-2.0 * cos_w0) / a0,
                b2: (1.0 - alpha * A) / a0,
                a1: (-2.0 * cos_w0) / a0,
                a2: (1.0 - alpha / A) / a0,
                active: true
            )
        }

        lock.lock()
        let wasEnabled = self.isEnabled
        let nextEnabled = enabled && anyActive
        self.coeffs = newCoeffs
        self.isEnabled = nextEnabled
        if !nextEnabled || (!wasEnabled && nextEnabled) {
            self.needsStateReset = true
        }
        lock.unlock()
    }

    public func processInt16Samples(_ samples: inout [Int16], channels: Int = 2) {
        lock.lock()
        let isEn = isEnabled
        let localCoeffs = coeffs
        let shouldReset = needsStateReset
        if shouldReset {
            needsStateReset = false
        }
        lock.unlock()

        if shouldReset {
            for i in 0..<5 {
                z1[i] = [0.0, 0.0]
                z2[i] = [0.0, 0.0]
            }
        }
        guard isEn, !samples.isEmpty else { return }

        let ch = channels == 1 ? 1 : 2
        let numFrames = samples.count / ch
        let knee: Double = 0.85

        for n in 0..<numFrames {
            for c in 0..<ch {
                var x = Double(samples[n * ch + c]) / 32768.0
                for b in 0..<5 {
                    let coeff = localCoeffs[b]
                    if !coeff.active { continue }
                    let y = coeff.b0 * x + z1[b][c]
                    z1[b][c] = coeff.b1 * x - coeff.a1 * y + z2[b][c]
                    z2[b][c] = coeff.b2 * x - coeff.a2 * y
                    x = y
                }
                if x > knee {
                    x = knee + (1.0 - knee) * tanh((x - knee) / (1.0 - knee))
                } else if x < -knee {
                    x = -knee - (1.0 - knee) * tanh((-x - knee) / (1.0 - knee))
                }
                let clamped = Int32(round(max(-1.0, min(1.0, x)) * 32767.0))
                samples[n * ch + c] = Int16(max(-32768, min(32767, clamped)))
            }
        }
    }
}

// MARK: - AirSpeakerEngine
public final class AirSpeakerEngine: NSObject, ObservableObject {
    public static let shared = AirSpeakerEngine()

    private let rxEQDSP = Swift5BandBiquadDSP()
    private let txEQDSP = Swift5BandBiquadDSP()

    // MARK: - Equalizer States
    @Published public var playEQ = StudioEQConfig() { didSet { saveEQState(); updateEffectiveEQ() } }
    @Published public var serveEQ = StudioEQConfig() { didSet { saveEQState(); updateEffectiveEQ() } }
    @Published public var partyHostEQ = StudioEQConfig() { didSet { saveEQState(); updateEffectiveEQ() } }
    @Published public var partyJoinEQ = StudioEQConfig() { didSet { saveEQState(); updateEffectiveEQ() } }
    @Published public var universalEQ = StudioEQConfig() { didSet { saveEQState(); updateEffectiveEQ() } }
    @Published public var universalApplyTx: Bool = false { didSet { saveEQState(); updateEffectiveEQ() } }
    @Published public var universalApplyRx: Bool = false { didSet { saveEQState(); updateEffectiveEQ() } }
    @Published public var customEQPresets: [String: [Float]] = [:] { didSet { saveEQState() } }

    public static let builtinEQPresets: [String: [Float]] = [
        "Flat": [0.0, 0.0, 0.0, 0.0, 0.0],
        "Bass Boost": [6.5, 4.0, 0.0, 1.0, 1.5],
        "Vocal Crisp": [-2.0, -1.0, 3.5, 5.0, 2.0],
        "Gaming Footsteps": [-3.0, 1.5, 4.5, 6.0, 3.5],
        "Warm Studio": [3.5, 2.5, -0.5, 1.5, 2.5],
        "Treble Air": [0.0, 0.0, 1.0, 4.0, 7.0]
    ]

    // MARK: - Published UI States
    @Published public var isAudioPlaying: Bool = false
    @Published public var isInternalAudioServing: Bool = false
    @Published public var connectedServerIp: String = "" {
        didSet {
            stateLock.lock()
            atomicConnectedServerIp = connectedServerIp
            stateLock.unlock()
        }
    }
    @Published public var connectedServerName: String = "PC"
    @Published public var latencyMs: Double = 0.0
    @Published public var packetLossPercent: Double = 0.0
    @Published public var packetsLostCount: UInt64 = 0
    @Published public var rxPps: Int = 0
    @Published public var serveTxPps: Int = 0
    @Published public var servePeakLevel: Float = 0.0
    @Published public var underrunCount: Int = 0

    @Published public var volume: Float = 1.0 {
        didSet {
            let clamped = min(2.0, max(0.0, volume))
            stateLock.lock()
            atomicVolume = clamped
            stateLock.unlock()
        }
    }
    @Published public var isMuted: Bool = false {
        didSet {
            stateLock.lock()
            atomicMuted = isMuted
            stateLock.unlock()
            audioControlQueue.async { [weak self] in
                guard let self = self else { return }
                self.playerNode.volume = self.isMuted ? 0.0 : 1.0
            }
        }
    }
    @Published public var bufferTargetMs: Int = 30 {
        didSet {
            stateLock.lock()
            atomicBufferTargetMs = bufferTargetMs
            stateLock.unlock()
        }
    }
    @Published public var bufferMode: String = "AUTO" // AUTO or FIXED
    @Published public var soundMode: String = "BALANCED" // BALANCED, GAMING, QUALITY, CUSTOM

    // Studio Telemetry & PC Synced State
    @Published public var delayHistory: [Double] = Array(repeating: 0.0, count: 60)
    @Published public var activeCodecName: String = "PCM16"
    @Published public var opusBitrateKbps: String = "192"
    @Published public var serverRole: String = "PC_TO_PHONE"
    @Published public var outputEngineMode: String = "AUDIOTRACK_STANDARD"
    @Published public var enableAudioEffects: Bool = false
    @Published public var exclusiveAudioMode: String = "MIX_WITH_OTHERS"
    @Published public var stopOnHeadsetDisconnect: Bool = false
    @Published public var availableIpListText: String = ""
    @Published public var localSyncRev: Int = 1

    // Party Mode States
    @Published public var partyScreen: String = "CHOOSER" // CHOOSER, HOST, JOIN
    @Published public var isPartyHost: Bool = false {
        didSet { syncRelayFlags() }
    }
    @Published public var isPartyHostBroadcasting: Bool = false {
        didSet { syncRelayFlags() }
    }
    @Published public var isPartyJoined: Bool = false
    @Published public var isPartyExtending: Bool = false {
        didSet { syncRelayFlags() }
    }
    @Published public var partyMembers: [PartyMember] = []
    @Published public var partyPeerIps: Set<String> = []
    @Published public var discoveredPartyHosts: [DiscoveredHost] = []
    @Published public var currentPartyHostIp: String = ""
    @Published public var statusMessage: String = "Ready"

    // Slider debouncing and dragging state
    public var isUserDraggingSlider: Bool = false
    private var configSyncDebounceWorkItem: DispatchWorkItem?
    private var eqSaveWorkItem: DispatchWorkItem?
    private let eqSaveQueue = DispatchQueue(label: "com.hasan.airspeaker.eqSaveQueue", qos: .utility)
    private var atomicFrameDurationMs: Double = 5.0
    private var atomicServeTargetIp: String = ""

    // MARK: - Update Notification States
    @Published public var updateAvailableVersion: String = ""
    @Published public var updateDownloadUrl: String = ""
    @Published public var showUpdateBanner: Bool = false
    @Published public var updateStatusMessage: String = ""
    @Published public var updateId: String = ""

    // MARK: - Synchronization & Locks
    private let peersLock = NSLock()
    private let stateLock = NSLock()
    private let atomicUnderruns = AtomicInt()
    private var lastSyncId: Int = Int(Date().timeIntervalSince1970 * 1000) & 0x7FFFFFFF
    private var seenSyncIds: [Int] = []
    private var suppressSync: Bool = false

    // Thread-safe snapshots read by background audio/network threads
    private var atomicVolume: Float = 1.0
    private var atomicMuted: Bool = false
    private var atomicBufferTargetMs: Int = 30
    private var playbackSessionId: Int = 0
    private var atomicRelayActive: Bool = false
    private var atomicConnectedServerIp: String = ""
    private var isPrebufferingAudio: Bool = true

    // MARK: - Audio Engine Components
    private let audioEngine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let audioFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48000, channels: 2, interleaved: false)!
    private let audioBufferQueueCount = AtomicInt()
    private let audioControlQueue = DispatchQueue(label: "com.hasan.airspeaker.audioControlQueue")
    private var receivedOpusPacket: Bool = false
    private var lastStatsCalcTime: Date = Date()

    // BSD POSIX UDP Sockets
    private var audioRxSocket: Int32 = -1
    private var controlSocket: Int32 = -1
    private var serveTxSocket: Int32 = -1
    private var relayTxSocket: Int32 = -1

    // Rx Session State & Socket Safety
    private let rxStateLock = NSLock()
    private var rxSessionId: UInt64 = 0
    private var activeAudioRxFd: Int32 = -1
    private let atomicOverflows = AtomicInt()

    private func isRxSessionActive(_ sessionId: UInt64) -> Bool {
        rxStateLock.lock()
        defer { rxStateLock.unlock() }
        return isRunningAudio && rxSessionId == sessionId
    }

    // Background Threads
    private var rxThread: Thread?
    private var controlThread: Thread?
    private var isRunningAudio: Bool = false
    private var isRunningControl: Bool = false

    // Stats & Pacing (guarded by stateLock)
    private var packetsReceived: UInt64 = 0
    private var packetsLost: UInt64 = 0
    private var lastSequence: UInt32 = 0
    private var lastRxPpsCount: UInt64 = 0
    private var servePacketsSent: UInt64 = 0
    private var lastServePacketsCount: UInt64 = 0

    // Timers
    private var beaconTimer: Timer?
    private var statsTimer: Timer?
    private var partyKeepAliveTimer: Timer?

    // Protocol sync
    private var localRev: Int = 1
    private var lastCodecSyncReqTime: TimeInterval = 0

    // Device Identification
    public var deviceName: String {
        return UIDevice.current.name
    }

    public var localIpAddress: String {
        return AirSpeakerEngine.getPreferredHostIp()
    }

    override private init() {
        super.init()
        setupAudioSession()
        setupAudioEngine()
        refreshAdapterIps()

        // Start Control Channel (Port 5005) & 1-second Beacon Discovery IMMEDIATELY on init
        startControlSocket()
        startBeaconTimer()
        loadEQState()
    }

    deinit {
        stopAll()
    }

    // MARK: - Equalizer Manager Logic
    public func updateEffectiveEQ() {
        let isParty = isPartyJoined
        let modeRX = isParty ? partyJoinEQ : playEQ
        let rxEnabled = modeRX.enabled || (universalEQ.enabled && universalApplyRx)
        var rxBands: [Float] = [0, 0, 0, 0, 0]
        for i in 0..<5 {
            var sum: Float = 0
            if modeRX.enabled && i < modeRX.bands.count { sum += modeRX.bands[i] }
            if universalEQ.enabled && universalApplyRx && i < universalEQ.bands.count { sum += universalEQ.bands[i] }
            rxBands[i] = max(-12.0, min(12.0, sum))
        }
        rxEQDSP.setGains(enabled: rxEnabled, gains: rxBands)

        let isHost = isPartyHost
        let modeTX = isHost ? partyHostEQ : serveEQ
        let txEnabled = modeTX.enabled || (universalEQ.enabled && universalApplyTx)
        var txBands: [Float] = [0, 0, 0, 0, 0]
        for i in 0..<5 {
            var sum: Float = 0
            if modeTX.enabled && i < modeTX.bands.count { sum += modeTX.bands[i] }
            if universalEQ.enabled && universalApplyTx && i < universalEQ.bands.count { sum += universalEQ.bands[i] }
            txBands[i] = max(-12.0, min(12.0, sum))
        }
        txEQDSP.setGains(enabled: txEnabled, gains: txBands)
    }

    public func saveCustomEQPreset(name: String, bands: [Float]) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        customEQPresets[clean] = Array((bands + [0, 0, 0, 0, 0]).prefix(5))
    }

    public func deleteCustomEQPreset(name: String) {
        customEQPresets.removeValue(forKey: name)
    }

    private func saveEQState() {
        eqSaveWorkItem?.cancel()
        let play = self.playEQ
        let serve = self.serveEQ
        let ph = self.partyHostEQ
        let pj = self.partyJoinEQ
        let univ = self.universalEQ
        let tx = self.universalApplyTx
        let rx = self.universalApplyRx
        let presets = self.customEQPresets

        let workItem = DispatchWorkItem {
            let defaults = UserDefaults.standard
            if let playData = try? JSONEncoder().encode(play) { defaults.set(playData, forKey: "ps_playEQ") }
            if let serveData = try? JSONEncoder().encode(serve) { defaults.set(serveData, forKey: "ps_serveEQ") }
            if let phData = try? JSONEncoder().encode(ph) { defaults.set(phData, forKey: "ps_partyHostEQ") }
            if let pjData = try? JSONEncoder().encode(pj) { defaults.set(pjData, forKey: "ps_partyJoinEQ") }
            if let univData = try? JSONEncoder().encode(univ) { defaults.set(univData, forKey: "ps_universalEQ") }
            defaults.set(tx, forKey: "ps_universalApplyTx")
            defaults.set(rx, forKey: "ps_universalApplyRx")
            if let customData = try? JSONEncoder().encode(presets) { defaults.set(customData, forKey: "ps_customEQPresets") }
        }
        eqSaveWorkItem = workItem
        eqSaveQueue.asyncAfter(deadline: .now() + 0.25, execute: workItem)
    }

    private func loadEQState() {
        let defaults = UserDefaults.standard
        if let d = defaults.data(forKey: "ps_playEQ"), let c = try? JSONDecoder().decode(StudioEQConfig.self, from: d) { playEQ = c }
        if let d = defaults.data(forKey: "ps_serveEQ"), let c = try? JSONDecoder().decode(StudioEQConfig.self, from: d) { serveEQ = c }
        if let d = defaults.data(forKey: "ps_partyHostEQ"), let c = try? JSONDecoder().decode(StudioEQConfig.self, from: d) { partyHostEQ = c }
        if let d = defaults.data(forKey: "ps_partyJoinEQ"), let c = try? JSONDecoder().decode(StudioEQConfig.self, from: d) { partyJoinEQ = c }
        if let d = defaults.data(forKey: "ps_universalEQ"), let c = try? JSONDecoder().decode(StudioEQConfig.self, from: d) { universalEQ = c }
        universalApplyTx = defaults.bool(forKey: "ps_universalApplyTx")
        universalApplyRx = defaults.bool(forKey: "ps_universalApplyRx")
        if let d = defaults.data(forKey: "ps_customEQPresets"), let c = try? JSONDecoder().decode([String: [Float]].self, from: d) { customEQPresets = c }
        updateEffectiveEQ()
    }

    private func syncRelayFlags() {
        let active = (isPartyHost && isPartyHostBroadcasting) || isPartyExtending
        stateLock.lock()
        atomicRelayActive = active
        stateLock.unlock()
    }

    public func scheduleConfigSync(delaySeconds: Double = 0.06) {
        configSyncDebounceWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            self?.sendConfigSync()
        }
        configSyncDebounceWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + delaySeconds, execute: workItem)
    }

    public func updateServeTargetIp(_ ip: String) {
        stateLock.lock()
        atomicServeTargetIp = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        atomicConnectedServerIp = atomicServeTargetIp
        stateLock.unlock()
        DispatchQueue.main.async {
            self.connectedServerIp = self.atomicServeTargetIp
        }
    }

    public func clearPartyPeers() {
        peersLock.lock()
        let peersToKick = Array(partyPeerIps)
        partyPeerIps.removeAll()
        peersLock.unlock()
        for p in peersToKick {
            sendControlMessage(["type": "PARTY_KICK", "target_ip": p, "reason": "Host stopped party mode"], toIp: p)
        }
        DispatchQueue.main.async {
            self.partyMembers.removeAll()
        }
    }

    public func applyAudioSessionMode() {
        setupAudioSession()
    }

    private func updateServerIp(_ ip: String) {
        connectedServerIp = ip
        stateLock.lock()
        atomicConnectedServerIp = ip
        stateLock.unlock()
    }

    // MARK: - Audio Session & Engine Setup
    private func setupAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            var options: AVAudioSession.CategoryOptions = [.defaultToSpeaker, .allowBluetooth]
            if exclusiveAudioMode == "MIX_WITH_OTHERS" {
                options.insert(.mixWithOthers)
            }
            try session.setCategory(.playAndRecord, mode: .default, options: options)
            try session.setPreferredSampleRate(48000)
            try session.setPreferredIOBufferDuration(0.005) // 5ms buffer
            try session.setActive(true)
        } catch {
            print("AudioSession setup error: \(error)")
        }
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleRouteChange), name: AVAudioSession.routeChangeNotification, object: nil)
    }

    @objc private func handleRouteChange(notification: Notification) {
        guard stopOnHeadsetDisconnect, isAudioPlaying else { return }
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else { return }

        if reason == .oldDeviceUnavailable {
            DispatchQueue.main.async {
                self.isMuted = true
                self.statusMessage = "Headset disconnected — Paused"
            }
        }
    }

    private func setupAudioEngine() {
        audioControlQueue.sync {
            audioEngine.attach(playerNode)
            audioEngine.connect(playerNode, to: audioEngine.mainMixerNode, format: audioFormat)
            playerNode.volume = isMuted ? 0.0 : 1.0
            do {
                try audioEngine.start()
            } catch {
                print("AVAudioEngine start error: \(error)")
            }
        }
    }

    // MARK: - Start / Stop Speaker Player (UDP 5004)
    public func startSpeakerPlayer(port: Int = 5004, manualHost: String = "") {
        guard !isAudioPlaying else { return }
        setupAudioSession()
        if !manualHost.isEmpty {
            updateServerIp(manualHost)
        }

        stateLock.lock()
        packetsReceived = 0
        packetsLost = 0
        lastSequence = 0
        lastRxPpsCount = 0
        receivedOpusPacket = false
        atomicFrameDurationMs = 5.0
        stateLock.unlock()

        atomicUnderruns.reset()
        atomicOverflows.reset()

        DispatchQueue.main.async {
            self.delayHistory = Array(repeating: 0.0, count: 60)
            self.packetLossPercent = 0.0
            self.packetsLostCount = 0
            self.rxPps = 0
            self.underrunCount = 0
        }

        audioControlQueue.sync {
            self.playbackSessionId &+= 1
            self.audioBufferQueueCount.reset()
            self.isPrebufferingAudio = true
            self.playerNode.stop()
            if !self.audioEngine.isRunning {
                try? self.audioEngine.start()
            }
        }

        rxStateLock.lock()
        isRunningAudio = true
        rxStateLock.unlock()

        guard startAudioRxSocket(port: port) else {
            rxStateLock.lock()
            isRunningAudio = false
            rxStateLock.unlock()
            audioControlQueue.sync {
                self.playerNode.stop()
            }
            DispatchQueue.main.async {
                self.isAudioPlaying = false
                self.statusMessage = "Failed to bind UDP port \(port)"
            }
            return
        }

        startStatsTimer()
        DispatchQueue.main.async {
            self.isAudioPlaying = true
            self.statusMessage = "Speaker active (UDP \(port))"
        }
    }

    public func stopSpeakerPlayer() {
        guard isAudioPlaying else { return }
        rxStateLock.lock()
        rxSessionId &+= 1
        isRunningAudio = false
        let fdToUnblock = activeAudioRxFd
        activeAudioRxFd = -1
        rxStateLock.unlock()

        if fdToUnblock >= 0 {
            Darwin.shutdown(fdToUnblock, SHUT_RDWR)
        }

        stateLock.lock()
        receivedOpusPacket = false
        audioRxSocket = -1
        stateLock.unlock()

        audioControlQueue.sync {
            self.playbackSessionId &+= 1
            self.playerNode.stop()
            if self.audioEngine.isRunning {
                self.audioEngine.pause()
            }
            self.audioBufferQueueCount.reset()
        }

        if !isInternalAudioServing {
            stopStatsTimer()
        }

        DispatchQueue.main.async {
            self.isAudioPlaying = false
            self.statusMessage = "Speaker stopped"
        }
    }

    // MARK: - Exact 12-Byte Little-Endian Audio Receiver (UDP 5004)
    @discardableResult
    private func startAudioRxSocket(port: Int = 5004) -> Bool {
        audioRxSocket = socket(AF_INET, SOCK_DGRAM, 0)
        guard audioRxSocket >= 0 else { return false }

        var opt: Int32 = 1
        setsockopt(audioRxSocket, SOL_SOCKET, SO_REUSEADDR, &opt, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(audioRxSocket, SOL_SOCKET, SO_REUSEPORT, &opt, socklen_t(MemoryLayout<Int32>.size))

        var rcvBuf: Int32 = 1048576 // 1MB buffer
        setsockopt(audioRxSocket, SOL_SOCKET, SO_RCVBUF, &rcvBuf, socklen_t(MemoryLayout<Int32>.size))

        var tv = timeval(tv_sec: 0, tv_usec: 500_000)
        setsockopt(audioRxSocket, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(port).bigEndian
        addr.sin_addr.s_addr = in_addr_t(0) // INADDR_ANY

        let bindResult = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                bind(audioRxSocket, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        if bindResult < 0 {
            print("Failed to bind UDP port \(port)")
            closeSocket(&audioRxSocket)
            return false
        }

        rxStateLock.lock()
        rxSessionId &+= 1
        let sessionId = rxSessionId
        activeAudioRxFd = audioRxSocket
        isRunningAudio = true
        rxStateLock.unlock()

        let sock = audioRxSocket
        rxThread = Thread { [weak self] in
            self?.runAudioRxLoop(fd: sock, sessionId: sessionId)
        }
        rxThread?.qualityOfService = .userInteractive
        rxThread?.start()
        return true
    }

    @inline(__always)
    private static func softKneeLimit(_ x: Float) -> Float {
        if x > 0.85 {
            return 0.85 + 0.15 * tanhf((x - 0.85) / 0.15)
        } else if x < -0.85 {
            return -0.85 + 0.15 * tanhf((x + 0.85) / 0.15)
        }
        return x
    }

    private func runAudioRxLoop(fd: Int32, sessionId: UInt64) {
        defer {
            Darwin.close(fd)
        }
        var buffer = [UInt8](repeating: 0, count: 4096)
        var senderAddr = sockaddr_in()
        var senderLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        var localSeqLast: UInt32 = 0
        var localLastSyncReq: TimeInterval = 0
        var cachedMyIps: [String] = AirSpeakerEngine.getLocalIpv4Addresses()
        var lastIpRefresh: TimeInterval = Date().timeIntervalSince1970

        while isRxSessionActive(sessionId) {
            let bytesRead = withUnsafeMutablePointer(to: &senderAddr) { addrPtr in
                addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    recvfrom(fd, &buffer, buffer.count, 0, sa, &senderLen)
                }
            }

            if bytesRead <= 0 {
                if !isRxSessionActive(sessionId) { break }
                continue
            }

            // Exact 12-byte header requirement
            guard bytesRead > 12 else { continue }

            var senderIpStr = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &senderAddr.sin_addr, &senderIpStr, socklen_t(INET_ADDRSTRLEN))
            let senderIp = String(cString: senderIpStr)

            let nowIpTs = Date().timeIntervalSince1970
            if nowIpTs - lastIpRefresh > 2.0 {
                cachedMyIps = AirSpeakerEngine.getLocalIpv4Addresses()
                lastIpRefresh = nowIpTs
            }
            if senderIp == "127.0.0.1" || cachedMyIps.contains(senderIp) {
                continue
            }

            stateLock.lock()
            let needServerIp = atomicConnectedServerIp.isEmpty
            if needServerIp {
                atomicConnectedServerIp = senderIp
            }
            stateLock.unlock()
            if needServerIp {
                DispatchQueue.main.async { [weak self] in
                    if self?.connectedServerIp.isEmpty == true {
                        self?.connectedServerIp = senderIp
                    }
                }
            }

            // Parse Little-Endian 12-Byte Header:
            // bytes[0..3]: seq (UInt32 LE)
            // bytes[4..7]: timestamp_ms (UInt32 LE)
            // bytes[8]: rawCodec (codec = rawCodec & 0x0F, hopCount = (rawCodec >> 4) & 0x07)
            // bytes[9]: channels (1 or 2)
            // bytes[10..11]: sampleRateDiv100 (UInt16 LE, 480 = 48000 Hz)
            let seq = UInt32(buffer[0]) | (UInt32(buffer[1]) << 8) | (UInt32(buffer[2]) << 16) | (UInt32(buffer[3]) << 24)
            let rawCodec = buffer[8]
            let codec = rawCodec & 0x0F
            let hopCount = (rawCodec >> 4) & 0x07
            let channels = Int(buffer[9] == 1 ? 1 : 2)
            let srDiv100 = UInt16(buffer[10]) | (UInt16(buffer[11]) << 8)

            if (codec != 0 && codec != 1) || srDiv100 != 480 {
                continue
            }

            // Snapshot thread-safe state
            stateLock.lock()
            if seq < 15 && localSeqLast > 30 {
                localSeqLast = seq
            } else if localSeqLast > 0 && seq > localSeqLast + 1 {
                packetsLost += UInt64(seq - localSeqLast - 1)
            }
            localSeqLast = seq
            packetsReceived += 1
            let relayActive = atomicRelayActive
            let curVolume = atomicVolume
            let curMuted = atomicMuted
            let curTargetMs = atomicBufferTargetMs
            stateLock.unlock()

            // Multi-Hop Party Relay (Daisy-Chain Forwarding) with thread-safe snapshot
            if relayActive && hopCount < 4 {
                var relayPkt = Array(buffer[0..<bytesRead])
                let nextHop = hopCount + 1
                relayPkt[8] = (codec & 0x0F) | (((nextHop) & 0x07) << 4)

                peersLock.lock()
                let peersSnapshot = Array(partyPeerIps)
                peersLock.unlock()

                let nowTs = Date().timeIntervalSince1970
                if nowTs - lastIpRefresh > 2.0 {
                    cachedMyIps = AirSpeakerEngine.getLocalIpv4Addresses()
                    lastIpRefresh = nowTs
                }
                for peer in peersSnapshot {
                    if peer != senderIp && !cachedMyIps.contains(peer) {
                        sendAudioPacket(relayPkt, toIp: peer, port: 5004)
                    }
                }
            }

            // Codec Fallback: If sender sends Opus (codec == 1), auto-request Lossless PCM16
            if codec == 1 {
                stateLock.lock()
                self.receivedOpusPacket = true
                stateLock.unlock()
                let now = Date().timeIntervalSince1970
                if now - localLastSyncReq > 2.0 {
                    localLastSyncReq = now
                    DispatchQueue.main.async { [weak self] in
                        self?.sendConfigSync(forcePcm: true, targetIp: senderIp)
                    }
                }
                continue
            }

            // Audio Playout (PCM16): payload begins at byte 12
            let pcmPayloadBytes = bytesRead - 12
            let sampleCount = pcmPayloadBytes / 2
            let frameCount = AVAudioFrameCount(channels == 1 ? sampleCount : (sampleCount / 2))
            guard frameCount > 0 else { continue }

            let frameDurMs = max(1.0, (Double(frameCount) * 1000.0) / 48000.0)
            stateLock.lock()
            atomicFrameDurationMs = frameDurMs
            stateLock.unlock()

            // Dynamic buffer thresholds based on real frame duration (5ms, 10ms, or 20ms)
            let maxQueued = max(8, Int(ceil((Double(curTargetMs) * 2.0) / frameDurMs)))
            if audioBufferQueueCount.value >= maxQueued {
                atomicOverflows.increment()
                continue
            }

            if let pcmBuf = AVAudioPCMBuffer(pcmFormat: audioFormat, frameCapacity: frameCount) {
                pcmBuf.frameLength = frameCount
                let left = pcmBuf.floatChannelData![0]
                let right = pcmBuf.floatChannelData![1]

                let totalInt16Count = channels == 1 ? Int(frameCount) : Int(frameCount) * 2
                var int16Samples = [Int16](repeating: 0, count: totalInt16Count)
                for i in 0..<totalInt16Count {
                    let off = 12 + i * 2
                    let u16 = UInt16(buffer[off]) | (UInt16(buffer[off + 1]) << 8)
                    int16Samples[i] = Int16(bitPattern: u16)
                }
                rxEQDSP.processInt16Samples(&int16Samples, channels: channels)

                let gain: Float = curMuted ? 0.0 : curVolume
                if channels == 1 {
                    for i in 0..<Int(frameCount) {
                        let sample = AirSpeakerEngine.softKneeLimit((Float(int16Samples[i]) / 32767.0) * gain)
                        left[i] = sample
                        right[i] = sample
                    }
                } else {
                    for i in 0..<Int(frameCount) {
                        left[i] = AirSpeakerEngine.softKneeLimit((Float(int16Samples[i * 2]) / 32767.0) * gain)
                        right[i] = AirSpeakerEngine.softKneeLimit((Float(int16Samples[i * 2 + 1]) / 32767.0) * gain)
                    }
                }

                let minPrebuffer = max(2, Int(round(Double(curTargetMs) / frameDurMs)))
                guard isRxSessionActive(sessionId) else { break }
                audioControlQueue.async { [weak self] in
                    guard let self = self, self.isRxSessionActive(sessionId) else { return }

                    let playSession = self.playbackSessionId
                    if self.audioBufferQueueCount.value == 0 && !self.isPrebufferingAudio {
                        self.atomicUnderruns.increment()
                        self.isPrebufferingAudio = true
                        if self.playerNode.isPlaying {
                            self.playerNode.pause()
                        }
                    }
                    self.audioBufferQueueCount.increment()
                    self.playerNode.scheduleBuffer(pcmBuf, at: nil, options: []) { [weak self] in
                        guard let self = self else { return }
                        self.audioControlQueue.async {
                            if self.playbackSessionId == playSession {
                                self.audioBufferQueueCount.decrement()
                            }
                        }
                    }
                    if self.isPrebufferingAudio && self.audioBufferQueueCount.value >= minPrebuffer {
                        self.isPrebufferingAudio = false
                        if !self.audioEngine.isRunning {
                            try? self.audioEngine.start()
                        }
                        if !self.playerNode.isPlaying {
                            self.playerNode.play()
                        }
                    }
                }
            }
        }
    }

    private func sendAudioPacket(_ data: [UInt8], toIp: String, port: Int) {
        if relayTxSocket < 0 {
            relayTxSocket = socket(AF_INET, SOCK_DGRAM, 0)
        }
        guard relayTxSocket >= 0 else { return }

        var dest = sockaddr_in()
        dest.sin_family = sa_family_t(AF_INET)
        dest.sin_port = in_port_t(port).bigEndian
        guard inet_pton(AF_INET, toIp, &dest.sin_addr) == 1 else { return }

        _ = withUnsafePointer(to: &dest) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                sendto(relayTxSocket, data, data.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
    }

    // MARK: - Option A: Internal System Audio Capture & Streamer (UDP 5004)
    public func toggleServingInternalAudio(targetIp: String = "") {
        if isInternalAudioServing {
            stopServingInternalAudio()
        } else {
            startServingInternalAudio(targetIp: targetIp)
        }
    }

    private func extractInterleavedInt16Stereo(from sampleBuffer: CMSampleBuffer) -> [Int16]? {
        guard let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbdPtr = CMAudioFormatDescriptionGetStreamBasicDescription(formatDesc) else {
            return nil
        }
        let asbd = asbdPtr.pointee
        let channels = Int(asbd.mChannelsPerFrame)
        let frameCount = CMSampleBufferGetNumSamples(sampleBuffer)
        guard channels > 0, frameCount > 0 else { return nil }

        let isFloat = (asbd.mFormatFlags & kAudioFormatFlagIsFloat) != 0
        let isNonInterleaved = (asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0
        let isBigEndian = (asbd.mFormatFlags & kAudioFormatFlagIsBigEndian) != 0

        let bufferCount = isNonInterleaved ? channels : 1
        let listSize = MemoryLayout<AudioBufferList>.size + max(0, bufferCount - 1) * MemoryLayout<AudioBuffer>.size
        let rawList = UnsafeMutableRawPointer.allocate(byteCount: listSize, alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { rawList.deallocate() }
        let ablPtr = rawList.bindMemory(to: AudioBufferList.self, capacity: 1)

        var blockBuffer: CMBlockBuffer?
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: ablPtr,
            bufferListSize: listSize,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &blockBuffer
        )
        guard status == noErr else { return nil }

        let buffers = UnsafeMutableAudioBufferListPointer(ablPtr)
        var out = [Int16](repeating: 0, count: frameCount * 2)

        @inline(__always)
        func floatToInt16(_ v: Float) -> Int16 {
            let clamped = max(-1.0, min(1.0, v))
            return Int16(clamped * 32767.0)
        }

        if isNonInterleaved {
            guard let leftData = buffers[0].mData else { return nil }
            let rightData = (buffers.count > 1 ? buffers[1].mData : buffers[0].mData) ?? leftData
            if isFloat {
                let lPtr = leftData.bindMemory(to: Float.self, capacity: frameCount)
                let rPtr = rightData.bindMemory(to: Float.self, capacity: frameCount)
                for i in 0..<frameCount {
                    out[i * 2] = floatToInt16(lPtr[i])
                    out[i * 2 + 1] = floatToInt16(rPtr[i])
                }
            } else {
                let lPtr = leftData.bindMemory(to: Int16.self, capacity: frameCount)
                let rPtr = rightData.bindMemory(to: Int16.self, capacity: frameCount)
                for i in 0..<frameCount {
                    out[i * 2] = isBigEndian ? Int16(bigEndian: lPtr[i]) : lPtr[i]
                    out[i * 2 + 1] = isBigEndian ? Int16(bigEndian: rPtr[i]) : rPtr[i]
                }
            }
        } else {
            guard let data = buffers[0].mData else { return nil }
            if isFloat {
                let ptr = data.bindMemory(to: Float.self, capacity: frameCount * channels)
                for i in 0..<frameCount {
                    let l = ptr[i * channels]
                    let r = (channels > 1) ? ptr[i * channels + 1] : l
                    out[i * 2] = floatToInt16(l)
                    out[i * 2 + 1] = floatToInt16(r)
                }
            } else {
                let ptr = data.bindMemory(to: Int16.self, capacity: frameCount * channels)
                for i in 0..<frameCount {
                    let rawL = ptr[i * channels]
                    let rawR = (channels > 1) ? ptr[i * channels + 1] : rawL
                    out[i * 2] = isBigEndian ? Int16(bigEndian: rawL) : rawL
                    out[i * 2 + 1] = isBigEndian ? Int16(bigEndian: rawR) : rawR
                }
            }
        }
        return out
    }

    public func startServingInternalAudio(targetIp: String = "") {
        guard !isInternalAudioServing else { return }

        stateLock.lock()
        if !targetIp.isEmpty {
            atomicServeTargetIp = targetIp.trimmingCharacters(in: .whitespacesAndNewlines)
            atomicConnectedServerIp = atomicServeTargetIp
        }
        stateLock.unlock()

        let recorder = RPScreenRecorder.shared()
        guard recorder.isAvailable else {
            DispatchQueue.main.async {
                self.statusMessage = "Screen recorder unavailable for internal audio"
            }
            return
        }

        serveTxSocket = socket(AF_INET, SOCK_DGRAM, 0)
        guard serveTxSocket >= 0 else {
            DispatchQueue.main.async {
                self.statusMessage = "Failed to open UDP transmit socket"
            }
            return
        }
        var broadcast: Int32 = 1
        setsockopt(serveTxSocket, SOL_SOCKET, SO_BROADCAST, &broadcast, socklen_t(MemoryLayout<Int32>.size))
        var sndBuf: Int32 = 65536
        setsockopt(serveTxSocket, SOL_SOCKET, SO_SNDBUF, &sndBuf, socklen_t(MemoryLayout<Int32>.size))

        var seq: UInt32 = 1
        var sampleAccumulator: [Int16] = []
        let serveLock = NSLock()

        recorder.startCapture(handler: { [weak self] (sampleBuffer, bufferType, error) in
            guard let self = self, error == nil, bufferType == .audioApp, self.serveTxSocket >= 0 else { return }
            guard var decodedStereo = self.extractInterleavedInt16Stereo(from: sampleBuffer) else { return }

            var inSampleRate: Double = 48000.0
            if let fmtDesc = CMSampleBufferGetFormatDescription(sampleBuffer),
               let asbdPtr = CMAudioFormatDescriptionGetStreamBasicDescription(fmtDesc) {
                let asbd = asbdPtr.pointee
                if asbd.mSampleRate > 8000.0 {
                    inSampleRate = asbd.mSampleRate
                }
            }

            // Resample to 48 kHz stereo if iOS ReplayKit delivers 44.1 kHz
            if abs(inSampleRate - 48000.0) > 100.0 && decodedStereo.count >= 4 {
                let inFrames = decodedStereo.count / 2
                let outFrames = max(1, Int(round(Double(inFrames) * 48000.0 / inSampleRate)))
                var resampled = [Int16](repeating: 0, count: outFrames * 2)
                let ratio = Double(inFrames - 1) / Double(max(1, outFrames - 1))
                for f in 0..<outFrames {
                    let srcPos = Double(f) * ratio
                    let idx0 = min(inFrames - 1, Int(srcPos))
                    let idx1 = min(inFrames - 1, idx0 + 1)
                    let frac = Float(srcPos - Double(idx0))
                    let l = Float(decodedStereo[idx0 * 2]) * (1.0 - frac) + Float(decodedStereo[idx1 * 2]) * frac
                    let r = Float(decodedStereo[idx0 * 2 + 1]) * (1.0 - frac) + Float(decodedStereo[idx1 * 2 + 1]) * frac
                    resampled[f * 2] = Int16(max(-32768.0, min(32767.0, l)))
                    resampled[f * 2 + 1] = Int16(max(-32768.0, min(32767.0, r)))
                }
                decodedStereo = resampled
            }

            var maxSample: Int32 = 0
            serveLock.lock()
            sampleAccumulator.append(contentsOf: decodedStereo)

            while sampleAccumulator.count >= 480 {
                var slice = Array(sampleAccumulator.prefix(480))
                sampleAccumulator.removeFirst(480)

                self.txEQDSP.processInt16Samples(&slice, channels: 2)

                var packet = [UInt8](repeating: 0, count: 972)
                packet[0] = UInt8(seq & 0xFF)
                packet[1] = UInt8((seq >> 8) & 0xFF)
                packet[2] = UInt8((seq >> 16) & 0xFF)
                packet[3] = UInt8((seq >> 24) & 0xFF)

                let ts = UInt32(ProcessInfo.processInfo.systemUptime * 1000) & 0xFFFFFFFF
                packet[4] = UInt8(ts & 0xFF)
                packet[5] = UInt8((ts >> 8) & 0xFF)
                packet[6] = UInt8((ts >> 16) & 0xFF)
                packet[7] = UInt8((ts >> 24) & 0xFF)

                packet[8] = 0 // PCM16
                packet[9] = 2 // Stereo
                packet[10] = UInt8(480 & 0xFF)
                packet[11] = UInt8((480 >> 8) & 0xFF)

                for j in 0..<480 {
                    let s = slice[j]
                    let a = abs(Int32(s))
                    if a > maxSample { maxSample = a }
                    let u = UInt16(bitPattern: s)
                    packet[12 + j * 2] = UInt8(u & 0xFF)
                    packet[12 + j * 2 + 1] = UInt8((u >> 8) & 0xFF)
                }

                seq &+= 1
                self.stateLock.lock()
                self.servePacketsSent += 1
                self.stateLock.unlock()

                let destIp: String
                self.stateLock.lock()
                let targetServe = self.atomicServeTargetIp
                let curIp = self.atomicConnectedServerIp
                self.stateLock.unlock()
                if !targetServe.isEmpty {
                    destIp = targetServe
                } else if !targetIp.isEmpty {
                    destIp = targetIp
                } else {
                    destIp = curIp.isEmpty ? "255.255.255.255" : curIp
                }
                self.sendServePacket(packet, toIp: destIp)
            }
            serveLock.unlock()

            let peak = min(1.0, Float(maxSample) / 32768.0)
            DispatchQueue.main.async {
                self.servePeakLevel = peak
            }
        }) { [weak self] error in
            DispatchQueue.main.async {
                if let error = error {
                    self?.statusMessage = "Capture error: \(error.localizedDescription)"
                    self?.isInternalAudioServing = false
                    if var sock = self?.serveTxSocket {
                        self?.closeSocket(&sock)
                        self?.serveTxSocket = -1
                    }
                } else {
                    self?.isInternalAudioServing = true
                    self?.statusMessage = "Serving Internal Audio (Port 5004)"
                    self?.startStatsTimer()
                }
            }
        }
    }

    private func sendServePacket(_ packet: [UInt8], toIp: String) {
        guard serveTxSocket >= 0 else { return }
        var destAddr = sockaddr_in()
        destAddr.sin_family = sa_family_t(AF_INET)
        destAddr.sin_port = in_port_t(5004).bigEndian
        if inet_pton(AF_INET, toIp, &destAddr.sin_addr) == 1 {
            withUnsafePointer(to: &destAddr) { addrPtr in
                _ = addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    sendto(self.serveTxSocket, packet, 972, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }

        if toIp == "255.255.255.255" {
            let parts = self.localIpAddress.split(separator: ".")
            if parts.count == 4 {
                let subnetBcast = "\(parts[0]).\(parts[1]).\(parts[2]).255"
                var bcastAddr = sockaddr_in()
                bcastAddr.sin_family = sa_family_t(AF_INET)
                bcastAddr.sin_port = in_port_t(5004).bigEndian
                inet_pton(AF_INET, subnetBcast, &bcastAddr.sin_addr)
                withUnsafePointer(to: &bcastAddr) { addrPtr in
                    _ = addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        sendto(self.serveTxSocket, packet, 972, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        }

        if atomicRelayActive {
            self.peersLock.lock()
            let peers = Array(self.partyPeerIps)
            self.peersLock.unlock()
            for peer in peers where peer != toIp {
                var peerAddr = sockaddr_in()
                peerAddr.sin_family = sa_family_t(AF_INET)
                peerAddr.sin_port = in_port_t(5004).bigEndian
                inet_pton(AF_INET, peer, &peerAddr.sin_addr)
                withUnsafePointer(to: &peerAddr) { addrPtr in
                    _ = addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        sendto(self.serveTxSocket, packet, 972, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        }
    }

    public func stopServingInternalAudio() {
        guard isInternalAudioServing else { return }
        RPScreenRecorder.shared().stopCapture { [weak self] error in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.isInternalAudioServing = false
                self.statusMessage = "Internal Audio Sender Stopped"
                self.servePeakLevel = 0.0
                self.serveTxPps = 0
                self.closeSocket(&self.serveTxSocket)
                if !self.isAudioPlaying {
                    self.stopStatsTimer()
                }
            }
        }
    }

    // MARK: - Exact Port 5005 Control Protocol
    private func startControlSocket() {
        controlSocket = socket(AF_INET, SOCK_DGRAM, 0)
        guard controlSocket >= 0 else { return }

        var opt: Int32 = 1
        setsockopt(controlSocket, SOL_SOCKET, SO_REUSEADDR, &opt, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(controlSocket, SOL_SOCKET, SO_REUSEPORT, &opt, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(controlSocket, SOL_SOCKET, SO_BROADCAST, &opt, socklen_t(MemoryLayout<Int32>.size))

        var tv = timeval(tv_sec: 0, tv_usec: 500_000)
        setsockopt(controlSocket, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(5005).bigEndian
        addr.sin_addr.s_addr = in_addr_t(0)

        let bindRes = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                bind(controlSocket, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        if bindRes < 0 {
            print("Failed to bind UDP port 5005")
            closeSocket(&controlSocket)
            return
        }

        isRunningControl = true
        controlThread = Thread { [weak self] in
            self?.runControlRxLoop()
        }
        controlThread?.qualityOfService = .utility
        controlThread?.start()
    }

    private func runControlRxLoop() {
        var buffer = [UInt8](repeating: 0, count: 4096)
        var senderAddr = sockaddr_in()
        var senderLen = socklen_t(MemoryLayout<sockaddr_in>.size)

        while isRunningControl && controlSocket >= 0 {
            let bytesRead = withUnsafeMutablePointer(to: &senderAddr) { addrPtr in
                addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    recvfrom(controlSocket, &buffer, buffer.count, 0, sa, &senderLen)
                }
            }

            guard bytesRead > 0 else { continue }

            var senderIpStr = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &senderAddr.sin_addr, &senderIpStr, socklen_t(INET_ADDRSTRLEN))
            let senderIp = String(cString: senderIpStr)

            let myIps = AirSpeakerEngine.getLocalIpv4Addresses()
            if myIps.contains(senderIp) { continue }

            let data = Data(buffer[0..<bytesRead])
            guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let type = json["type"] as? String else { continue }

            DispatchQueue.main.async { [weak self] in
                self?.handleControlMessage(type: type, payload: json, senderIp: senderIp)
            }
        }
    }

    private func handleControlMessage(type: String, payload: [String: Any], senderIp: String) {
        switch type {
        case "PC_ANNOUNCE":
            updateServerIp(senderIp)
            if let pcName = payload["pc_name"] as? String {
                connectedServerName = pcName
            }
            let isHost = payload["is_party_host"] as? Bool ?? (payload["party_mode"] as? Bool ?? false)
            if isHost {
                updateDiscoveredPartyHost(name: connectedServerName, ip: senderIp, port: 5004, isExtended: false)
            } else {
                discoveredPartyHosts.removeAll { $0.ip == senderIp }
            }

        case "BEACON":
            let dev = payload["device"] as? String ?? (payload["device_name"] as? String ?? "Device")
            let isHost = payload["is_party_host"] as? Bool ?? false
            let isExt = payload["is_extended_host"] as? Bool ?? false
            let isPartyJoin = payload["party_join"] as? Bool

            if isHost || isExt {
                updateDiscoveredPartyHost(name: dev, ip: senderIp, port: 5004, isExtended: isExt)
            } else if payload["is_party_host"] != nil || payload["is_extended_host"] != nil {
                discoveredPartyHosts.removeAll { $0.ip == senderIp }
            }

            // Downstream peer discovery
            if (isPartyHost || isPartyExtending) {
                let isPartyLeave = payload["party_leave"] as? Bool ?? false
                if isPartyJoin == true && !isHost {
                    peersLock.lock()
                    partyPeerIps.insert(senderIp)
                    peersLock.unlock()
                    updatePartyMember(name: dev, ip: senderIp)
                } else if isPartyLeave {
                    peersLock.lock()
                    partyPeerIps.remove(senderIp)
                    peersLock.unlock()
                    partyMembers.removeAll { $0.ip == senderIp }
                }
            }

        case "CONFIG_SYNC":
            let inSyncId = (payload["sync_id"] as? NSNumber)?.intValue
            let inRev = (payload["rev"] as? NSNumber)?.intValue ?? 0
            if let sid = inSyncId {
                if seenSyncIds.contains(sid) { return }
                if inRev < localRev { return }
                if seenSyncIds.count >= 32 { seenSyncIds.removeFirst() }
                seenSyncIds.append(sid)
                lastSyncId = sid
            } else if inRev <= localRev {
                return
            }
            localRev = max(localRev, inRev) + 1
            localSyncRev = localRev
            suppressSync = true
            defer { suppressSync = false }

            if !isUserDraggingSlider {
                if let vol = (payload["volume_gain"] as? NSNumber)?.floatValue {
                    volume = min(2.0, max(0.0, vol))
                }
                if let mute = payload["muted"] as? Bool {
                    isMuted = mute
                }
                if let profile = payload["audio_profile"] as? String {
                    soundMode = profile
                }
                if let bMode = payload["buffer_mode"] as? String {
                    bufferMode = bMode
                }
                if let targetMs = (payload["custom_buffer_ms"] as? NSNumber)?.intValue {
                    bufferTargetMs = min(200, max(10, targetMs))
                }
            }
            if let role = payload["direction"] as? String {
                serverRole = role
            }
            if let bRate = (payload["opus_bitrate_kbps"] as? NSNumber)?.intValue {
                opusBitrateKbps = String(bRate)
            }
            if let outEng = payload["audio_output_engine"] as? String {
                outputEngineMode = outEng
            }
            if let fx = payload["enable_audio_effects"] as? Bool {
                enableAudioEffects = fx
            }
            if let excl = payload["exclusive_audio_mode"] as? String {
                exclusiveAudioMode = excl
            }
            if let disc = payload["stop_on_headset_disconnect"] as? Bool {
                stopOnHeadsetDisconnect = disc
            }
            if let pcStreaming = payload["pc_streaming"] as? Bool, pcStreaming && !isAudioPlaying {
                statusMessage = "PC is streaming — Tap Start Speaker Player"
            }

        case "PARTY_KICK":
            let targetIp = payload["target_ip"] as? String ?? ""
            if targetIp.isEmpty || AirSpeakerEngine.getLocalIpv4Addresses().contains(targetIp) {
                leaveParty()
            }

        default:
            break
        }
    }

    public func sendConfigSync(forcePcm: Bool = false, targetIp: String? = nil) {
        if forcePcm {
            localRev += 10
        } else {
            guard !suppressSync else { return }
            localRev += 1
        }
        localSyncRev = localRev
        let syncId = Int(Date().timeIntervalSince1970 * 1000) & 0x7FFFFFFF
        lastSyncId = syncId
        let cfg: [String: Any] = [
            "type": "CONFIG_SYNC",
            "rev": localRev,
            "sync_id": syncId,
            "direction": isInternalAudioServing && isAudioPlaying ? "FULL_DUPLEX" : (isInternalAudioServing ? "PHONE_TO_PC" : "PC_TO_PHONE"),
            "audio_profile": soundMode,
            "codec": 0,
            "force_pcm": true,
            "platform": "ios",
            "opus_bitrate_kbps": Int(opusBitrateKbps) ?? 192,
            "frame_ms": 5,
            "buffer_mode": bufferMode,
            "custom_buffer_ms": Double(bufferTargetMs),
            "auto_buffer": (bufferMode == "AUTO"),
            "volume_gain": Double(volume),
            "muted": isMuted,
            "device": deviceName,
            "party_mode": isPartyHost || isPartyExtending,
            "audio_output_engine": outputEngineMode,
            "enable_audio_effects": enableAudioEffects,
            "exclusive_audio_mode": exclusiveAudioMode,
            "stop_on_headset_disconnect": stopOnHeadsetDisconnect
        ]
        let dest = targetIp ?? connectedServerIp
        if !dest.isEmpty && dest != "255.255.255.255" {
            sendControlMessage(cfg, toIp: dest)
        } else {
            broadcastControlMessage(cfg)
        }
    }

    private func updateDiscoveredPartyHost(name: String, ip: String, port: Int, isExtended: Bool) {
        if let idx = discoveredPartyHosts.firstIndex(where: { $0.ip == ip }) {
            discoveredPartyHosts[idx].lastSeen = Date()
            discoveredPartyHosts[idx].isExtended = isExtended
        } else {
            discoveredPartyHosts.append(DiscoveredHost(name: name, ip: ip, port: port, isExtended: isExtended))
        }
    }

    private func updatePartyMember(name: String, ip: String, isManual: Bool = false) {
        if let idx = partyMembers.firstIndex(where: { $0.ip == ip }) {
            partyMembers[idx].lastSeen = Date()
            if isManual {
                partyMembers[idx].isManual = true
            }
        } else {
            partyMembers.append(PartyMember(name: name, ip: ip, isManual: isManual))
        }
    }

    public func addManualPeer(ip: String) {
        let trimmed = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        peersLock.lock()
        partyPeerIps.insert(trimmed)
        peersLock.unlock()
        updatePartyMember(name: "Peer (\(trimmed))", ip: trimmed, isManual: true)
    }

    public func kickMember(ip: String) {
        peersLock.lock()
        partyPeerIps.remove(ip)
        peersLock.unlock()
        partyMembers.removeAll { $0.ip == ip }
        sendControlMessage(["type": "PARTY_KICK", "target_ip": ip], toIp: ip)
    }

    // MARK: - Party Mode Lifecycle & QR Code URIs
    public func hostParty() {
        partyScreen = "HOST"
        isPartyHost = false
        isPartyHostBroadcasting = false
        isPartyJoined = false
        isPartyExtending = false
        updateEffectiveEQ()
        partyMembers.removeAll()
        peersLock.lock()
        partyPeerIps.removeAll()
        peersLock.unlock()
    }

    public func startPartyHostBroadcast() {
        isPartyHost = true
        isPartyHostBroadcasting = true
        syncRelayFlags()
        startSpeakerPlayer()
        broadcastBeacon()
    }

    public func stopPartyHostBroadcast() {
        isPartyHost = false
        isPartyHostBroadcasting = false
        syncRelayFlags()
        sendPartyHostStopAnnouncement()
        stopSpeakerPlayer()
    }

    public func sendPartyHostStopAnnouncement() {
        let stopBeacon: [String: Any] = [
            "type": "BEACON",
            "app": "AirSpeaker",
            "version": 1,
            "ips": AirSpeakerEngine.getLocalIpv4Addresses(),
            "audio_port": 5004,
            "device": self.deviceName,
            "party_join": false,
            "party_leave": true,
            "is_party_host": false,
            "is_extended_host": false,
            "serving": self.isInternalAudioServing,
            "playing": false,
            "platform": "ios"
        ]
        broadcastControlMessage(stopBeacon)
    }

    public func joinParty(hostIp: String, port: Int = 5004) {
        let (ip, p) = AirSpeakerEngine.parseHostIpFromUri(hostIp)
        let actualIp = ip.isEmpty ? hostIp : ip
        let actualPort = p > 0 ? p : port

        partyScreen = "JOIN"
        isPartyHost = false
        isPartyHostBroadcasting = false
        isPartyJoined = true
        currentPartyHostIp = actualIp
        updateServerIp(actualIp)
        updateEffectiveEQ()

        startSpeakerPlayer(port: actualPort, manualHost: actualIp)
        sendPartyJoinAnnouncement(hostIp: actualIp)
        startPartyKeepAliveTimer()
    }

    public func toggleExtendParty() {
        isPartyExtending.toggle()
        if isPartyExtending {
            if relayTxSocket < 0 {
                relayTxSocket = socket(AF_INET, SOCK_DGRAM, 0)
                var broadcast: Int32 = 1
                setsockopt(relayTxSocket, SOL_SOCKET, SO_BROADCAST, &broadcast, socklen_t(MemoryLayout<Int32>.size))
            }
            statusMessage = "Extending Party (Daisy-chain repeater active)"
        } else {
            closeSocket(&relayTxSocket)
            statusMessage = "Party extension stopped"
        }
        broadcastBeacon()
    }

    public func leaveParty() {
        if isPartyHostBroadcasting {
            stopPartyHostBroadcast()
        }
        if isPartyJoined && !currentPartyHostIp.isEmpty {
            sendControlMessage([
                "type": "BEACON",
                "app": "AirSpeaker",
                "version": 1,
                "device": deviceName,
                "party_join": false,
                "party_leave": true,
                "is_party_host": false,
                "is_extended_host": false,
                "audio_port": 5004
            ], toIp: currentPartyHostIp)
        }
        broadcastBeacon(explicitLeave: true)
        stopPartyKeepAliveTimer()
        stopSpeakerPlayer()
        closeSocket(&relayTxSocket)
        partyScreen = "CHOOSER"
        isPartyHost = false
        isPartyHostBroadcasting = false
        isPartyJoined = false
        isPartyExtending = false
        currentPartyHostIp = ""
        updateEffectiveEQ()
        partyMembers.removeAll()
        peersLock.lock()
        partyPeerIps.removeAll()
        peersLock.unlock()
    }

    // MARK: - QR URI Generator & Parser
    public func makePartyQrUri(isExtended: Bool) -> String {
        let encodedName = deviceName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "Phone"
        return "airspeaker://join?host=\(localIpAddress)&port=5004&name=\(encodedName)"
    }

    public static func parseHostIpFromUri(_ raw: String) -> (String, Int) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)

        // 1. phonespeaker://join?host=... or http://...
        if trimmed.hasPrefix("airspeaker://") || trimmed.hasPrefix("phonespeaker://") || trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://") {
            if let components = URLComponents(string: trimmed) {
                let hostParam = components.queryItems?.first(where: { $0.name == "host" })?.value
                let portParam = components.queryItems?.first(where: { $0.name == "port" })?.value
                let host = hostParam ?? components.host ?? ""
                let port = Int(portParam ?? "") ?? (components.port ?? 5004)
                if !host.isEmpty {
                    return (host, port)
                }
            }
        }

        // 2. PHONESPEAKER_PARTY:<ip>[:port]
        if trimmed.hasPrefix("AIRSPEAKER_PARTY:") || trimmed.hasPrefix("PHONESPEAKER_PARTY:") {
            let prefixLen = trimmed.hasPrefix("AIRSPEAKER_PARTY:") ? "AIRSPEAKER_PARTY:".count : "PHONESPEAKER_PARTY:".count
            let after = String(trimmed.dropFirst(prefixLen))
            let parts = after.split(separator: ":")
            let host = String(parts[0])
            let port = parts.count > 1 ? (Int(parts[1]) ?? 5004) : 5004
            return (host, port)
        }

        // 3. Raw host:port or host
        let parts = trimmed.split(separator: ":")
        if !parts.isEmpty {
            let host = String(parts[0])
            let port = parts.count > 1 ? (Int(parts[1]) ?? 5004) : 5004
            return (host, port)
        }

        return (trimmed, 5004)
    }

    // MARK: - Periodic Beacons & Stats
    private func startBeaconTimer() {
        DispatchQueue.main.async {
            self.beaconTimer?.invalidate()
            self.beaconTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                guard let self = self else { return }
                self.broadcastBeacon()
                let now = Date()
                self.discoveredPartyHosts.removeAll { now.timeIntervalSince($0.lastSeen) > 6.0 }
                self.partyMembers.removeAll { member in
                    if !member.isManual && now.timeIntervalSince(member.lastSeen) > 10.0 {
                        self.peersLock.lock()
                        self.partyPeerIps.remove(member.ip)
                        self.peersLock.unlock()
                        return true
                    }
                    return false
                }
            }
        }
    }

    public func broadcastBeacon(explicitLeave: Bool = false) {
        let isHostActive = self.isPartyHost && self.isPartyHostBroadcasting && !self.isPartyExtending
        let isExtActive = self.isPartyExtending && self.isPartyJoined
        var beacon: [String: Any] = [
            "type": "BEACON",
            "app": "AirSpeaker",
            "version": 1,
            "ips": AirSpeakerEngine.getLocalIpv4Addresses(),
            "audio_port": 5004,
            "device": self.deviceName,
            "party_join": self.isPartyJoined && !explicitLeave,
            "is_party_host": isHostActive,
            "is_extended_host": isExtActive,
            "serving": self.isInternalAudioServing,
            "playing": self.isAudioPlaying,
            "platform": "ios",
            "force_pcm": self.isAudioPlaying && self.receivedOpusPacket
        ]
        if explicitLeave {
            beacon["party_leave"] = true
        }
        broadcastControlMessage(beacon)
    }

    private func sendPartyJoinAnnouncement(hostIp: String) {
        let joinBeacon: [String: Any] = [
            "type": "BEACON",
            "app": "AirSpeaker",
            "version": 1,
            "device": self.deviceName,
            "party_join": true,
            "is_party_host": false,
            "is_extended_host": self.isPartyExtending,
            "audio_port": 5004
        ]
        sendControlMessage(joinBeacon, toIp: hostIp)
    }

    private func startPartyKeepAliveTimer() {
        DispatchQueue.main.async {
            self.partyKeepAliveTimer?.invalidate()
            self.partyKeepAliveTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: true) { [weak self] _ in
                guard let self = self, self.isPartyJoined, !self.currentPartyHostIp.isEmpty else { return }
                self.sendPartyJoinAnnouncement(hostIp: self.currentPartyHostIp)
            }
        }
    }

    private func stopPartyKeepAliveTimer() {
        DispatchQueue.main.async {
            self.partyKeepAliveTimer?.invalidate()
            self.partyKeepAliveTimer = nil
        }
    }

    private func startStatsTimer() {
        DispatchQueue.main.async {
            self.statsTimer?.invalidate()
            self.statsTimer = Timer.scheduledTimer(withTimeInterval: 0.3, repeats: true) { [weak self] _ in
                guard let self = self else { return }
                self.stateLock.lock()
                let curRx = self.packetsReceived
                let curLost = self.packetsLost
                let curServeTx = self.servePacketsSent
                self.stateLock.unlock()

                let total = curRx + curLost
                self.packetLossPercent = total > 0 ? (Double(curLost) / Double(total)) * 100.0 : 0.0
                self.stateLock.lock()
                let curFrameDurMs = self.atomicFrameDurationMs
                self.stateLock.unlock()
                let queueBufMs = Double(self.audioBufferQueueCount.value) * curFrameDurMs
                let curBufMs: Double
                if queueBufMs > 0 {
                    curBufMs = queueBufMs
                } else if self.isInternalAudioServing && self.serveTxPps > 0 {
                    curBufMs = Double(self.bufferTargetMs) + Double(self.servePeakLevel * 5.0)
                } else {
                    curBufMs = 0.0
                }
                self.latencyMs = curBufMs

                self.delayHistory.append(curBufMs)
                if self.delayHistory.count > 60 {
                    self.delayHistory.removeFirst(self.delayHistory.count - 60)
                }

                let now = Date()
                let dt = max(0.001, now.timeIntervalSince(self.lastStatsCalcTime))
                self.lastStatsCalcTime = now
                let rxDelta = curRx >= self.lastRxPpsCount ? Double(curRx - self.lastRxPpsCount) : 0.0
                self.rxPps = Int(round(rxDelta / dt))
                self.lastRxPpsCount = curRx

                let serveDelta = curServeTx >= self.lastServePacketsCount ? Double(curServeTx - self.lastServePacketsCount) : 0.0
                self.serveTxPps = Int(round(serveDelta / dt))
                self.lastServePacketsCount = curServeTx

                self.packetsLostCount = curLost

                // Sync atomic underruns to published property
                self.underrunCount = self.atomicUnderruns.value

                if !self.connectedServerIp.isEmpty {
                    let stats: [String: Any] = [
                        "type": "STATS",
                        "buffer_ms": curBufMs,
                        "target_ms": Double(self.bufferTargetMs),
                        "underruns": self.underrunCount,
                        "lost_packets": curLost,
                        "packet_loss_pct": self.packetLossPercent,
                        "late_packets": 0,
                        "auto_mode": (self.bufferMode == "AUTO"),
                        "rx_pps": self.rxPps,
                        "active_codec": 0,
                        "codec": "PCM16",
                        "device": self.deviceName,
                        "serving": self.isInternalAudioServing,
                        "playing": self.isAudioPlaying,
                        "serve_pps": self.serveTxPps,
                        "platform": "ios",
                        "force_pcm": true
                    ]
                    self.sendControlMessage(stats, toIp: self.connectedServerIp)
                }
            }
        }
    }

    private func stopStatsTimer() {
        DispatchQueue.main.async {
            self.statsTimer?.invalidate()
            self.statsTimer = nil
        }
    }

    // MARK: - Control Message Sending
    public func broadcastControlMessage(_ dict: [String: Any]) {
        sendControlMessage(dict, toIp: "255.255.255.255")

        // Unicast to known connected Server PC and party host
        if !connectedServerIp.isEmpty {
            sendControlMessage(dict, toIp: connectedServerIp)
        }
        if !currentPartyHostIp.isEmpty {
            sendControlMessage(dict, toIp: currentPartyHostIp)
        }

        // Also broadcast to local subnet and gateway
        let localIp = localIpAddress
        let parts = localIp.split(separator: ".")
        if parts.count == 4 {
            let subnetBcast = "\(parts[0]).\(parts[1]).\(parts[2]).255"
            sendControlMessage(dict, toIp: subnetBcast)
            let gatewayIp = "\(parts[0]).\(parts[1]).\(parts[2]).1"
            sendControlMessage(dict, toIp: gatewayIp)
        }
    }

    public func sendControlMessage(_ dict: [String: Any], toIp: String) {
        guard let data = try? JSONSerialization.data(withJSONObject: dict), controlSocket >= 0 else { return }

        var dest = sockaddr_in()
        dest.sin_family = sa_family_t(AF_INET)
        dest.sin_port = in_port_t(5005).bigEndian
        guard inet_pton(AF_INET, toIp, &dest.sin_addr) == 1 else { return }

        data.withUnsafeBytes { ptr in
            withUnsafePointer(to: &dest) { addrPtr in
                _ = addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    sendto(self.controlSocket, ptr.baseAddress, ptr.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    private func closeSocket(_ sock: inout Int32) {
        if sock >= 0 {
            shutdown(sock, SHUT_RDWR)
            close(sock)
            sock = -1
        }
    }

    public func stopAll() {
        stopSpeakerPlayer()
        stopServingInternalAudio()
        stopPartyKeepAliveTimer()
        beaconTimer?.invalidate()
        beaconTimer = nil
        closeSocket(&relayTxSocket)
        isRunningControl = false
        closeSocket(&controlSocket)
    }

    // MARK: - Adapter Enumeration
    public func refreshAdapterIps() {
        var lines: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else {
            availableIpListText = "en0        : 127.0.0.1"
            return
        }

        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            if interface.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                if name.hasPrefix("lo") { continue }
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                            &hostname, socklen_t(hostname.count),
                            nil, socklen_t(0), NI_NUMERICHOST)
                let ip = String(cString: hostname)
                if ip != "127.0.0.1" {
                    let paddedName = name.padding(toLength: 10, withPad: " ", startingAt: 0)
                    lines.append("\(paddedName): \(ip)")
                }
            }
        }
        freeifaddrs(ifaddr)
        if lines.isEmpty {
            lines.append("en0        : 127.0.0.1")
        }
        availableIpListText = lines.joined(separator: "\n")
    }

    // MARK: - Hotspot-First Local IPv4 Address Discovery
    public static func getLocalIpv4Addresses() -> [String] {
        var ips: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return ["127.0.0.1"] }

        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            if interface.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                if name.hasPrefix("lo") || name.hasPrefix("pdp_ip") || name.hasPrefix("utun") || name.hasPrefix("llw") || name.hasPrefix("awdl") {
                    continue
                }
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                            &hostname, socklen_t(hostname.count),
                            nil, socklen_t(0), NI_NUMERICHOST)
                let ip = String(cString: hostname)
                if !ips.contains(ip) && ip != "127.0.0.1" {
                    ips.append(ip)
                }
            }
        }
        freeifaddrs(ifaddr)
        return ips.isEmpty ? ["127.0.0.1"] : ips
    }

    public static func getPreferredHostIp() -> String {
        let allIps = getLocalIpv4Addresses()
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return allIps.first ?? "127.0.0.1" }

        var bridgeIp: String?
        var wifiIp: String?

        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            if interface.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                            &hostname, socklen_t(hostname.count),
                            nil, socklen_t(0), NI_NUMERICHOST)
                let ip = String(cString: hostname)
                if ip == "127.0.0.1" { continue }

                // Check iPhone Personal Hotspot interfaces (bridge100 / ap1) first!
                if name.hasPrefix("bridge") || name.hasPrefix("ap") {
                    bridgeIp = ip
                } else if name.hasPrefix("en0") || name.hasPrefix("en1") {
                    if wifiIp == nil { wifiIp = ip }
                }
            }
        }
        freeifaddrs(ifaddr)
        return bridgeIp ?? wifiIp ?? allIps.first ?? "127.0.0.1"
    }

    // MARK: - Automatic GitHub Update Checker
    public func dismissUpdateBanner(skip: Bool = false) {
        if skip && !updateId.isEmpty {
            UserDefaults.standard.set(updateId, forKey: "skipped_update_id")
        }
        showUpdateBanner = false
    }

    public func checkForUpdates(manual: Bool = false) {
        if manual {
            updateStatusMessage = "Checking for updates..."
        }
        guard let url = URL(string: "https://api.github.com/repos/HSAdib/AirSpeaker/releases/latest") else { return }
        var request = URLRequest(url: url)
        request.timeoutInterval = 5.0
        request.setValue("AirSpeaker-iOS/1.0.0", forHTTPHeaderField: "User-Agent")
        request.setValue("application/vnd.github.v3+json", forHTTPHeaderField: "Accept")

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            guard let data = data, error == nil,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let tagName = json["tag_name"] as? String else {
                if manual {
                    DispatchQueue.main.async {
                        self.updateStatusMessage = "Could not check for updates (offline or rate limited)."
                    }
                }
                return
            }

            let body = json["body"] as? String ?? ""
            let htmlUrl = json["html_url"] as? String ?? "https://github.com/HSAdib/AirSpeaker/releases/latest"

            // Extract remote build sha: <!-- build: a1b2c3d -->
            var remoteSha: String? = nil
            if let regex = try? NSRegularExpression(pattern: "<!--\\s*build:\\s*([a-fA-F0-9]+)\\s*-->", options: .caseInsensitive),
               let match = regex.firstMatch(in: body, options: [], range: NSRange(location: 0, length: body.utf16.count)),
               let range = Range(match.range(at: 1), in: body) {
                remoteSha = String(body[range]).trimmingCharacters(in: .whitespacesAndNewlines)
            }

            let localBuildSha = (Bundle.main.infoDictionary?["AirSpeakerBuildSHA"] as? String ?? "dev").trimmingCharacters(in: .whitespacesAndNewlines)
            let localVersion = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "1.0.0"

            func parseSemver(_ v: String) -> (Int, Int, Int) {
                let cleaned = v.trimmingCharacters(in: CharacterSet(charactersIn: "vV \t\r\n"))
                let parts = cleaned.components(separatedBy: ".")
                let n0 = parts.count > 0 ? Int(parts[0].filter { $0.isNumber }) ?? 0 : 0
                let n1 = parts.count > 1 ? Int(parts[1].filter { $0.isNumber }) ?? 0 : 0
                let n2 = parts.count > 2 ? Int(parts[2].filter { $0.isNumber }) ?? 0 : 0
                return (n0, n1, n2)
            }

            let remoteSem = parseSemver(tagName)
            let localSem = parseSemver(localVersion)

            var isNewerSemver = false
            if remoteSem.0 > localSem.0 { isNewerSemver = true }
            else if remoteSem.0 == localSem.0 && remoteSem.1 > localSem.1 { isNewerSemver = true }
            else if remoteSem.0 == localSem.0 && remoteSem.1 == localSem.1 && remoteSem.2 > localSem.2 { isNewerSemver = true }

            var isNewerRebuild = false
            if remoteSem.0 == localSem.0 && remoteSem.1 == localSem.1 && remoteSem.2 == localSem.2 {
                if let rSha = remoteSha, localBuildSha != "dev", rSha.lowercased() != localBuildSha.lowercased() {
                    isNewerRebuild = true
                }
            }

            guard isNewerSemver || isNewerRebuild else {
                if manual {
                    DispatchQueue.main.async {
                        self.updateStatusMessage = "Air Speaker v1.0.0 is up to date."
                    }
                }
                return
            }

            let newUpdateId = remoteSha != nil ? "\(tagName)_\(remoteSha!)" : tagName
            let skippedId = UserDefaults.standard.string(forKey: "skipped_update_id") ?? ""
            if !manual && skippedId == newUpdateId {
                return
            }

            var downloadUrl = htmlUrl
            if let assets = json["assets"] as? [[String: Any]] {
                for asset in assets {
                    let name = asset["name"] as? String ?? ""
                    if name.hasSuffix(".ipa") || name.contains("iOS") {
                        if let browserUrl = asset["browser_download_url"] as? String {
                            downloadUrl = browserUrl
                            break
                        }
                    }
                }
            }

            let displayVersion = (isNewerRebuild && remoteSha != nil) ? "\(tagName) (\(String(remoteSha!.prefix(7))))" : tagName

            DispatchQueue.main.async {
                self.updateId = newUpdateId
                self.updateAvailableVersion = displayVersion
                self.updateDownloadUrl = downloadUrl
                self.showUpdateBanner = true
                self.updateStatusMessage = "Update available: \(displayVersion)"
            }
        }.resume()
    }
}

// Backward compatibility alias
public typealias PhoneSpeakerEngine = AirSpeakerEngine
