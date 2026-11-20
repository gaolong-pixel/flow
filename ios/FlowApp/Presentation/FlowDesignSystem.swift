import SwiftUI
import UIKit

enum FlowPalette {
    static let background = Color(red: 0.961, green: 0.969, blue: 0.980) // #F5F7FA
    static let surface = Color.white
    static let surfaceMuted = Color(red: 0.933, green: 0.949, blue: 0.973)
    static let primary = Color(red: 0.208, green: 0.416, blue: 0.902) // #356AE6
    static let primaryBright = Color(red: 0.357, green: 0.549, blue: 1.0) // #5B8CFF
    static let primarySoft = Color(red: 0.918, green: 0.941, blue: 1.0)
    static let text = Color(red: 0.059, green: 0.090, blue: 0.165) // #0F172A
    static let secondaryText = Color(red: 0.518, green: 0.569, blue: 0.647) // #8491A5
    static let secondaryInk = Color(red: 0.373, green: 0.424, blue: 0.502) // #5F6C80
    static let outline = Color(red: 0.898, green: 0.914, blue: 0.941)
    static let outlineStrong = Color(red: 0.835, green: 0.871, blue: 0.918)
    static let segmentBackground = Color(red: 0.914, green: 0.929, blue: 0.953)

    static let success = Color(red: 0.208, green: 0.788, blue: 0.604) // #35C99A
    static let successInk = Color(red: 0.055, green: 0.478, blue: 0.376)
    static let successSoft = Color(red: 0.906, green: 0.976, blue: 0.949)
    static let warm = Color(red: 1.0, green: 0.706, blue: 0.369) // #FFB45E
    static let warmInk = Color(red: 0.659, green: 0.384, blue: 0.0)
    static let warmSoft = Color(red: 1.0, green: 0.945, blue: 0.871)
    static let invalid = Color(red: 0.851, green: 0.294, blue: 0.294)
    static let dangerSoft = Color(red: 0.992, green: 0.933, blue: 0.933)

    static let runBackground = Color(red: 0.043, green: 0.071, blue: 0.125) // #0B1220
    static let runSurface = Color(red: 0.086, green: 0.137, blue: 0.231) // #16233B
    static let runSurfaceRaised = Color(red: 0.114, green: 0.176, blue: 0.275)
    static let runText = Color(red: 0.961, green: 0.973, blue: 1.0)
    static let runSecondaryText = Color(red: 0.604, green: 0.667, blue: 0.757)
    static let runMutedText = Color(red: 0.490, green: 0.553, blue: 0.651)
    static let runTrack = Color(red: 0.133, green: 0.192, blue: 0.290) // #22314A
    static let runTrackStrong = Color(red: 0.176, green: 0.263, blue: 0.388)
    static let runOutline = Color(red: 0.192, green: 0.259, blue: 0.361)
    static let runDanger = Color(red: 1.0, green: 0.478, blue: 0.478)

    static let synced = successInk
    static let dirty = warmInk
    static let border = outline
    static let connector = outlineStrong
    static let accent = primary
    static let accentSoft = primarySoft
    static let warningSoft = warmSoft
}

enum FlowMetrics {
    static let pagePadding: CGFloat = 22
    static let cardRadius: CGFloat = 18
    static let sheetRadius: CGFloat = 18
    static let buttonRadius: CGFloat = 18
    static let inputRadius: CGFloat = 12
    static let cardPadding: CGFloat = 14
    static let buttonHeight: CGFloat = 52
    static let secondaryButtonHeight: CGFloat = 52
    static let gap: CGFloat = 14
    static let sectionGap: CGFloat = 24
    static let smallGap: CGFloat = 8
}

struct FlowCard<Content: View>: View {
    private let padding: CGFloat
    private let content: Content

    init(padding: CGFloat = FlowMetrics.cardPadding, @ViewBuilder content: () -> Content) {
        self.padding = padding
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            content
        }
        .padding(padding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlowPalette.surface)
        .clipShape(RoundedRectangle(cornerRadius: FlowMetrics.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: FlowMetrics.cardRadius, style: .continuous)
                .stroke(FlowPalette.outline, lineWidth: 1)
        )
    }
}

