import Foundation
import AVFoundation
import CoreMedia

/// 从相册视频文件流式提取帧（不缓存全部帧，控制内存）
enum VideoFrameReader {

    enum ReaderError: LocalizedError {
        case noVideoTrack
        case readingFailed(String)

        var errorDescription: String? {
            switch self {
            case .noVideoTrack: return "视频中未找到轨视频道"
            case .readingFailed(let m): return "视频读取失败：\(m)"
            }
        }
    }

    /// 逐帧回调（处理图坐标系 + downscale 倍率）
    struct Frame {
        let image: GrayscaleImage
        let time: Double
        let downscale: Int
        let originalWidth: Int
        let originalHeight: Int
    }

    static func read(url: URL,
                     maxDimension: Int = 960,
                     onFrame: @escaping (Frame) -> Void,
                     onProgress: @escaping (Double) -> Void,
                     completion: @escaping (Result<(fps: Double, totalFrames: Int), Error>) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let asset = AVURLAsset(url: url)
                guard let track = asset.tracks(withMediaType: .video).first else {
                    throw ReaderError.noVideoTrack
                }
                let fps = Double(track.nominalFrameRate)
                let duration = CMTimeGetSeconds(asset.duration)

                let reader = try AVAssetReader(asset: asset)
                let output = AVAssetReaderTrackOutput(track: track,
                                                      outputSettings: [
                                                        kCVPixelBufferPixelFormatTypeKey as String:
                                                            kCVPixelFormatType_32BGRA
                                                      ])
                output.alwaysCopiesSampleData = false
                reader.add(output)
                reader.startReading()

                var count = 0
                while let sample = output.copyNextSampleBuffer() {
                    guard let pb = CMSampleBufferGetImageBuffer(sample) else { continue }
                    let ts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
                    if let conv = GrayscaleConverter.convert(pb, maxDimension: maxDimension) {
                        count += 1
                        onFrame(Frame(image: conv.image,
                                      time: ts,
                                      downscale: conv.downscale,
                                      originalWidth: CVPixelBufferGetWidth(pb),
                                      originalHeight: CVPixelBufferGetHeight(pb)))
                    }
                    onProgress(duration > 0 ? min(1, ts / duration) : 0)
                }

                if let err = reader.error {
                    throw ReaderError.readingFailed(err.localizedDescription)
                }
                completion(.success((fps, count)))
            } catch {
                completion(.failure(error))
            }
        }
    }
}
