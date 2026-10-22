import SwiftUI
import ShuttleEyeCore

/// 结果页：峰值测速 + 物理验证 + 轨迹复核
struct ResultView: View {
    let output: AnalysisRunner.Output
    let calibration: ScaleCalibration
    let source: String
    let onClose: () -> Void

    @State private var removed: Set<Int> = []
    @StateObject private var history = HistoryStore()
    @Environment(\.dismiss) private var dismiss

    private var basePoints: [TrackPoint] { output.analysis.rectified }

    /// 剔除异常点后的重算结果
    private var speed: SpeedResult? {
        SpeedCalculator.compute(points: effectivePoints, calibration: calibration)
    }
    private var physics: PhysicsReport? {
        speed != nil ? PhysicsValidator.validate(points: effectivePoints, calibration: calibration) : nil
    }
    private var effectivePoints: [TrackPoint] {
        removed.isEmpty ? basePoints : basePoints.enumerated().filter { !removed.contains($0.offset) }.map(\.element)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    peakCard
                    verificationCard
                    if let speed {
                        CardView {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("速度曲线（逐帧段速度）").font(.headline)
                                SpeedChartView(segments: speed.segments,
                                               peakIndex: speed.peakSegmentIndex)
                                Text("点击轨迹图上的点可剔除异常帧并自动重算")
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                    TrajectoryOverlayView(backgroundImage: output.backgroundImage,
                                          points: basePoints,
                                          removed: removed) { idx in
                        if removed.contains(idx) { removed.remove(idx) } else { removed.insert(idx) }
                    }
                        .frame(height: 260)
                }
                .padding()
            }
            .navigationTitle("测速结果")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { onClose() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(speed == nil)
                }
            }
        }
    }

    // MARK: - 卡片

    private var peakCard: some View {
        CardView {
            VStack(spacing: 6) {
                Text("峰值球速（击球瞬间）")
                    .font(.subheadline).foregroundStyle(.secondary)
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(speed.map { String(format: "%.1f", $0.peakKmh) } ?? "--")
                        .font(.system(size: 64, weight: .bold, design: .rounded))
                        .foregroundStyle(Theme.accent)
                    Text("km/h").font(.title2).foregroundStyle(.secondary)
                }
                if let speed {
                    Text("±\(String(format: "%.1f", speed.peakUncertaintyKmh)) km/h（像素量化误差）")
                        .font(.caption).foregroundStyle(.secondary)
                    HStack(spacing: 16) {
                        stat("平滑峰值", value: String(format: "%.1f", speed.smoothedPeakKmh))
                        stat("平均球速", value: String(format: "%.1f", speed.averageKmh))
                        stat("帧率", value: String(format: "%.0f fps", output.fps))
                    }
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var verificationCard: some View {
        CardView {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Image(systemName: physics?.isConsistent == true ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(physics?.isConsistent == true ? Theme.good : Theme.warn)
                    Text("物理一致性验证")
                        .font(.headline)
                    Spacer()
                }
                if let p = physics {
                    Text(p.isConsistent
                         ? "轨迹衰减与羽毛球空气阻力模型吻合，测速结果自洽可信。"
                         : "轨迹与阻力模型存在偏差，结果仅供参考。建议：提高帧率、固定机位、重新标定。")
                        .font(.footnote).foregroundStyle(.secondary)
                    Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 6) {
                        GridRow {
                            stat("气动系数 k", value: String(format: "%.3f /m", p.dragCoeffK))
                            stat("拟合优度 R²", value: String(format: "%.3f", p.r2))
                        }
                        GridRow {
                            stat("雷达等效速度", value: p.radarEquivalentKmh.map { String(format: "%.1f km/h", $0) } ?? "--")
                            stat("可疑帧段", value: p.suspiciousSegments.isEmpty ? "无" : "\(p.suspiciousSegments.count) 段")
                        }
                    }
                    Text("雷达等效速度 = 距击球点 4m 处的推算速度，可与专业雷达枪读数直接对比。")
                        .font(.caption2).foregroundStyle(.secondary)
                } else {
                    Text("有效段数不足，无法进行物理验证。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func stat(_ name: String, value: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.subheadline.weight(.semibold))
            Text(name).font(.caption2).foregroundStyle(.secondary)
        }
    }

    private func save() {
        guard let speed else { return }
        let record = SessionRecord(
            source: source,
            fps: output.fps,
            peakKmh: speed.peakKmh,
            uncertaintyKmh: speed.peakUncertaintyKmh,
            smoothedPeakKmh: speed.smoothedPeakKmh,
            radarEquivalentKmh: physics?.radarEquivalentKmh,
            dragCoeffK: physics?.dragCoeffK,
            r2: physics?.r2,
            physicsConsistent: physics?.isConsistent ?? false,
            calibration: calibration,
            points: effectivePoints,
            speedCurve: speed.segments.map { [$0.t0, $0.speedKmh] }
        )
        history.add(record)
        onClose()
    }
}

/// 简易卡片容器（避免与 Core 的 CardView 命名冲突）
private struct CardView<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        content.padding(16)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
    }
}
