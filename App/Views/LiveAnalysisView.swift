import SwiftUI
import CoreMedia
import ShuttleEyeCore

/// 实时测速主界面
struct LiveAnalysisView: View {
    private enum Phase: Equatable {
        case idle, calibrating, recording, analyzing, result
    }

    @StateObject private var camera = CameraService()
    @State private var phase: Phase = .idle
    @State private var calibration: ScaleCalibration?
    @State private var frozenImage: CGImage?
    @State private var showCalibration = false
    @State private var result: AnalysisRunner.Output?
    @State private var statusText = ""
    @State private var preferredFPS = 240

    var body: some View {
        NavigationStack {
            ZStack {
                CameraPreviewView(session: camera.session)
                    .ignoresSafeArea()

                VStack {
                    topBar
                    Spacer()
                    bottomControls
                }
            }
            .navigationTitle("实时测速")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showCalibration, onDismiss: {
                if phase == .calibrating { phase = .idle }
            }) {
                if let img = frozenImage {
                    CalibrationView(image: img) { cal in
                        showCalibration = false
                        if let cal { calibration = cal }
                    }
                }
            }
            .fullScreenCover(item: $result) { output in
                ResultView(output: output, calibration: calibration ?? placeholderCalibration,
                           source: "实时测量") {
                    result = nil
                    phase = .idle
                }
            }
            .onAppear {
                if !camera.isConfigured {
                    try? camera.configure(preferredFPS: preferredFPS)
                }
                camera.start()
            }
            .onDisappear { camera.stop() }
        }
    }

    // MARK: - 子视图

    private var topBar: some View {
        HStack {
            Label(camera.activeFPS > 0 ? "\(camera.activeFPS) FPS" : "未连接",
                  systemImage: "dot.radiowaves.left.and.right")
                .font(.footnote.weight(.semibold))
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(.ultraThinMaterial, in: Capsule())
            Spacer()
            Button {
                camera.capturePreviewFrame { img in
                    frozenImage = img
                    showCalibration = true
                    phase = .calibrating
                }
            } label: {
                Label(calibration == nil ? "标定比例尺" : "已标定 ✓",
                      systemImage: "ruler")
                    .font(.footnote.weight(.semibold))
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(calibration == nil ? Color.orange.opacity(0.8) : Color.green.opacity(0.8),
                                in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal)
    }

    private var bottomControls: some View {
        VStack(spacing: 12) {
            if !statusText.isEmpty {
                Text(statusText)
                    .font(.footnote)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(.ultraThinMaterial, in: Capsule())
            }

            Button {
                startCapture()
            } label: {
                Circle()
                    .fill(canStart ? Theme.accent : Color.gray.opacity(0.5))
                    .frame(width: 84, height: 84)
                    .overlay(
                        Circle().stroke(.white.opacity(0.7), lineWidth: 4).padding(-6)
                    )
                    .overlay(
                        Group {
                            if phase == .recording { ProgressView().tint(.white) }
                            else { Text("捕捉").font(.headline).foregroundStyle(.white) }
                        }
                    )
            }
            .disabled(!canStart)
            .animation(.easeInOut(duration: 0.2), value: phase)

            if calibration == nil {
                Text("请先标定比例尺，再开始捕捉")
                    .font(.caption).foregroundStyle(.white.opacity(0.8))
            }
        }
        .padding(.bottom, 32)
    }

    private var canStart: Bool {
        calibration != nil && (phase == .idle || phase == .result)
    }

    // MARK: - 动作

    private func startCapture() {
        guard let cal = calibration else { return }
        phase = .recording
        statusText = "录制中，请保持手机固定…"
        camera.recordClip(duration: 2.0) { pixelFrames in
            phase = .analyzing
            statusText = "分析中…"
            DispatchQueue.global(qos: .userInitiated).async {
                var frames: [(GrayscaleImage, Double, Int, Int, Int)] = []
                var bg: CGImage? = nil
                for (i, f) in pixelFrames.enumerated() {
                    if let conv = GrayscaleConverter.convert(f.pixelBuffer) {
                        frames.append((conv.image,
                                       CMTimeGetSeconds(f.time),
                                       CVPixelBufferGetWidth(f.pixelBuffer),
                                       CVPixelBufferGetHeight(f.pixelBuffer),
                                       conv.downscale))
                        if i == pixelFrames.count / 2, bg == nil { bg = f.pixelBuffer.toCGImage() }
                    }
                }
                let fps = pixelFrames.count >= 2
                    ? Double(pixelFrames.count - 1) / max(0.001, frames.last!.1 - frames.first!.1)
                    : 0
                let output = AnalysisRunner.analyze(frames: frames, fps: fps,
                                                    calibration: cal, backgroundImage: bg)
                DispatchQueue.main.async {
                    if let output {
                        self.result = output
                        self.statusText = ""
                    } else {
                        self.phase = .idle
                        self.statusText = "未捕捉到有效轨迹，请确保：球在画面内、机位固定、光线充足"
                    }
                }
            }
        }
    }
}

// 占位标定（不应被用到；结果页需有效标定）
extension LiveAnalysisView {
    var placeholderCalibration: ScaleCalibration {
        ScaleCalibration(pixelA: .zero, pixelB: CGPoint(x: 1, y: 0), realDistanceMeters: 1)
    }
}
