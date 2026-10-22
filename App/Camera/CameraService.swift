import AVFoundation
import CoreMedia
import CoreImage
import UIKit

/// 高帧率相机服务
///
/// 精度要点：测速误差 ∝ 1/帧率，因此优先选择 240fps → 120fps 的格式。
/// 使用 .inputPriority preset 以确保设备高帧率格式不被会话 preset 降级。
final class CameraService: NSObject, ObservableObject {

    enum CameraError: LocalizedError {
        case noDevice
        case configurationFailed(String)

        var errorDescription: String? {
            switch self {
            case .noDevice: return "未找到可用的后置摄像头"
            case .configurationFailed(let reason): return "相机配置失败：\(reason)"
            }
        }
    }

    struct CapturedFrame {
        let pixelBuffer: CVPixelBuffer
        let time: CMTime
    }

    let session = AVCaptureSession()
    @Published private(set) var activeFPS: Int = 0
    @Published private(set) var isConfigured = false

    private let videoQueue = DispatchQueue(label: "shuttleeye.video", qos: .userInteractive)
    private var isRecordingClip = false
    private var clipBuffer: [CapturedFrame] = []
    private var clipStartTime: CMTime?
    private var clipDuration: Double = 2.0
    private var clipHandler: (([CapturedFrame]) -> Void)?
    private var previewOnce: ((CGImage) -> Void)?

    // MARK: - 配置

    func configure(preferredFPS: Int = 240) throws {
        guard !isConfigured else { return }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        // .inputPriority：完全采用设备选择的格式（高帧率必需）
        session.sessionPreset = .inputPriority

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            throw CameraError.noDevice
        }

        guard let picked = Self.selectFormat(for: device, preferredFPS: preferredFPS) else {
            throw CameraError.configurationFailed("该设备不支持高帧率录制（120/240fps）")
        }

        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: device)
        } catch {
            throw CameraError.configurationFailed("无法创建输入：\(error.localizedDescription)")
        }
        guard session.canAddInput(input) else {
            throw CameraError.configurationFailed("无法添加视频输入")
        }
        session.addInput(input)

        do {
            try device.lockForConfiguration()
            let ts = CMTimeScale(picked.fps)
            device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: ts)
            device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: ts)
            device.unlockForConfiguration()
        } catch {
            throw CameraError.configurationFailed("无法设置帧率：\(error.localizedDescription)")
        }

        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: videoQueue)
        guard session.canAddOutput(output) else {
            throw CameraError.configurationFailed("无法添加视频输出")
        }
        session.addOutput(output)
        activeFPS = picked.fps
        isConfigured = true
    }

    func start() {
        guard isConfigured, !session.isRunning else { return }
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    func stop() {
        guard session.isRunning else { return }
        DispatchQueue.global(qos: .userInitiated).async { self.session.stopRunning() }
    }

    // MARK: - 采集

    /// 录制一段固定时长的片段（默认 2s，240fps 下约 480 帧）
    func recordClip(duration: Double, completion: @escaping ([CapturedFrame]) -> Void) {
        videoQueue.async {
            self.clipBuffer.removeAll(keepingCapacity: true)
            self.clipDuration = duration
            self.clipStartTime = nil
            self.clipHandler = completion
            self.isRecordingClip = true
        }
    }

    /// 抓取一帧用于标定
    func capturePreviewFrame(completion: @escaping (CGImage) -> Void) {
        videoQueue.async { self.previewOnce = completion }
    }

    // MARK: - 格式选择（优先高帧率，其次分辨率）

    static func selectFormat(for device: AVCaptureDevice,
                             preferredFPS: Int) -> (format: AVCaptureDevice.Format, fps: Int)? {
        for fps in [preferredFPS, 240, 120, 60] where fps > 0 {
            let target = 1.0 / Double(fps)
            var best: AVCaptureDevice.Format?
            var bestPixels = 0
            for f in device.formats {
                let dims = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
                let supported = f.videoSupportedFrameRateRanges.contains {
                    $0.minFrameDuration.seconds <= target + 1e-9 && $0.maxFrameDuration.seconds >= target - 1e-9
                }
                guard supported else { continue }
                let px = Int(dims.width) * Int(dims.height)
                if px > bestPixels {
                    bestPixels = px
                    best = f
                }
            }
            if let f = best { return (f, fps) }
        }
        return nil
    }
}

// MARK: - 帧回调

extension CameraService: AVCaptureVideoDataOutputSampleBufferDelegate {

    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        // 一次性抓帧（标定用）
        if let once = previewOnce {
            previewOnce = nil
            if let img = pb.toCGImage() {
                DispatchQueue.main.async { once(img) }
            }
            return
        }

        guard isRecordingClip else { return }
        let ts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        if clipStartTime == nil { clipStartTime = ts }
        let elapsed = CMTimeGetSeconds(ts - clipStartTime!)
        clipBuffer.append(CapturedFrame(pixelBuffer: pb, time: ts))

        if elapsed >= clipDuration {
            isRecordingClip = false
            let frames = clipBuffer
            clipBuffer = []
            let handler = clipHandler
            clipHandler = nil
            if let handler {
                DispatchQueue.main.async { handler(frames) }
            }
        }
    }
}

// MARK: - CVPixelBuffer → CGImage

extension CVPixelBuffer {
    func toCGImage() -> CGImage? {
        let ciImage = CIImage(cvPixelBuffer: self)
        let context = CIContext(options: nil)
        return context.createCGImage(ciImage, from: ciImage.extent)
    }
}