struct FlowPrimaryButtonStyle: ButtonStyle {
    var height: CGFloat = FlowMetrics.buttonHeight

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .flowFont(15, weight: .semibold)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(
                LinearGradient(
                    colors: [Color(red: 0.31, green: 0.55, blue: 1.0), Color(red: 0.18, green: 0.42, blue: 1.0)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            .clipShape(RoundedRectangle(cornerRadius: FlowMetrics.buttonRadius, style: .continuous))
            .shadow(color: FlowPalette.primary.opacity(configuration.isPressed ? 0.18 : 0.28), radius: 12, x: 0, y: 8)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct FlowSecondaryButtonStyle: ButtonStyle {
    var height: CGFloat = FlowMetrics.secondaryButtonHeight

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .flowFont(15, weight: .medium)
            .foregroundColor(FlowPalette.primary)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(
                RoundedRectangle(cornerRadius: FlowMetrics.buttonRadius, style: .continuous)
                    .fill(Color.white.opacity(configuration.isPressed ? 0.55 : 0.72))
            )
            .overlay(
                RoundedRectangle(cornerRadius: FlowMetrics.buttonRadius, style: .continuous)
                    .stroke(FlowPalette.outline, lineWidth: 1)
            )
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct FlowRunPrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .flowFont(15, weight: .semibold)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(FlowPalette.primary.opacity(configuration.isPressed ? 0.82 : 1))
            )
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct FlowRunSecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .flowFont(15, weight: .medium)
            .foregroundColor(FlowPalette.runText)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(FlowPalette.runSurface.opacity(configuration.isPressed ? 0.75 : 0.18))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(FlowPalette.runOutline, lineWidth: 1)
            )
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct FlowTypeBadge: View {
    let type: StepType

    private var color: Color {
        type == .count ? FlowPalette.primary : FlowPalette.warmInk
    }

    var body: some View {
        Text(type == .count ? "COUNT" : "TIMER")
            .flowFont(10, weight: .semibold)
            .foregroundColor(color)
            .padding(.horizontal, 9)
            .frame(height: 24)
            .background(color.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

struct FlowProgressBar: View {
    let progress: Double

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(FlowPalette.segmentBackground)
                Capsule()
                    .fill(FlowPalette.primary)
                    .frame(width: geometry.size.width * min(1, max(0, progress)))
            }
        }
        .frame(height: 4)
    }
}

struct FlowBoltMark: View {
    var size: CGFloat = 22

    var body: some View {
        Canvas { context, canvasSize in
            let w = canvasSize.width
            let h = canvasSize.height
            var path = Path()
            path.move(to: CGPoint(x: w * 0.58, y: 0))
            path.addLine(to: CGPoint(x: w * 0.18, y: h * 0.55))
            path.addLine(to: CGPoint(x: w * 0.48, y: h * 0.55))
            path.addLine(to: CGPoint(x: w * 0.38, y: h))
            path.addLine(to: CGPoint(x: w * 0.84, y: h * 0.40))
            path.addLine(to: CGPoint(x: w * 0.54, y: h * 0.40))
            path.closeSubpath()
            context.fill(
                path,
                with: .linearGradient(
                    Gradient(colors: [
                        Color(red: 0.46, green: 0.66, blue: 1.0),
                        Color(red: 0.23, green: 0.51, blue: 0.96),
                        Color(red: 0.14, green: 0.34, blue: 0.85)
                    ]),
                    startPoint: .zero,
                    endPoint: CGPoint(x: w, y: h)
                )
            )
        }
        .frame(width: size, height: size)
        .shadow(color: FlowPalette.primary.opacity(0.28), radius: 5, x: 0, y: 3)
    }
}

enum FlowPlanIcon { case sun, target, flow }

struct FlowPlanIconTile: View {
    let icon: FlowPlanIcon
    var size: CGFloat = 44

    private var gradient: LinearGradient {
        let colors: [Color]
        switch icon {
        case .sun:
            colors = [Color(red: 0.95, green: 0.98, blue: 1.0), Color(red: 0.85, green: 0.92, blue: 1.0)]
        case .target:
            colors = [Color(red: 0.96, green: 0.95, blue: 1.0), Color(red: 0.89, green: 0.91, blue: 1.0)]
        case .flow:
            colors = [Color(red: 0.94, green: 0.98, blue: 1.0), Color(red: 0.85, green: 0.92, blue: 1.0)]
        }
        return LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(gradient)
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(Color.white.opacity(0.85), lineWidth: 1)
                )
                .shadow(color: FlowPalette.primary.opacity(0.08), radius: 6, x: 0, y: 4)
            Canvas { context, canvasSize in
                let w = canvasSize.width
                let h = canvasSize.height
                let stroke = 1.8
                let brush = GraphicsContext.Shading.linearGradient(
                    Gradient(colors: [Color(red: 0.42, green: 0.63, blue: 1.0), Color(red: 0.18, green: 0.42, blue: 1.0)]),
                    startPoint: .zero,
                    endPoint: CGPoint(x: w, y: h)
                )
                switch icon {
                case .sun:
                    context.stroke(
                        Path(ellipseIn: CGRect(x: w * 0.31, y: h * 0.31, width: w * 0.38, height: h * 0.38)),
                        with: brush,
                        lineWidth: stroke
                    )
                    for index in 0..<8 {
                        let angle = Double(index) * .pi / 4
                        let inner = w * 0.30
                        let outer = w * 0.43
                        var ray = Path()
                        ray.move(to: CGPoint(x: w / 2 + inner * cos(angle), y: h / 2 + inner * sin(angle)))
                        ray.addLine(to: CGPoint(x: w / 2 + outer * cos(angle), y: h / 2 + outer * sin(angle)))
                        context.stroke(ray, with: brush, style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                    }
                case .target:
                    context.stroke(
                        Path(ellipseIn: CGRect(x: w * 0.1, y: h * 0.1, width: w * 0.8, height: h * 0.8)),
                        with: brush,
                        lineWidth: stroke
                    )
                    context.stroke(
                        Path(ellipseIn: CGRect(x: w * 0.26, y: h * 0.26, width: w * 0.48, height: h * 0.48)),
                        with: brush,
                        lineWidth: stroke
                    )
                    context.fill(
                        Path(ellipseIn: CGRect(x: w * 0.43, y: h * 0.43, width: w * 0.14, height: h * 0.14)),
                        with: brush
                    )
                case .flow:
                    for index in 0..<3 {
                        let y = h * (0.28 + Double(index) * 0.22)
                        var path = Path()
                        path.move(to: CGPoint(x: w * 0.08, y: y))
                        path.addCurve(
                            to: CGPoint(x: w * 0.92, y: y),
                            control1: CGPoint(x: w * 0.34, y: y - h * 0.16),
                            control2: CGPoint(x: w * 0.62, y: y + h * 0.16)
                        )
                        context.stroke(path, with: brush, style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                    }
                }
            }
            .frame(width: 23, height: 23)
        }
        .frame(width: size, height: size)
    }
}

struct FlowTypeGlyph: View {
    let type: StepType
    var size: CGFloat = 12
    var onDark: Bool = false

    private var color: Color {
        if type == .count {
            return onDark ? FlowPalette.primaryBright : FlowPalette.primary
        }
        return onDark ? FlowPalette.warm : FlowPalette.warmInk
    }

    var body: some View {
        Canvas { context, canvasSize in
            let w = canvasSize.width
            let h = canvasSize.height
            let stroke = 1.5
            if type == .count {
                for index in 0..<4 {
                    let x = w * (0.2 + Double(index) * 0.2)
                    var line = Path()
                    line.move(to: CGPoint(x: x, y: h * 0.2))
                    line.addLine(to: CGPoint(x: x, y: h * 0.82))
                    context.stroke(line, with: .color(color), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                }
                var slash = Path()
                slash.move(to: CGPoint(x: w * 0.08, y: h * 0.22))
                slash.addLine(to: CGPoint(x: w * 0.92, y: h * 0.78))
                context.stroke(slash, with: .color(color), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
            } else {
                context.stroke(
                    Path(ellipseIn: CGRect(x: w * 0.1, y: h * 0.1, width: w * 0.8, height: h * 0.8)),
                    with: .color(color),
                    lineWidth: stroke
                )
                var hands = Path()
                hands.move(to: CGPoint(x: w * 0.5, y: h * 0.3))
                hands.addLine(to: CGPoint(x: w * 0.5, y: h * 0.53))
                hands.addLine(to: CGPoint(x: w * 0.64, y: h * 0.63))
                context.stroke(hands, with: .color(color), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
            }
        }
        .frame(width: size, height: size)
    }
}

struct FlowChevron: View {
    var size: CGFloat = 14
    var color: Color = FlowPalette.secondaryText

    var body: some View {
        Canvas { context, canvasSize in
            var path = Path()
            path.move(to: CGPoint(x: canvasSize.width * 0.34, y: canvasSize.height * 0.18))
            path.addLine(to: CGPoint(x: canvasSize.width * 0.7, y: canvasSize.height * 0.5))
            path.addLine(to: CGPoint(x: canvasSize.width * 0.34, y: canvasSize.height * 0.82))
            context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 1.8, lineCap: .round))
        }
        .frame(width: size, height: size)
    }
}

struct FlowBackChevron: View {
    var size: CGFloat = 18
    var color: Color = FlowPalette.text

    var body: some View {
        Canvas { context, canvasSize in
            var path = Path()
            path.move(to: CGPoint(x: canvasSize.width * 0.64, y: canvasSize.height * 0.16))
            path.addLine(to: CGPoint(x: canvasSize.width * 0.3, y: canvasSize.height * 0.5))
            path.addLine(to: CGPoint(x: canvasSize.width * 0.64, y: canvasSize.height * 0.84))
            context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 2, lineCap: .round))
        }
        .frame(width: size, height: size)
    }
}

struct FlowClockIcon: View {
    var size: CGFloat = 14
    var color: Color = FlowPalette.secondaryText

    var body: some View {
        Canvas { context, canvasSize in
            let w = canvasSize.width
            let h = canvasSize.height
            context.stroke(
                Path(ellipseIn: CGRect(x: w * 0.1, y: h * 0.1, width: w * 0.8, height: h * 0.8)),
                with: .color(color),
                lineWidth: 1.5
            )
            var hands = Path()
            hands.move(to: CGPoint(x: w * 0.5, y: h * 0.28))
            hands.addLine(to: CGPoint(x: w * 0.5, y: h * 0.52))
            hands.addLine(to: CGPoint(x: w * 0.64, y: h * 0.61))
            context.stroke(hands, with: .color(color), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
        }
        .frame(width: size, height: size)
    }
}

enum FlowArtMode { case home, prepare, done, exit }

struct FlowLightArt: View {
    let mode: FlowArtMode

    var body: some View {
        Canvas { context, size in
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(FlowPalette.background))
            let topCenter = CGPoint(x: size.width * 0.92, y: size.height * 0.08)
            context.fill(
                Path(ellipseIn: CGRect(
                    x: topCenter.x - size.width * 0.9,
                    y: topCenter.y - size.width * 0.9,
                    width: size.width * 1.8,
                    height: size.width * 1.8
                )),
                with: .radialGradient(
                    Gradient(colors: [Color(red: 0.61, green: 0.75, blue: 1.0).opacity(0.24), .clear]),
                    center: topCenter,
                    startRadius: 0,
                    endRadius: size.width * 0.9
                )
            )
            let bottomCenter = CGPoint(x: size.width * 0.05, y: size.height * 0.92)
            context.fill(
                Path(ellipseIn: CGRect(
                    x: bottomCenter.x - size.width * 0.75,
                    y: bottomCenter.y - size.width * 0.75,
                    width: size.width * 1.5,
                    height: size.width * 1.5
                )),
                with: .radialGradient(
                    Gradient(colors: [Color(red: 0.73, green: 0.84, blue: 1.0).opacity(0.18), .clear]),
                    center: bottomCenter,
                    startRadius: 0,
                    endRadius: size.width * 0.75
                )
            )
            var ribbonA = Path()
            ribbonA.move(to: CGPoint(x: -size.width * 0.2, y: size.height * 0.08))
            ribbonA.addCurve(
                to: CGPoint(x: size.width * 1.18, y: size.height * 0.26),
                control1: CGPoint(x: size.width * 0.18, y: -size.height * 0.02),
                control2: CGPoint(x: size.width * 0.72, y: size.height * 0.03)
            )
            ribbonA.addLine(to: CGPoint(x: size.width * 1.18, y: size.height * 0.38))
            ribbonA.addCurve(
                to: CGPoint(x: -size.width * 0.2, y: size.height * 0.25),
                control1: CGPoint(x: size.width * 0.72, y: size.height * 0.15),
                control2: CGPoint(x: size.width * 0.2, y: size.height * 0.12)
            )
            ribbonA.closeSubpath()
            context.fill(
                ribbonA,
                with: .linearGradient(
                    Gradient(colors: [Color(red: 0.61, green: 0.75, blue: 1.0).opacity(0.30), Color(red: 0.92, green: 0.95, blue: 1.0).opacity(0.05)]),
                    startPoint: .zero,
                    endPoint: CGPoint(x: size.width, y: size.height * 0.4)
                )
            )
            var ribbonB = Path()
            ribbonB.move(to: CGPoint(x: -size.width * 0.2, y: size.height * 0.68))
            ribbonB.addCurve(
                to: CGPoint(x: size.width * 1.2, y: size.height * 0.78),
                control1: CGPoint(x: size.width * 0.25, y: size.height * 0.56),
                control2: CGPoint(x: size.width * 0.74, y: size.height * 0.60)
            )
            ribbonB.addLine(to: CGPoint(x: size.width * 1.2, y: size.height * 1.02))
            ribbonB.addLine(to: CGPoint(x: -size.width * 0.2, y: size.height * 1.02))
            ribbonB.closeSubpath()
            context.fill(
                ribbonB,
                with: .linearGradient(
                    Gradient(colors: [Color(red: 0.78, green: 0.87, blue: 1.0).opacity(0.18), Color(red: 0.96, green: 0.98, blue: 1.0).opacity(0.02)]),
                    startPoint: CGPoint(x: 0, y: size.height * 0.65),
                    endPoint: CGPoint(x: size.width, y: size.height)
                )
            )
            let contour = Color(red: 0.62, green: 0.74, blue: 0.94).opacity(0.28)
            for index in 0..<5 {
                let y = size.height * (0.30 + Double(index) * 0.045)
                var path = Path()
                path.move(to: CGPoint(x: -size.width * 0.1, y: y))
                path.addCurve(
                    to: CGPoint(x: size.width * 0.63, y: y - size.height * 0.02),
                    control1: CGPoint(x: size.width * 0.18, y: y - size.height * 0.05),
                    control2: CGPoint(x: size.width * 0.42, y: y + size.height * 0.04)
                )
                path.addCurve(
                    to: CGPoint(x: size.width * 1.1, y: y + size.height * 0.01),
                    control1: CGPoint(x: size.width * 0.82, y: y - size.height * 0.06),
                    control2: CGPoint(x: size.width * 0.96, y: y - size.height * 0.02)
                )
                context.stroke(path, with: .color(contour), lineWidth: 1)
            }
        }
        .ignoresSafeArea()
    }
}

struct FlowDarkArt: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var breath = false

    var body: some View {
        ZStack {
            Canvas { context, size in
                context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(FlowPalette.runBackground))
                let center = CGPoint(x: size.width * 0.5, y: size.height * 0.5)
                context.fill(
                    Path(ellipseIn: CGRect(
                        x: center.x - size.width * 0.75,
                        y: center.y - size.width * 0.75,
                        width: size.width * 1.5,
                        height: size.width * 1.5
                    )),
                    with: .radialGradient(
                        Gradient(colors: [
                            FlowPalette.primary.opacity(0.28),
                            FlowPalette.primary.opacity(0.08),
                            .clear
                        ]),
                        center: center,
                        startRadius: 0,
                        endRadius: size.width * 0.75
                    )
                )
                for index in 0..<3 {
                    let radius = size.width * (0.48 + Double(index) * 0.10)
                    context.stroke(
                        Path(ellipseIn: CGRect(
                            x: center.x - radius,
                            y: center.y - radius,
                            width: radius * 2,
                            height: radius * 2
                        )),
                        with: .color(Color(red: 0.19, green: 0.32, blue: 0.50).opacity(0.28)),
                        lineWidth: 1
                    )
                }
                var waveA = Path()
                waveA.move(to: CGPoint(x: -size.width * 0.15, y: size.height * 0.73))
                waveA.addCurve(
                    to: CGPoint(x: size.width * 0.63, y: size.height * 0.72),
                    control1: CGPoint(x: size.width * 0.18, y: size.height * 0.68),
                    control2: CGPoint(x: size.width * 0.4, y: size.height * 0.76)
                )
                waveA.addCurve(
                    to: CGPoint(x: size.width * 1.15, y: size.height * 0.70),
                    control1: CGPoint(x: size.width * 0.82, y: size.height * 0.69),
                    control2: CGPoint(x: size.width * 0.96, y: size.height * 0.73)
                )
                waveA.addLine(to: CGPoint(x: size.width * 1.15, y: size.height * 1.05))
                waveA.addLine(to: CGPoint(x: -size.width * 0.15, y: size.height * 1.05))
                waveA.closeSubpath()
                context.fill(
                    waveA,
                    with: .linearGradient(
                        Gradient(colors: [Color(red: 0.08, green: 0.16, blue: 0.29).opacity(0.80), FlowPalette.runBackground]),
                        startPoint: CGPoint(x: 0, y: size.height * 0.7),
                        endPoint: CGPoint(x: 0, y: size.height)
                    )
                )
                var waveB = Path()
                waveB.move(to: CGPoint(x: -size.width * 0.15, y: size.height * 0.82))
                waveB.addCurve(
                    to: CGPoint(x: size.width * 0.66, y: size.height * 0.80),
                    control1: CGPoint(x: size.width * 0.2, y: size.height * 0.77),
                    control2: CGPoint(x: size.width * 0.45, y: size.height * 0.85)
                )
                waveB.addCurve(
                    to: CGPoint(x: size.width * 1.15, y: size.height * 0.78),
                    control1: CGPoint(x: size.width * 0.86, y: size.height * 0.76),
                    control2: CGPoint(x: size.width * 0.98, y: size.height * 0.8)
                )
                waveB.addLine(to: CGPoint(x: size.width * 1.15, y: size.height * 1.05))
                waveB.addLine(to: CGPoint(x: -size.width * 0.15, y: size.height * 1.05))
                waveB.closeSubpath()
                context.fill(
                    waveB,
                    with: .linearGradient(
                        Gradient(colors: [Color(red: 0.05, green: 0.11, blue: 0.21).opacity(0.9), Color(red: 0.03, green: 0.06, blue: 0.11)]),
                        startPoint: CGPoint(x: 0, y: size.height * 0.78),
                        endPoint: CGPoint(x: 0, y: size.height)
                    )
                )
                let sparkles = [
                    CGPoint(x: 0.12, y: 0.20), CGPoint(x: 0.84, y: 0.28),
                    CGPoint(x: 0.74, y: 0.14), CGPoint(x: 0.22, y: 0.62),
                    CGPoint(x: 0.86, y: 0.58)
                ]
                for point in sparkles {
                    context.fill(
                        Path(ellipseIn: CGRect(
                            x: size.width * point.x - 1.4,
                            y: size.height * point.y - 1.4,
                            width: 2.8,
                            height: 2.8
                        )),
                        with: .color(Color(red: 0.66, green: 0.78, blue: 1.0).opacity(0.38))
                    )
                }
            }
            .ignoresSafeArea()

            LinearGradient(
                colors: [FlowPalette.primary.opacity(breath ? 0.10 : 0.04), .clear],
                startPoint: .center,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            .animation(reduceMotion ? nil : .easeInOut(duration: 3.2).repeatForever(autoreverses: true), value: breath)
            .onAppear { breath = true }
        }
    }
}

struct FlowRunProgressBar: View {
    let progress: Double

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.08))
                Capsule()
                    .fill(
                        LinearGradient(
                            colors: [FlowPalette.primaryBright, FlowPalette.primary],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .frame(width: geometry.size.width * min(1, max(0, progress)))
                    .shadow(color: FlowPalette.primaryBright.opacity(0.45), radius: 6, x: 0, y: 0)
            }
        }
        .frame(height: 6)
    }
}

