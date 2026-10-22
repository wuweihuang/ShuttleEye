import SwiftUI

/// 原理与使用说明
struct AboutView: View {
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    section("鹰眼系统原理") {
                        Text("""
                        本系统基于开源项目 TrackNetV3（羽毛球追踪行业标准，准确率 97.51%）与移动端测速研究（arXiv:2509.05334）构建：

                        1. 检测：TrackNet 风格热图模型（可选，需自行转换 CoreML）或经典 CV 帧差检测，输出亮度加权亚像素质心；
                        2. 跟踪：卡尔曼滤波（恒速模型）+ 复合评分（0.3·检测置信度 + 0.7·预测邻近度）+ 启发式速度边界（5~375 km/h）；
                        3. 轨迹修复：中值离群剔除 + Catmull-Rom 样条填补短时丢失；
                        4. 测速：v = (Δ像素/Δt) × 比例尺 × 3.6，含运动模糊前缘补偿与误差量化；
                        5. 物理验证：拟合空气阻力模型 dv/dt = -k·v²，校验测速自洽性，并推算雷达枪等效速度。
                        """)
                    }

                    section("拍摄要求（决定精度上限）") {
                        bullet("相机垂直于球的飞行平面（侧机位），减少深度透视误差")
                        bullet("使用 240fps（其次 120fps）；帧率越高测速误差越小")
                        bullet("三脚架固定，避免机位抖动污染帧差")
                        bullet("标定线段须与击球点等距、与成像平面平行")
                        bullet("光线充足、背景简洁，避免白色广告牌等干扰")
                    }

                    section("速度验证说明") {
                        Text("""
                        为什么本系统峰值速度显著高于雷达枪？
                        羽毛球空气阻力极大（击球瞬间减速度可达 ~200g），球速沿飞行方向快速衰减。本系统测得的是击球瞬间的真实峰值，而雷达枪需要球飞行一段距离才能锁定，读到的是已衰减的速度。App 内的「雷达等效速度」即按阻力模型推算出的 4m 距离处读数，可与雷达枪直接对比。
                        """)
                    }

                    section("开源参考") {
                        link("TrackNetV3 (qaz812345)", "https://github.com/qaz812345/TrackNetV3")
                        link("TrackNetV2 (NYCU)", "https://nol.cs.nctu.edu.tw:234/open-source/TrackNetv2/")
                        link("Badminton_Challenge (IJCAI 金奖)", "https://github.com/jean50621/Badminton_Challenge")
                        link("MonoTrack 单目3D轨迹重建", "https://arxiv.org/abs/2204.01899")
                        link("移动端杀球测速系统 (arXiv:2509.05334)", "https://arxiv.org/abs/2509.05334")
                    }
                }
                .padding()
            }
            .navigationTitle("原理说明")
        }
    }

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func bullet(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Circle().fill(Theme.accent).frame(width: 6, height: 6).padding(.top, 6)
            Text(text).font(.subheadline)
        }
    }

    private func link(_ text: String, _ url: String) -> some View {
        if let u = URL(string: url) {
            Link(destination: u) {
                HStack {
                    Text(text).font(.subheadline)
                    Spacer()
                    Image(systemName: "arrow.up.right.square").foregroundStyle(.secondary)
                }
            }
        }
    }
}
