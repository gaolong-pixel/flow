import SwiftUI

enum FlowPalette {
    static let background = Color(red: 0.961, green: 0.969, blue: 0.980) // #F5F7FA
    static let surface = Color.white
    static let primary = Color(red: 0.208, green: 0.416, blue: 0.902) // #356AE6
    static let primarySoft = Color(red: 0.918, green: 0.941, blue: 1.0) // #EAF0FF
    static let text = Color(red: 0.090, green: 0.125, blue: 0.200) // #172033
    static let secondaryText = Color(red: 0.482, green: 0.518, blue: 0.584) // #7B8495
    static let outline = Color(red: 0.898, green: 0.914, blue: 0.941) // #E5E9F0
    static let segmentBackground = Color(red: 0.914, green: 0.929, blue: 0.953) // #E9EDF3
    static let synced = Color(red: 0.129, green: 0.525, blue: 0.357) // #21865B
    static let successSoft = Color(red: 0.910, green: 0.961, blue: 0.933) // #E8F5EE
    static let dirty = Color(red: 0.835, green: 0.541, blue: 0.165) // #D58A2A
    static let warningSoft = Color(red: 1.0, green: 0.957, blue: 0.898) // #FFF4E5
    static let invalid = Color(red: 0.851, green: 0.294, blue: 0.294) // #D94B4B
    static let dangerSoft = Color(red: 0.992, green: 0.933, blue: 0.933) // #FDEEEE
    static let border = outline
    static let connector = Color(red: 0.863, green: 0.890, blue: 0.937) // #DCE3EF
    static let accent = primary
    static let accentSoft = primarySoft
}

enum FlowMetrics {
    static let pagePadding: CGFloat = 22
    static let cardRadius: CGFloat = 18
    static let buttonRadius: CGFloat = 14
    static let inputRadius: CGFloat = 12
    static let cardPadding: CGFloat = 18
    static let buttonHeight: CGFloat = 54
    static let secondaryButtonHeight: CGFloat = 46
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
            .flowFont(16, weight: .semibold)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(
                RoundedRectangle(cornerRadius: FlowMetrics.buttonRadius, style: .continuous)
                    .fill(FlowPalette.primary.opacity(configuration.isPressed ? 0.82 : 1))
            )
    }
}

struct FlowSecondaryButtonStyle: ButtonStyle {
    var height: CGFloat = FlowMetrics.secondaryButtonHeight

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .flowFont(16, weight: .medium)
            .foregroundColor(FlowPalette.primary)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(
                RoundedRectangle(cornerRadius: FlowMetrics.buttonRadius, style: .continuous)
                    .fill(FlowPalette.surface.opacity(configuration.isPressed ? 0.85 : 1))
            )
            .overlay(
                RoundedRectangle(cornerRadius: FlowMetrics.buttonRadius, style: .continuous)
                    .stroke(FlowPalette.outline, lineWidth: 1)
            )
    }
}

struct FlowTypeBadge: View {
    let type: StepType

    private var color: Color {
        type == .count ? FlowPalette.primary : FlowPalette.dirty
    }

    private var label: String {
        type == .count ? "COUNT" : "TIMER"
    }

    var body: some View {
        Text(label)
            .flowFont(11, weight: .semibold)
            .foregroundColor(color)
            .padding(.horizontal, 10)
            .frame(height: 28)
            .background(color.opacity(0.10))
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
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

struct FlowSectionTitle: View {
    let title: String
    let trailing: String?

    init(_ title: String, trailing: String? = nil) {
        self.title = title
        self.trailing = trailing
    }

    var body: some View {
        HStack {
            Text(title)
                .flowFont(18, weight: .semibold)
                .foregroundColor(FlowPalette.text)
            Spacer()
            if let trailing {
                Text(trailing)
                    .flowFont(13)
                    .foregroundColor(FlowPalette.secondaryText)
            }
        }
    }
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
