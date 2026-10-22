import Foundation
import CoreGraphics
import ShuttleEyeCore

/// 分析调度器：串联 检测 → 跟踪 → 修复 → 测速 → 物理验证
final class AnalysisRunner {

    struct Output {
        let track: [TrackPoint]
        let analysis: AnalysisOutput
        let fps: Double
        let originalWidth: Int
        let originalHeight: Int
        let backgroundImage: CGImage?
    }

    /// 帧处理器：流式消费，内存中只保留检测所需的前一帧
    struct FrameProcessor {
        private let detector: ShuttlecockDetector
        private var tracker = TrajectoryTracker()
        private var tracks: [[TrackPoint]] = []
        private var prev: GrayscaleImage?
        private var idx = 0

        init(detector: ShuttlecockDetector) {
            self.detector = detector
        }

        mutating func process(_ frame: GrayscaleImage,
                              _ time: Double,
                              downscale: Int,
                              originalWidth: Int,
                              calibration: ScaleCalibration?) {
            let dets = detector.detect(previous: prev, current: frame)
                .map { d -> Detection in
                    var d = d
                    d.center = CGPoint(x: d.center.x * Double(downscale),
                                       y: d.center.y * Double(downscale))
                    d.bboxSize = CGSize(width: d.bboxSize.width * Double(downscale),
                                        height: d.bboxSize.height * Double(downscale))
                    d.blurLength *= Double(downscale)
                    return d
                }

            if tracker.isFinished {
                if tracker.points.count >= 2 { tracks.append(tracker.points) }
                tracker = TrajectoryTracker()
            }

            _ = tracker.process(frameIndex: idx,
                                time: time,
                                detections: dets,
                                frameWidth: Double(originalWidth),
                                scaleMetersPerPixel: calibration?.scale)
            prev = frame
            idx += 1
        }

        mutating func finish() -> [[TrackPoint]] {
            if tracker.points.count >= 2 { tracks.append(tracker.points) }
            return tracks
        }
    }

    static func makeDetector() -> ShuttlecockDetector {
        if let ml = CoreMLShuttleDetector() { return ml }
        return ClassicalMotionDetector()
    }

    /// 处理已捕获的帧序列（实时模式与视频模式共用）
    static func analyze(frames: [(GrayscaleImage, Double, Int, Int, Int)],
                        fps: Double,
                        calibration: ScaleCalibration?,
                        backgroundImage: CGImage?) -> Output? {
        guard let first = frames.first else { return nil }
        let originalWidth = first.2
        let originalHeight = first.3

        var processor = FrameProcessor(detector: makeDetector())
        for (img, t, _, ds, _) in frames {
            processor.process(img, t, downscale: ds, originalWidth: originalWidth, calibration: calibration)
        }
        let tracks = processor.finish()
        return finalize(tracks: tracks, fps: fps,
                        originalWidth: originalWidth, originalHeight: originalHeight,
                        calibration: calibration, backgroundImage: backgroundImage)
    }

    /// 多段轨迹择优并输出
    static func finalize(tracks: [[TrackPoint]],
                         fps: Double,
                         originalWidth: Int,
                         originalHeight: Int,
                         calibration: ScaleCalibration?,
                         backgroundImage: CGImage?) -> Output? {
        guard let (track, analysis) = AnalysisPipeline.bestAnalysis(
            tracks: tracks, frameWidth: Double(originalWidth), calibration: calibration) else { return nil }
        return Output(track: track, analysis: analysis, fps: fps,
                      originalWidth: originalWidth, originalHeight: originalHeight,
                      backgroundImage: backgroundImage)
    }
}
