// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "mgbridge",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "mgbridge",
            path: "Sources/mgbridge"
        ),
        .testTarget(
            name: "mgbridgeTests",
            dependencies: ["mgbridge"],
            path: "Tests/mgbridgeTests"
        ),
    ]
)
