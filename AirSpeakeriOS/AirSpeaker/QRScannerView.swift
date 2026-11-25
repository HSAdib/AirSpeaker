//
//  QRScannerView.swift
//  AirSpeaker
//
//  Modern Fullscreen Camera QR Scanner with Corner Reticle, Torch, Gallery Photo Scanner,
//  and Clipboard Paste for Party Mode & Host Discovery.
//

import SwiftUI
import UIKit
import AVFoundation
import AudioToolbox
import CoreImage
import CoreImage.CIFilterBuiltins
import PhotosUI

// MARK: - SwiftUI Modern QR Scanner
public struct QRScannerView: View {
    public let onCodeScanned: (String) -> Void
    @Environment(\.presentationMode) var presentationMode

    @State private var isTorchOn: Bool = false
    @State private var showPhotoPicker: Bool = false
    @State private var alertMessage: String? = nil
    @State private var showAlert: Bool = false

    public init(onCodeScanned: @escaping (String) -> Void) {
        self.onCodeScanned = onCodeScanned
    }

    public var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // 1. Live Camera Feed
            CameraScannerRepresentable(isTorchOn: isTorchOn) { code in
                handleDetectedCode(code)
            }
            .ignoresSafeArea()

            // 2. Translucent Scrim with Centered Framing Box & 4 White Corner Brackets
            ScannerFrameOverlay()
                .ignoresSafeArea()

            // 3. Top Header Bar: Close Button & Instruction Badge
            VStack {
                HStack {
                    Button(action: {
                        presentationMode.wrappedValue.dismiss()
                    }) {
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 40, height: 40)
                            .background(Color.black.opacity(0.6))
                            .clipShape(Circle())
                    }

                    Spacer()

                    Text("SCAN HOST QR CODE")
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.65))
                        .cornerRadius(20)
                        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.white.opacity(0.3), lineWidth: 1))

                    Spacer()

                    // Balance spacer for header symmetry
                    Color.clear.frame(width: 40, height: 40)
                }
                .padding(.horizontal, 16)
                .padding(.top, 50)

                Spacer()

                // 4. Floating Bottom Action Dock: Torch | Gallery Scan | Paste Link
                HStack(spacing: 16) {
                    // Torch Button
                    Button(action: {
                        isTorchOn.toggle()
                    }) {
                        VStack(spacing: 4) {
                            Image(systemName: isTorchOn ? "flashlight.on.fill" : "flashlight.off.fill")
                                .font(.system(size: 20))
                                .foregroundColor(isTorchOn ? Color(red: 1.0, green: 0.85, blue: 0.0) : .white)
                            Text("TORCH")
                                .font(.system(size: 9, weight: .bold, design: .monospaced))
                                .foregroundColor(.white)
                        }
                        .frame(width: 72, height: 56)
                        .background(Color(white: 0.15).opacity(0.85))
                        .cornerRadius(12)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(isTorchOn ? Color(red: 1.0, green: 0.85, blue: 0.0) : Color.white.opacity(0.2), lineWidth: 1))
                    }

                    // Gallery Photo Pick Button
                    Button(action: {
                        showPhotoPicker = true
                    }) {
                        VStack(spacing: 4) {
                            Image(systemName: "photo.on.rectangle")
                                .font(.system(size: 20))
                                .foregroundColor(Color(red: 0.22, green: 0.71, blue: 1.0))
                            Text("GALLERY")
                                .font(.system(size: 9, weight: .bold, design: .monospaced))
                                .foregroundColor(.white)
                        }
                        .frame(width: 72, height: 56)
                        .background(Color(white: 0.15).opacity(0.85))
                        .cornerRadius(12)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.white.opacity(0.2), lineWidth: 1))
                    }

                    // Paste Link / IP Button
                    Button(action: {
                        pasteFromClipboard()
                    }) {
                        VStack(spacing: 4) {
                            Image(systemName: "doc.on.clipboard")
                                .font(.system(size: 20))
                                .foregroundColor(Color(red: 0.0, green: 1.0, blue: 0.0))
                            Text("PASTE")
                                .font(.system(size: 9, weight: .bold, design: .monospaced))
                                .foregroundColor(.white)
                        }
                        .frame(width: 72, height: 56)
                        .background(Color(white: 0.15).opacity(0.85))
                        .cornerRadius(12)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.white.opacity(0.2), lineWidth: 1))
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .background(Color.black.opacity(0.75))
                .cornerRadius(24)
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.white.opacity(0.25), lineWidth: 1))
                .padding(.bottom, 40)
            }
        }
        .sheet(isPresented: $showPhotoPicker) {
            PhotoPickerView { selectedImage in
                if let image = selectedImage {
                    decodeQrFromImage(image)
                }
            }
        }
        .alert(isPresented: $showAlert) {
            Alert(title: Text("QR Code"), message: Text(alertMessage ?? ""), dismissButton: .default(Text("OK")))
        }
    }

    private func handleDetectedCode(_ code: String) {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        AudioServicesPlaySystemSound(SystemSoundID(kSystemSoundID_Vibrate))
        onCodeScanned(trimmed)
        presentationMode.wrappedValue.dismiss()
    }

    private func pasteFromClipboard() {
        if let clip = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines), !clip.isEmpty {
            handleDetectedCode(clip)
        } else {
            alertMessage = "Clipboard is empty or does not contain text."
            showAlert = true
        }
    }

    private func decodeQrFromImage(_ image: UIImage) {
        guard let ciImage = CIImage(image: image) ?? (image.cgImage.map { CIImage(cgImage: $0) }) else {
            alertMessage = "Could not load selected photo."
            showAlert = true
            return
        }

        let detectorOptions = [CIDetectorAccuracy: CIDetectorAccuracyHigh]
        guard let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: detectorOptions) else {
            alertMessage = "QR Detector unavailable."
            showAlert = true
            return
        }

        let features = detector.features(in: ciImage)
        for feature in features {
            if let qrFeature = feature as? CIQRCodeFeature, let message = qrFeature.messageString, !message.isEmpty {
                handleDetectedCode(message)
                return
            }
        }

        alertMessage = "No valid QR code found in selected photo."
        showAlert = true
    }
}