struct FlowConfettiPiece: Identifiable {
    let id = UUID()
    let x: CGFloat
    let y: CGFloat
    let color: Color
    let angle: Double
    let width: CGFloat
    let height: CGFloat
    let isCircle: Bool
}

struct FlowConfettiView: View {
    let progress: Double

    private let pieces: [FlowConfettiPiece] = [
        .init(x: 0.10, y: 0.22, color: FlowPalette.primaryBright, angle: -18, width: 4, height: 9, isCircle: false),
        .init(x: 0.24, y: 0.10, color: Color(red: 0.58, green: 0.71, blue: 1.0), angle: 32, width: 4, height: 8, isCircle: false),
        .init(x: 0.40, y: 0.05, color: FlowPalette.success, angle: -42, width: 4, height: 9, isCircle: false),
        .init(x: 0.62, y: 0.06, color: FlowPalette.warm, angle: 18, width: 4, height: 8, isCircle: false),
        .init(x: 0.78, y: 0.16, color: FlowPalette.primaryBright, angle: 54, width: 4, height: 9, isCircle: false),
        .init(x: 0.88, y: 0.30, color: Color(red: 0.58, green: 0.71, blue: 1.0), angle: -24, width: 4, height: 8, isCircle: false),
        .init(x: 0.08, y: 0.44, color: FlowPalette.warm, angle: 64, width: 4, height: 9, isCircle: false),
        .init(x: 0.14, y: 0.66, color: FlowPalette.primaryBright, angle: -36, width: 4, height: 8, isCircle: false),
        .init(x: 0.86, y: 0.56, color: FlowPalette.success, angle: 18, width: 4, height: 9, isCircle: false),
        .init(x: 0.76, y: 0.74, color: Color(red: 0.58, green: 0.71, blue: 1.0), angle: -52, width: 4, height: 8, isCircle: false),
        .init(x: 0.44, y: 0.82, color: FlowPalette.warm, angle: 38, width: 4, height: 9, isCircle: false),
        .init(x: 0.60, y: 0.88, color: FlowPalette.primaryBright, angle: 12, width: 4, height: 8, isCircle: false),
        .init(x: 0.34, y: 0.34, color: FlowPalette.success, angle: 72, width: 3, height: 6, isCircle: true),
        .init(x: 0.68, y: 0.42, color: FlowPalette.warm, angle: -68, width: 3, height: 6, isCircle: true),
        .init(x: 0.24, y: 0.52, color: Color(red: 0.58, green: 0.71, blue: 1.0), angle: 44, width: 3, height: 6, isCircle: true)
    ]

