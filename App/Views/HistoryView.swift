import SwiftUI
import Charts

/// 历史记录
struct HistoryView: View {
    @StateObject private var store = HistoryStore()

    var body: some View {
        NavigationStack {
            Group {
                if store.records.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "clock.arrow.circlepath")
                            .font(.system(size: 40)).foregroundStyle(.secondary)
                        Text("暂无历史记录").foregroundStyle(.secondary)
                    }
                } else {
                    List {
                        ForEach(store.records) { r in
                            NavigationLink {
                                recordDetail(r)
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 4) {
                                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                                            Text(String(format: "%.1f", r.peakKmh))
                                                .font(.title3.weight(.bold)).foregroundStyle(Theme.accent)
                                            Text("km/h").font(.caption).foregroundStyle(.secondary)
                                            if r.physicsConsistent {
                                                Image(systemName: "checkmark.seal.fill")
                                                    .font(.caption).foregroundStyle(Theme.good)
                                            }
                                        }
                                        Text("\(r.source) · \(r.date.formatted(date: .abbreviated, time: .shortened))")
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                }
                            }
                        }
                        .onDelete { idx in
                            for i in idx { store.delete(store.records[i]) }
                        }
                    }
                }
            }
            .navigationTitle("历史记录")
        }
    }

    private func recordDetail(_ r: SessionRecord) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                VStack(spacing: 4) {
                    Text(String(format: "%.1f", r.peakKmh))
                        .font(.system(size: 56, weight: .bold, design: .rounded))
                        .foregroundStyle(Theme.accent)
                    Text("km/h  ±\(String(format: "%.1f", r.uncertaintyKmh))")
                        .font(.caption).foregroundStyle(.secondary)
                }.padding(.top)

                Grid(alignment: .leading, horizontalSpacing: 24, verticalSpacing: 8) {
                    GridRow {
                        stat("平滑峰值", String(format: "%.1f", r.smoothedPeakKmh))
                        stat("雷达等效", r.radarEquivalentKmh.map { String(format: "%.1f", $0) } ?? "--")
                    }
                    GridRow {
                        stat("气动系数 k", r.dragCoeffK.map { String(format: "%.3f /m", $0) } ?? "--")
                        stat("R²", r.r2.map { String(format: "%.3f", $0) } ?? "--")
                    }
                }
                .card()

                if r.speedCurve.count > 1 {
                    Chart {
                        ForEach(r.speedCurve.indices, id: \.self) { i in
                            LineMark(x: .value("t", r.speedCurve[i][0]),
                                     y: .value("v", r.speedCurve[i][1]))
                        }
                    }
                    .frame(height: 160).card()
                }

                Text("标定：\(String(format: "%.4f", r.calibration.scale)) m/px · \(String(format: "%.0f", r.fps)) fps")
                    .font(.caption).foregroundStyle(.secondary)
            }
            .padding()
        }
        .navigationTitle("记录详情")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func stat(_ name: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.headline)
            Text(name).font(.caption2).foregroundStyle(.secondary)
        }
    }
}