// MARK: - Translucent Scrim & Corner Brackets Overlay
struct ScannerFrameOverlay: View {
    var body: some View {
        GeometryReader { geo in
            let boxSize: CGFloat = min(geo.size.width * 0.72, 280)
            let boxRect = CGRect(
                x: (geo.size.width - boxSize) / 2,
                y: (geo.size.height - boxSize) / 2 - 30,
                width: boxSize,
                height: boxSize
            )

            ZStack {
                // Dimmed surround
                Color.black.opacity(0.55)
                    .mask(
                        RectangleCutoutMask(cutoutRect: boxRect)
                            .fill(style: FillStyle(eoFill: true))
                    )

                // 4 Corner Brackets
                CornerBracketsShape(rect: boxRect, armLength: 32, cornerRadius: 8)
                    .stroke(Color.white, style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
            }
        }
    }
}

struct RectangleCutoutMask: Shape {
    let cutoutRect: CGRect

    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.addRect(rect)
        path.addRoundedRect(in: cutoutRect, cornerSize: CGSize(width: 8, height: 8))
        return path
    }
}

struct CornerBracketsShape: Shape {
    let rect: CGRect
    let armLength: CGFloat
    let cornerRadius: CGFloat

    func path(in bounds: CGRect) -> Path {
        var path = Path()
        let r = cornerRadius
        let a = armLength

        // Top-Left
        path.move(to: CGPoint(x: rect.minX, y: rect.minY + a))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        path.addQuadCurve(to: CGPoint(x: rect.minX + r, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX + a, y: rect.minY))

        // Top-Right
        path.move(to: CGPoint(x: rect.maxX - a, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        path.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY + r), control: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + a))

        // Bottom-Right
        path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - a))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        path.addQuadCurve(to: CGPoint(x: rect.maxX - r, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX - a, y: rect.maxY))

        // Bottom-Left
        path.move(to: CGPoint(x: rect.minX + a, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        path.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - r), control: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - a))

        return path
    }
}

// MARK: - Live Camera UIViewControllerRepresentable
struct CameraScannerRepresentable: UIViewControllerRepresentable {
    let isTorchOn: Bool
    let onCodeScanned: (String) -> Void

    func makeUIViewController(context: Context) -> CameraScannerViewController {
        let vc = CameraScannerViewController()
        vc.onCodeScanned = onCodeScanned
        return vc
    }

    func updateUIViewController(_ uiViewController: CameraScannerViewController, context: Context) {
        uiViewController.setTorch(on: isTorchOn)
    }
}

final class CameraScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCodeScanned: ((String) -> Void)?
    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var currentDevice: AVCaptureDevice?
    private var hasScanned: Bool = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupCamera()
    }

    private func setupCamera() {
        let session = AVCaptureSession()
        guard let videoCaptureDevice = AVCaptureDevice.default(for: .video) else { return }
        self.currentDevice = videoCaptureDevice
        guard let videoInput = try? AVCaptureDeviceInput(device: videoCaptureDevice) else { return }

        if session.canAddInput(videoInput) {
            session.addInput(videoInput)
        } else {
            return
        }

        let metadataOutput = AVCaptureMetadataOutput()
        if session.canAddOutput(metadataOutput) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            metadataOutput.metadataObjectTypes = [.qr]
        } else {
            return
        }

        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.frame = view.layer.bounds
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)
        self.previewLayer = previewLayer

        self.captureSession = session
        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    func setTorch(on: Bool) {
        guard let device = currentDevice, device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = on ? .on : .off
            device.unlockForConfiguration()
        } catch {
            // Ignore torch error
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.layer.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        setTorch(on: false)
        if captureSession?.isRunning == true {
            captureSession?.stopRunning()
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !hasScanned else { return }
        if let metadataObject = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
           let stringValue = metadataObject.stringValue, !stringValue.isEmpty {
            hasScanned = true
            captureSession?.stopRunning()
            onCodeScanned?(stringValue)
        }
    }
}

// MARK: - Photo Library Picker (PHPickerViewController)
struct PhotoPickerView: UIViewControllerRepresentable {
    let onImagePicked: (UIImage?) -> Void
    @Environment(\.presentationMode) var presentationMode

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let parent: PhotoPickerView

        init(parent: PhotoPickerView) {
            self.parent = parent
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            parent.presentationMode.wrappedValue.dismiss()
            guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else {
                parent.onImagePicked(nil)
                return
            }

            provider.loadObject(ofClass: UIImage.self) { image, _ in
                DispatchQueue.main.async {
                    self.parent.onImagePicked(image as? UIImage)
                }
            }
        }
    }
}

// MARK: - QR Code Generator Helper
public struct QRCodeHelper {
    public static func generateQRCode(from string: String) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else { return nil }
        let scaledImage = outputImage.transformed(by: CGAffineTransform(scaleX: 10, y: 10))

        if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
            return UIImage(cgImage: cgImage)
        }
        return nil
    }
}
