// swift-tools-version:5.9
// ShuttleEyeCore —— 羽毛球鹰眼核心算法库（平台无关，可独立测试）
import PackageDescription

let package = Package(
    name: "ShuttleEyeCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "ShuttleEyeCore", targets: ["ShuttleEyeCore"])
    ],
    targets: [
        .target(name: "ShuttleEyeCore", path: "Core"),
        .testTarget(name: "ShuttleEyeCoreTests", dependencies: ["ShuttleEyeCore"], path: "Tests")
    ]
)
