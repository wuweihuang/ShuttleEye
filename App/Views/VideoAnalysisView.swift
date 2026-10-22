import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import CoreGraphics
import ShuttleEyeCore

/// 视频分析：从相册选择视频 → 标定 → 分析
struct VideoAnalysisView: View {
    private enum Phase: Equatable {
        case pick, reading, calibrating, analyzing, done
    }

    @State private var pickerItem: PhotosPickerItem?
    @State private var phase: Phase = .pick
    @State private var progress: Double = 0
    @State private var frames: [(GrayscaleImage, Double, Int, Int, Int)] = []
    @State private var videoFPS: Double = 0
    @State private var firstFrameImage: CGImage?
    @State private var calibration: ScaleCalibration?
    @State private var result: AnalysisRunner.Output?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                switch phase {
                case .pick:
                    PhotosPicker(selection: $pickerItem, matching: .videos) {
                        VStack(spacing: 12) {
                            Image(systemName: "square.and.arrow.down.on.square")
                                .font(.system(size: 44))
                            Text("选择包含击球的视频")
                                .font(.headline)
                            Text("建议：侧面垂直机位、120/240fps 慢动作、三脚架固定")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 220)
                        .card()
                    }
                    .buttonStyle(.plain)

                case .reading:
                    VStack(spacing: 12) {
                        ProgressView(value: progress)
                        Text("正在读取视频帧… \(Int(progress * 100))%")
                            .font(.footnote).foregroundStyle(.secondary)
                    }.card()

                case .calibrating:
                    Text("请在首帧上标定比例尺").foregroundStyle(.secondary)

                case .analyzing:
                    VStack(spacing: 12) {
                        ProgressView()
                        Text("鹰眼分析中…")
                    }.card()

                case .done:
                    Text("完成").foregroundStyle(.secondary)
                }

                if let errorMessage {
                    Text(errorMessage).font(.footnote).foregroundStyle(Theme.bad)
                }
                Spacer()
            }
            .padding()
            .navigationTitle("视频分析")
            .onChange(of: pickerItem) { item in
                guard let item else { return }
                loadAndRead(item)
            }
            .sheet(isPresented: Binding(get: { phase == .calibrating && firstFrameImage != nil },
                                        set: { if !$0 { phase = frames.isEmpty ? .pick : .reading } })) {
                if let img = firstFrameImage {
                    CalibrationView { cal in
                        if let cal {
                            calibration = cal
                            runAnalysis()
                        } else {
                            phase = .pick
                        }
                    }
                    .onAppear { phase = .calibrating }
                }
            }
            .fullScreenCover(item: $result) { output in
                ResultView(output: output,
                           calibration: calibration ?? ScaleCalibration(pixelA: .zero, pixelB: CGPoint(x: 1, y: 0), realDistanceMeters: 1),
                           source: "视频分析") {
                    result = nil
                    reset()
                }
            }
        }
    }

    // MARK: - 流程

    private func loadAndRead(_ item: PhotosPickerItem) {
        errorMessage = nil
        item.loadTransferable(type: MovieTransferable.self) { r in
            switch r {
            case .success(let movie?):
                read(movie.url)
            case .failure(let e):
                DispatchQueue.main.async { errorMessage = e.localizedDescription }
            default:
                break
            }
        }
    }

    private func read(_ url: URL) {
        phase = .reading
        progress = 0
        frames.removeAll()
        VideoFrameReader.read(url: url, onFrame: { f in
            frames.append((f.image, f.time, f.originalWidth, f.originalHeight, f.downscale))
            if firstFrameImage == nil { firstFrameImage = f.image.toCGImage() }
        }, onProgress: { p in
            DispatchQueue.main.async { progress = p }
        }, completion: { r in
            DispatchQueue.main.async {
                switch r {
                case .success(let (fps, count)):
                    videoFPS = fps
                    guard count >= 6 else {
                        errorMessage = "有效帧不足，请更换视频"
                        phase = .pick
                        return
                    }
                    phase = .calibrating
                case .failure(let e):
                    errorMessage = e.localizedDescription
                    phase = .pick
                }
            }
        })
    }

    private func runAnalysis() {
        phase = .analyzing
        DispatchQueue.global(qos: .userInitiated).async {
            let output = AnalysisRunner.analyze(frames: frames, fps: videoFPS,
                                                calibration: calibration, backgroundImage: firstFrameImage)
            DispatchQueue.main.async {
                if let output {
                    self.result = output
                    self.phase = .done
                } else {
                    errorMessage = "未在视频中捕捉到有效轨迹（检查标定与画面）"
                    phase = .pick
                }
            }
        }
    }

    private func reset() {
        frames.removeAll()
        firstFrameImage = nil
        calibration = nil
        pickerItem = nil
        phase = .pick
    }
}

struct MovieTransferable: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let tmp = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString).appendingPathExtension("mov")
            try FileManager.default.copyItem(at: received.file, to: tmp)
            return MovieTransferable(url: tmp)
        }
    }
}
