import SwiftUI
import CoreGraphics
import ShuttleEyeCore

/// 场地标定视图：在冻结帧上点击两个点，选择真实距离
struct CalibrationView: View {
    let image: CGImage
    let onDone: (ScaleCalibration?) -> Void

    @State private var pointA: CGPoint?
    @State private var pointB: CGPoint?
    @State private var presetIndex = 0

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                let rect = aspectFitRect(imageSize: CGSize(width: image.width, height: image.height),
                                         container: geo.size)
                ZStack {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .scaledToFit()
                        .frame(width: rect.width, height: rect.height)
                        .position(x: rect.midX, y: rect.midY)

                    // 已选点与连线
                    if let a = pointA { marker(a, rect) }
                    if let b = pointB { marker(b, rect) }
                    if let a = pointA, let b = pointB {
                        Path { p in
                            p.move(to: display(a, rect))
                            p.addLine(to: display(b, rect))
                        }
                        .stroke(Theme.accent, style: StrokeStyle(lineWidth: 2, dash: [6, 4]))
                    }
                }
                .contentShape(Rectangle())
                .gesture(SpatialTapGesture().onEnded { value in
                    let imgPt = imagePoint(from: value.location, rect)
                    if pointA == nil {
                        pointA = imgPt
                    } else if pointB == nil {
                        pointB = imgPt
                    } else {
                        pointA = imgPt
                        pointB = nil
                    }
                })
            }
            .navigationTitle("标定比例尺")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { onDone(nil) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("确定") {
                        guard let a = pointA, let b = pointB else { return }
                        let cal = ScaleCalibration(pixelA: a, pixelB: b,
                                                   realDistanceMeters: ScaleCalibration.courtPresets[presetIndex].meters)
                        onDone(cal)
                    }
                    .disabled(pointA == nil || pointB == nil)
                }
            }
            .safeAreaInset(edge: .bottom) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("在场地上点击两个点：选择一条与相机成像平面平行、且与击球点等距的已知线段（如双打边线）。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Picker("参考长度", selection: $presetIndex) {
                        ForEach(ScaleCalibration.courtPresets.indices, id: \.self) { i in
                            Text(ScaleCalibration.courtPresets[i].name).tag(i)
                        }
                    }
                    .pickerStyle(.menu)
                    HStack {
                        Text(pointA == nil ? "① 点击第一个点" : (pointB == nil ? "② 点击第二个点" : "✓ 完成，可重新点击修改"))
                            .font(.subheadline.weight(.medium))
                        Spacer()
                        if pointA != nil || pointB != nil {
                            Button("重置") { pointA = nil; pointB = nil }
                                .font(.subheadline)
                        }
                    }
                }
                .padding()
                .background(.ultraThinMaterial)
            }
        }
    }

    // MARK: - 坐标映射

    private func aspectFitRect(imageSize: CGSize, container: CGSize) -> CGRect {
        let scale = min(container.width / imageSize.width, container.height / imageSize.height)
        let w = imageSize.width * scale, h = imageSize.height * scale
        return CGRect(x: (container.width - w) / 2, y: (container.height - h) / 2, width: w, height: h)
    }

    private func display(_ imgPoint: CGPoint, _ rect: CGRect) -> CGPoint {
        let sx = rect.width / CGFloat(image.width)
        let sy = rect.height / CGFloat(image.height)
        return CGPoint(x: rect.minX + imgPoint.x * sx, y: rect.minY + imgPoint.y * sy)
    }

    private func imagePoint(from displayPoint: CGPoint, _ rect: CGRect) -> CGPoint {
        let sx = CGFloat(image.width) / rect.width
        let sy = CGFloat(image.height) / rect.height
        return CGPoint(x: (displayPoint.x - rect.minX) * sx,
                       y: (displayPoint.y - rect.minY) * sy)
    }

    private func marker(_ imgPoint: CGPoint, _ rect: CGRect) -> some View {
        Circle()
            .fill(Theme.accent)
            .frame(width: 12, height: 12)
            .overlay(Circle().stroke(.white, lineWidth: 2))
            .position(display(imgPoint, rect))
    }
}
