import SwiftUI
import Charts
import ShuttleEyeCore

/// 轨迹叠加视图（支持点击剔除异常点后重算）
struct TrajectoryOverlayView: View {
    let backgroundImage: CGImage?
    let points: [TrackPoint]
    let removed: Set<Int>
    let onToggleRemove: (Int) -> Void

    var body: some View {
        GeometryReader { geo in
            let rect = fitRect(for: geo.size)
            ZStack {
                if let bg = backgroundImage {
                    Image(decorative: bg, scale: 1)
                        .resizable().scaledToFit()
                        .frame(width: rect.width, height: rect.height)
                        .position(x: rect.midX, y: rect.midY)
                } else {
                    RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.04))
                }

                let visible = points.enumerated().filter { !removed.contains($0.offset) }
                // 轨迹线
                Path { p in
                    guard let first = visible.first else { return }
                    p.move(to: display(first.element.center, rect))
                    for pt in visible.dropFirst() {
                        p.addLine(to: display(pt.element.center, rect))
                    }
                }
                .stroke(Theme.accent.opacity(0.85), lineWidth: 2)

                // 速度渐变点：峰值段高亮
                ForEach(visible, id: \.offset) { item in
                    Circle()
                        .fill(item.element.isInterpolated ? Color.gray.opacity(0.6) : Color.cyan)
                        .frame(width: item.element.isInterpolated ? 5 : 7,
                               height: item.element.isInterpolated ? 5 : 7)
                        .position(display(item.element.center, rect))
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0).onEnded { value in
                    let pt = imagePoint(from: value.location, rect: rect)
                    // 找最近点
                    var best: (Int, Double)?
                    for (i, p) in points.enumerated() {
                        let d = hypot(p.center.x - pt.x, p.center.y - pt.y)
                        if best == nil || d < best!.1 { best = (i, d) }
                    }
                    if let b = best, b.1 < Double(rect.width) * 0.03 {
                        onToggleRemove(b.0)
                    }
                }
            )
        }
        .card()
    }

    private func fitRect(for size: CGSize) -> CGRect {
        guard let bg = backgroundImage, bg.width > 0 else {
            return CGRect(origin: .zero, size: size)
        }
        let s = min(size.width / CGFloat(bg.width), size.height / CGFloat(bg.height))
        let w = CGFloat(bg.width) * s, h = CGFloat(bg.height) * s
        return CGRect(x: (size.width - w) / 2, y: (size.height - h) / 2, width: w, height: h)
    }

    private func display(_ p: CGPoint, _ rect: CGRect) -> CGPoint {
        guard let bg = backgroundImage, bg.width > 0 else { return p }
        let sx = rect.width / CGFloat(bg.width)
        let sy = rect.height / CGFloat(bg.height)
        return CGPoint(x: rect.minX + p.x * sx, y: rect.minY + p.y * sy)
    }

    private func imagePoint(from dp: CGPoint, rect: CGRect) -> CGPoint {
        guard let bg = backgroundImage, bg.width > 0, rect.width > 0 else { return dp }
        let sx = CGFloat(bg.width) / rect.width
        let sy = CGFloat(bg.height) / rect.height
        return CGPoint(x: (dp.x - rect.minX) * sx, y: (dp.y - rect.minY) * sy)
    }
}

/// 速度曲线
struct SpeedChartView: View {
    let segments: [SegmentSpeed]
    let peakIndex: Int

    var body: some View {
        Chart {
            ForEach(segments, id: \.index) { seg in
                LineMark(x: .value("时间 (s)", seg.t0),
                         y: .value("km/h", seg.speedKmh))
                .foregroundStyle(seg.index == peakIndex ? Theme.accent : Color.cyan.opacity(0.6))
                .lineStyle(seg.index == peakIndex ? .init(lineWidth: 3) : .init(lineWidth: 1.5))

                PointMark(x: .value("时间 (s)", seg.t0),
                          y: .value("km/h", seg.speedKmh))
                .foregroundStyle(seg.index == peakIndex ? Theme.accent : Color.cyan.opacity(0.5))
                .symbolSize(seg.index == peakIndex ? 80 : 25)
            }
        }
        .chartYAxisLabel("km/h")
        .frame(height: 180)
    }
}
