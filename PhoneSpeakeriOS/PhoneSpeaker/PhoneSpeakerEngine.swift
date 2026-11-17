//
//  PhoneSpeakerEngine.swift
//  PhoneSpeaker
//
//  Full native iOS audio streaming, jitter buffering, microphone transmission,
//  multi-device party mode daisy-chain relay, and control protocol.
//  100% wire-compatible with Windows, Linux, macOS, and Android (PhoneSpeaker v2.0).
//

import Foundation
import UIKit
import AVFoundation
import ReplayKit
import Network
import Combine

// MARK: - Party Models
public struct PartyMember: Identifiable, Equatable {
    public let id: String
    public let name: String
    public let ip: String
    public var lastSeen: Date

    public init(name: String, ip: String) {
        self.id = "\(name)_\(ip)"
        self.name = name
        self.ip = ip
        self.lastSeen = Date()
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

// MARK: - PhoneSpeakerEngine
public final class PhoneSpeakerEngine: NSObject, ObservableObject {
    public static let shared = PhoneSpeakerEngine()

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
            playerNode.volume = isMuted ? 0.0 : 1.0
        }
    }
    @Published public var bufferTargetMs: Int = 20 {
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
    @Published public var outputEngineMode: String = "OBOE_LOW_LATENCY"
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
    @Published public var isPartyJoined: Bool = false
    @Published public var isPartyExtending: Bool = false {
        didSet { syncRelayFlags() }
    }
    @Published public var partyMembers: [PartyMember] = []
    @Published public var partyPeerIps: Set<String> = []
    @Published public var discoveredPartyHosts: [DiscoveredHost] = []
    @Published public var currentPartyHostIp: String = ""
    @Published public var statusMessage: String = "Ready"

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
    private var atomicBufferTargetMs: Int = 20
    private var atomicRelayActive: Bool = false
    private var atomicConnectedServerIp: String = ""
    private var isPrebufferingAudio: Bool = true

    // MARK: - Audio Engine Components
    private let audioEngine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let audioFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48000, channels: 2, interleaved: false)!
    private let audioBufferQueueCount = AtomicInt()