    var body: some View {
        GeometryReader { geometry in
            ForEach(pieces) { piece in
                Group {
                    if piece.isCircle {
                        Circle().fill(piece.color)
                    } else {
                        RoundedRectangle(cornerRadius: 1)
                            .fill(piece.color)
                    }
                }
                .frame(width: piece.width, height: piece.height)
                .rotationEffect(.degrees(piece.angle + progress * 240))
                .position(
                    x: geometry.size.width * piece.x + sin(progress * 6 + piece.x * 10) * 5,
                    y: geometry.size.height * piece.y - (1 - progress) * 18
                )
                .opacity(progress)
            }
        }
    }
}

enum FlowHaptics {
    static func tick() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func commit() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func cancel() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
}

extension View {
    func flowFont(_ size: CGFloat, weight: Font.Weight = .regular) -> some View {
        modifier(FlowScaledFont(size: size, weight: weight, design: .default))
    }

    func flowMonoFont(_ size: CGFloat, weight: Font.Weight = .regular) -> some View {
        modifier(FlowScaledFont(size: size, weight: weight, design: .monospaced))
    }
}

private struct FlowScaledFont: ViewModifier {
    @ScaledMetric private var scaledSize: CGFloat
    private let weight: Font.Weight
    private let design: Font.Design

    init(size: CGFloat, weight: Font.Weight, design: Font.Design) {
        _scaledSize = ScaledMetric(wrappedValue: size, relativeTo: .body)
        self.weight = weight
        self.design = design
    }

    func body(content: Content) -> some View {
        content.font(.system(size: scaledSize, weight: weight, design: design))
    }
}
