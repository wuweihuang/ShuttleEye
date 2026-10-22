import SwiftUI

/// 全局主题：颜色与卡片样式
enum Theme {
    static let accent = Color(red: 1.00, green: 0.58, blue: 0.11)   // 荧光球橙
    static let good = Color.green
    static let warn = Color.yellow
    static let bad = Color.red
    static let card = Color.white.opacity(0.06)
    static let screen = Color.black
}

struct CardStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(16)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
    }
}

extension View {
    func card() -> some View { modifier(CardStyle()) }
}

struct ContentView: View {
    var body: some View {
        TabView {
            LiveAnalysisView()
                .tabItem { Label("实时测速", systemImage: "camera.metering.matrix") }
            VideoAnalysisView()
                .tabItem { Label("视频分析", systemImage: "video") }
            HistoryView()
                .tabItem { Label("历史", systemImage: "clock.arrow.circlepath") }
            AboutView()
                .tabItem { Label("原理", systemImage: "book") }
        }
        .preferredColorScheme(.dark)
    }
}