    // BSD POSIX UDP Sockets
    private var audioRxSocket: Int32 = -1
    private var controlSocket: Int32 = -1
    private var serveTxSocket: Int32 = -1
    private var relayTxSocket: Int32 = -1

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
        return PhoneSpeakerEngine.getPreferredHostIp()
    }

    override private init() {
        super.init()
        setupAudioSession()
        setupAudioEngine()
        refreshAdapterIps()

        // Start Control Channel (Port 5005) & 1-second Beacon Discovery IMMEDIATELY on init
        startControlSocket()
        startBeaconTimer()
    }

    deinit {
        stopAll()
    }

    private func syncRelayFlags() {
        let active = isPartyHost || isPartyExtending
        stateLock.lock()
        atomicRelayActive = active
        stateLock.unlock()
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
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth, .mixWithOthers])
            try session.setPreferredSampleRate(48000)
            try session.setPreferredIOBufferDuration(0.005) // 5ms buffer
            try session.setActive(true)
        } catch {
            print("AudioSession setup error: \(error)")
        }
    }

    private func setupAudioEngine() {
        audioEngine.attach(playerNode)
        audioEngine.connect(playerNode, to: audioEngine.mainMixerNode, format: audioFormat)
        playerNode.volume = isMuted ? 0.0 : 1.0
        do {
            try audioEngine.start()
        } catch {
            print("AVAudioEngine start error: \(error)")
        }
    }

    // MARK: - Start / Stop Speaker Player (UDP 5004)
    public func startSpeakerPlayer(port: Int = 5004, manualHost: String = "") {
        guard !isAudioPlaying else { return }
        setupAudioSession()
        if !audioEngine.isRunning {
            try? audioEngine.start()
        }
        isPrebufferingAudio = true
        playerNode.play()

        if !manualHost.isEmpty {
            updateServerIp(manualHost)
        }

        isRunningAudio = true
        startAudioRxSocket(port: port)
        startStatsTimer()

        DispatchQueue.main.async {
            self.isAudioPlaying = true
            self.statusMessage = "Speaker active (UDP \(port))"
        }
    }

    public func stopSpeakerPlayer() {
        guard isAudioPlaying else { return }
        isRunningAudio = false
        playerNode.stop()

        if !isInternalAudioServing {
            stopStatsTimer()
        }
        closeSocket(&audioRxSocket)
        audioBufferQueueCount.reset()

        DispatchQueue.main.async {
            self.isAudioPlaying = false
            self.statusMessage = "Speaker stopped"
        }
    }

    // MARK: - Exact 12-Byte Little-Endian Audio Receiver (UDP 5004)
    private func startAudioRxSocket(port: Int = 5004) {
        audioRxSocket = socket(AF_INET, SOCK_DGRAM, 0)
        guard audioRxSocket >= 0 else { return }

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
            return
        }

        rxThread = Thread { [weak self] in
            self?.runAudioRxLoop()
        }
        rxThread?.qualityOfService = .userInteractive
        rxThread?.start()
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

    private func runAudioRxLoop() {
        var buffer = [UInt8](repeating: 0, count: 4096)
        var senderAddr = sockaddr_in()
        var senderLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        var localSeqLast: UInt32 = 0
        var localLastSyncReq: TimeInterval = 0
        var cachedMyIps: [String] = PhoneSpeakerEngine.getLocalIpv4Addresses()
        var lastIpRefresh: TimeInterval = Date().timeIntervalSince1970

        while isRunningAudio && audioRxSocket >= 0 {
            let bytesRead = withUnsafeMutablePointer(to: &senderAddr) { addrPtr in
                addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    recvfrom(audioRxSocket, &buffer, buffer.count, 0, sa, &senderLen)
                }
            }

            // Exact 12-byte header requirement
            guard bytesRead > 12 else { continue }

            var senderIpStr = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &senderAddr.sin_addr, &senderIpStr, socklen_t(INET_ADDRSTRLEN))
            let senderIp = String(cString: senderIpStr)

            if atomicConnectedServerIp.isEmpty && senderIp != "127.0.0.1" {
                atomicConnectedServerIp = senderIp
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
            if localSeqLast > 0 && seq > localSeqLast + 1 {
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
                    cachedMyIps = PhoneSpeakerEngine.getLocalIpv4Addresses()
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

            // Cap queued buffers to max(12, bufferTargetMs * 2 / 5) so Wi-Fi bursts buffer smoothly!
            let maxQueued = max(12, (curTargetMs * 2) / 5)
            if audioBufferQueueCount.value >= maxQueued {
                atomicUnderruns.increment()
                continue
            }

            if let pcmBuf = AVAudioPCMBuffer(pcmFormat: audioFormat, frameCapacity: frameCount) {
                pcmBuf.frameLength = frameCount
                let left = pcmBuf.floatChannelData![0]
                let right = pcmBuf.floatChannelData![1]
                // Bit-perfect direct path when unmuted and volume is 100% (gain = 1.0)
                let isUnity = abs(curVolume - 1.0) < 0.001 && !curMuted
                if isUnity {
                    if channels == 1 {
                        for i in 0..<Int(frameCount) {
                            let off = 12 + i * 2
                            let u16 = UInt16(buffer[off]) | (UInt16(buffer[off + 1]) << 8)
                            let s = Float(Int16(bitPattern: u16)) / 32767.0
                            left[i] = s
                            right[i] = s
                        }
                    } else {
                        for i in 0..<Int(frameCount) {
                            let off = 12 + i * 4
                            let uL = UInt16(buffer[off]) | (UInt16(buffer[off + 1]) << 8)
                            let uR = UInt16(buffer[off + 2]) | (UInt16(buffer[off + 3]) << 8)
                            left[i] = Float(Int16(bitPattern: uL)) / 32767.0
                            right[i] = Float(Int16(bitPattern: uR)) / 32767.0
                        }
                    }
                } else {
                    let gain: Float = curMuted ? 0.0 : curVolume
                    if channels == 1 {
                        for i in 0..<Int(frameCount) {
                            let off = 12 + i * 2
                            let u16 = UInt16(buffer[off]) | (UInt16(buffer[off + 1]) << 8)
                            let s16 = Int16(bitPattern: u16)
                            let sample = PhoneSpeakerEngine.softKneeLimit((Float(s16) / 32767.0) * gain)
                            left[i] = sample
                            right[i] = sample
                        }
                    } else {
                        for i in 0..<Int(frameCount) {
                            let off = 12 + i * 4
                            let uL = UInt16(buffer[off]) | (UInt16(buffer[off + 1]) << 8)
                            let uR = UInt16(buffer[off + 2]) | (UInt16(buffer[off + 3]) << 8)
                            left[i] = PhoneSpeakerEngine.softKneeLimit((Float(Int16(bitPattern: uL)) / 32767.0) * gain)
                            right[i] = PhoneSpeakerEngine.softKneeLimit((Float(Int16(bitPattern: uR)) / 32767.0) * gain)
                        }
                    }
                }

                let minPrebuffer = max(3, curTargetMs / 5)
                if audioBufferQueueCount.value == 0 {
                    isPrebufferingAudio = true
                    playerNode.pause()
                }
                audioBufferQueueCount.increment()
                playerNode.scheduleBuffer(pcmBuf, at: nil, options: []) { [weak self] in
                    self?.audioBufferQueueCount.decrement()
                }
                if isPrebufferingAudio && audioBufferQueueCount.value >= minPrebuffer {
                    isPrebufferingAudio = false
                    if !playerNode.isPlaying {
                        playerNode.play()
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
        inet_pton(AF_INET, toIp, &dest.sin_addr)

        _ = withUnsafePointer(to: &dest) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                sendto(relayTxSocket, data, data.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
    }

    // MARK: - Option A: Internal System Audio Capture & Streamer (UDP 5004)
    public func toggleServingInternalAudio() {
        if isInternalAudioServing {
            stopServingInternalAudio()
        } else {
            startServingInternalAudio()
        }
    }

    public func startServingInternalAudio(targetIp: String = "") {
        guard !isInternalAudioServing else { return }
        let ip = targetIp.isEmpty ? (atomicConnectedServerIp.isEmpty ? "255.255.255.255" : atomicConnectedServerIp) : targetIp

        serveTxSocket = socket(AF_INET, SOCK_DGRAM, 0)
        var broadcast: Int32 = 1
        setsockopt(serveTxSocket, SOL_SOCKET, SO_BROADCAST, &broadcast, socklen_t(MemoryLayout<Int32>.size))
        var sndBuf: Int32 = 65536
        setsockopt(serveTxSocket, SOL_SOCKET, SO_SNDBUF, &sndBuf, socklen_t(MemoryLayout<Int32>.size))

        let recorder = RPScreenRecorder.shared()
        guard recorder.isAvailable else {
            DispatchQueue.main.async {
                self.statusMessage = "Screen recorder unavailable for internal audio"
            }
            return
        }

        var seq: UInt32 = 1
        var sampleAccumulator: [Int16] = []
        let serveLock = NSLock()

        recorder.startCapture(handler: { [weak self] (sampleBuffer, bufferType, error) in
            guard let self = self, error == nil, bufferType == .audioApp, self.serveTxSocket >= 0 else { return }

            guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
            var lengthAtOffset: Int = 0
            var totalLength: Int = 0
            var dataPointer: UnsafeMutablePointer<Int8>?
            guard CMBlockBufferGetDataPointer(blockBuffer, atOffset: 0, lengthAtOffsetOut: &lengthAtOffset, totalLengthOut: &totalLength, dataPointerOut: &dataPointer) == noErr,
                  let rawPtr = dataPointer else { return }

            let sampleCount = totalLength / 2
            let shortPtr = rawPtr.withMemoryRebound(to: Int16.self, capacity: sampleCount) { $0 }

            var maxSample: Int16 = 0
            serveLock.lock()
            for i in 0..<sampleCount {
                let s = shortPtr[i]
                let a = abs(s)
                if a > maxSample { maxSample = a }
                sampleAccumulator.append(s)
            }

            while sampleAccumulator.count >= 480 {
                let slice = Array(sampleAccumulator.prefix(480))
                sampleAccumulator.removeFirst(480)

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
                    let u = UInt16(bitPattern: slice[j])
                    packet[12 + j * 2] = UInt8(u & 0xFF)
                    packet[12 + j * 2 + 1] = UInt8((u >> 8) & 0xFF)
                }

                seq &+= 1
                self.servePacketsSent += 1

                self.sendServePacket(packet, toIp: ip)
            }
            serveLock.unlock()

            let peak = Float(maxSample) / 32767.0
            DispatchQueue.main.async {
                self.servePeakLevel = peak
            }
        }) { [weak self] error in
            DispatchQueue.main.async {
                if let error = error {
                    self?.statusMessage = "Capture error: \(error.localizedDescription)"
                    self?.isInternalAudioServing = false
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
        inet_pton(AF_INET, toIp, &destAddr.sin_addr)

        withUnsafePointer(to: &destAddr) { addrPtr in
            _ = addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                sendto(self.serveTxSocket, packet, 972, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
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
                self?.isInternalAudioServing = false
                self?.statusMessage = "Internal Audio Sender Stopped"
                self?.closeSocket(&self!.serveTxSocket)
                if let playing = self?.isAudioPlaying, !playing {
                    self?.stopStatsTimer()
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

            let myIps = PhoneSpeakerEngine.getLocalIpv4Addresses()
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
            }

        case "BEACON":
            let dev = payload["device"] as? String ?? (payload["device_name"] as? String ?? "Device")
            let isHost = payload["is_party_host"] as? Bool ?? false
            let isExt = payload["is_extended_host"] as? Bool ?? false
            let isPartyJoin = payload["party_join"] as? Bool ?? false

            if isHost || isExt {
                updateDiscoveredPartyHost(name: dev, ip: senderIp, port: 5004, isExtended: isExt)
            }

            // Downstream peer discovery
            if (isPartyHost || isPartyExtending) && isPartyJoin && !isHost {
                peersLock.lock()
                partyPeerIps.insert(senderIp)
                peersLock.unlock()
                updatePartyMember(name: dev, ip: senderIp)
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

        case "PARTY_KICK":
            let targetIp = payload["target_ip"] as? String ?? ""
            if targetIp.isEmpty || PhoneSpeakerEngine.getLocalIpv4Addresses().contains(targetIp) {
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

    private func updatePartyMember(name: String, ip: String) {
        if let idx = partyMembers.firstIndex(where: { $0.ip == ip }) {
            partyMembers[idx].lastSeen = Date()
        } else {
            partyMembers.append(PartyMember(name: name, ip: ip))
        }
    }

    public func addManualPeer(ip: String) {
        let trimmed = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        peersLock.lock()
        partyPeerIps.insert(trimmed)
        peersLock.unlock()
        updatePartyMember(name: "Peer (\(trimmed))", ip: trimmed)
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
        isPartyHost = true
        isPartyJoined = false
        isPartyExtending = false
        partyMembers.removeAll()
        peersLock.lock()
        partyPeerIps.removeAll()
        peersLock.unlock()
        startSpeakerPlayer()
        broadcastBeacon()
    }

    public func joinParty(hostIp: String, port: Int = 5004) {
        let (ip, p) = PhoneSpeakerEngine.parseHostIpFromUri(hostIp)
        let actualIp = ip.isEmpty ? hostIp : ip
        let actualPort = p > 0 ? p : port

        partyScreen = "JOIN"
        isPartyHost = false
        isPartyJoined = true
        currentPartyHostIp = actualIp
        updateServerIp(actualIp)

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
        if isPartyJoined && !currentPartyHostIp.isEmpty {
            sendControlMessage([
                "type": "BEACON",
                "app": "PhoneSpeaker",
                "version": 2,
                "device": deviceName,
                "party_join": false,
                "audio_port": 5004
            ], toIp: currentPartyHostIp)
        }
        stopPartyKeepAliveTimer()
        stopSpeakerPlayer()
        closeSocket(&relayTxSocket)
        partyScreen = "CHOOSER"
        isPartyHost = false
        isPartyJoined = false
        isPartyExtending = false
        currentPartyHostIp = ""
        partyMembers.removeAll()
        peersLock.lock()
        partyPeerIps.removeAll()
        peersLock.unlock()
    }

    // MARK: - QR URI Generator & Parser
    public func makePartyQrUri(isExtended: Bool) -> String {
        let encodedName = deviceName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "Phone"
        return "phonespeaker://join?host=\(localIpAddress)&port=5004&name=\(encodedName)"
    }

    public static func parseHostIpFromUri(_ raw: String) -> (String, Int) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)

        // 1. phonespeaker://join?host=... or http://...
        if trimmed.hasPrefix("phonespeaker://") || trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://") {
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
        if trimmed.hasPrefix("PHONESPEAKER_PARTY:") {
            let after = String(trimmed.dropFirst("PHONESPEAKER_PARTY:".count))
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
        beaconTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.broadcastBeacon()
        }
    }

    public func broadcastBeacon() {
        let beacon: [String: Any] = [
            "type": "BEACON",
            "app": "PhoneSpeaker",
            "version": 2,
            "ips": PhoneSpeakerEngine.getLocalIpv4Addresses(),
            "audio_port": 5004,
            "device": self.deviceName,
            "party_join": false,
            "is_party_host": self.isPartyHost && !self.isPartyExtending,
            "is_extended_host": self.isPartyExtending,
            "serving": self.isInternalAudioServing,
            "platform": "ios",
            "force_pcm": true
        ]
        broadcastControlMessage(beacon)
    }

    private func sendPartyJoinAnnouncement(hostIp: String) {
        let joinBeacon: [String: Any] = [
            "type": "BEACON",
            "app": "PhoneSpeaker",
            "version": 2,
            "device": self.deviceName,
            "party_join": true,
            "is_party_host": false,
            "is_extended_host": self.isPartyExtending,
            "audio_port": 5004
        ]
        sendControlMessage(joinBeacon, toIp: hostIp)
    }

    private func startPartyKeepAliveTimer() {
        partyKeepAliveTimer?.invalidate()
        partyKeepAliveTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: true) { [weak self] _ in
            guard let self = self, self.isPartyJoined, !self.currentPartyHostIp.isEmpty else { return }
            self.sendPartyJoinAnnouncement(hostIp: self.currentPartyHostIp)
        }
    }

    private func stopPartyKeepAliveTimer() {
        partyKeepAliveTimer?.invalidate()
        partyKeepAliveTimer = nil
    }

    private func startStatsTimer() {
        statsTimer?.invalidate()
        statsTimer = Timer.scheduledTimer(withTimeInterval: 0.3, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.stateLock.lock()
            let curRx = self.packetsReceived
            let curLost = self.packetsLost
            let curServeTx = self.servePacketsSent
            self.stateLock.unlock()

            let total = curRx + curLost
            self.packetLossPercent = total > 0 ? (Double(curLost) / Double(total)) * 100.0 : 0.0
            let queueBufMs = Double(self.audioBufferQueueCount.value * 5)
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

            self.rxPps = Int(curRx >= self.lastRxPpsCount ? (curRx - self.lastRxPpsCount) : 0) * 3
            self.lastRxPpsCount = curRx

            self.serveTxPps = Int(curServeTx >= self.lastServePacketsCount ? (curServeTx - self.lastServePacketsCount) : 0) * 3
            self.lastServePacketsCount = curServeTx

            // Sync atomic underruns to published property
            self.underrunCount = self.atomicUnderruns.value

            if !self.connectedServerIp.isEmpty {
                let stats: [String: Any] = [
                    "type": "STATS",
                    "buffer_ms": curBufMs,
                    "target_ms": Double(self.bufferTargetMs),
                    "underruns": self.underrunCount,
                    "lost_packets": curLost,
                    "late_packets": 0,
                    "auto_mode": (self.bufferMode == "AUTO"),
                    "rx_pps": self.rxPps,
                    "active_codec": 0,
                    "device": self.deviceName,
                    "serving": self.isInternalAudioServing,
                    "serve_pps": self.serveTxPps,
                    "platform": "ios",
                    "force_pcm": true
                ]
                self.sendControlMessage(stats, toIp: self.connectedServerIp)
            }
        }
    }

    private func stopStatsTimer() {
        statsTimer?.invalidate()
        statsTimer = nil
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
        inet_pton(AF_INET, toIp, &dest.sin_addr)

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
}
