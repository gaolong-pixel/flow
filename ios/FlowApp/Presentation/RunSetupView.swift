import SwiftUI

struct RunSetupView: View {
    let plan: Routine
    let message: String?
    let onBack: () -> Void
    /// Returns an error message when the run cannot start, nil on success.
    let onStart: () -> String?

    @State private var localError: String?

    var body: some View {
        VStack(spacing: 0) {
            header

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(plan.name)
                        .flowFont(24, weight: .semibold)
                        .foregroundColor(FlowPalette.text)
                        .lineLimit(2)
                    Text("共 \(plan.steps.count) 个节点")
                        .flowFont(13)
                        .foregroundColor(FlowPalette.secondaryText)
                        .padding(.top, 5)

                    VStack(spacing: 0) {
                        ForEach(Array(plan.steps.enumerated()), id: \.element.id) { index, step in
                            SetupStepRow(
                                step: step,
                                index: index,
                                isLast: index == plan.steps.count - 1
                            )
                        }
                    }
                    .padding(.top, 24)

                    HStack(spacing: 8) {
                        Image(systemName: "arrow.down")
                            .flowFont(11, weight: .semibold)
                            .foregroundColor(FlowPalette.primary)
                            .frame(width: 22, height: 22)
                            .background(FlowPalette.primarySoft)
                            .clipShape(Circle())
                        Text("将按照节点顺序依次执行")
                            .flowFont(13)
                            .foregroundColor(FlowPalette.secondaryText)
                        Spacer()
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 12)
                    .background(FlowPalette.segmentBackground.opacity(0.65))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .padding(.top, 24)

                    if let text = localError ?? message {
                        Text(text)
                            .flowFont(13)
                            .foregroundColor(FlowPalette.invalid)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(FlowPalette.dangerSoft)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .padding(.top, 12)
                    }

                    Spacer(minLength: 24)
                }
                .padding(.horizontal, FlowMetrics.pagePadding)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)

            VStack(spacing: 0) {
                Rectangle()
                    .fill(FlowPalette.outline)
                    .frame(height: 1)
                Button(action: start) {
                    Text("开始执行")
                }
                .buttonStyle(FlowPrimaryButtonStyle())
                .disabled(plan.steps.isEmpty)
                .opacity(plan.steps.isEmpty ? 0.45 : 1)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .accessibilityIdentifier("setup-start")
            }
            .background(FlowPalette.surface)
        }
        .background(FlowPalette.background.ignoresSafeArea())
    }

    private var header: some View {
        ZStack {
            Text("准备开始")
                .flowFont(20, weight: .semibold)
                .foregroundColor(FlowPalette.text)
            HStack {
                Button(action: onBack) {
                    Image(systemName: "arrow.left")
                        .flowFont(18, weight: .medium)
                        .foregroundColor(FlowPalette.text)
                        .frame(width: 44, height: 48)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("返回")
                .accessibilityIdentifier("setup-back")
                Spacer()
            }
            .padding(.horizontal, FlowMetrics.pagePadding - 8)
        }
        .frame(height: 48)
    }

    private func start() {
        localError = onStart()
    }
}

private struct SetupStepRow: View {
    let step: RoutineStep
    let index: Int
    let isLast: Bool

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                if index > 0 {
                    Rectangle()
                        .fill(FlowPalette.connector)
                        .frame(width: 1, height: 30)
                        .frame(maxHeight: .infinity, alignment: .top)
                }
                if !isLast {
                    Rectangle()
                        .fill(FlowPalette.connector)
                        .frame(width: 1, height: 30)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                }
                Text("\(index + 1)")
                    .flowFont(13, weight: .semibold)
                    .foregroundColor(FlowPalette.primary)
                    .frame(width: 30, height: 30)
                    .background(FlowPalette.primarySoft)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            .frame(width: 20, height: 60)

            VStack(alignment: .leading, spacing: 2) {
                Text(step.name)
                    .flowFont(16, weight: .medium)
                    .foregroundColor(FlowPalette.text)
                    .lineLimit(1)
                Text(step.type == .count ? "COUNT" : "TIMER")
                    .flowFont(12, weight: .medium)
                    .foregroundColor(step.type == .count ? FlowPalette.primary : FlowPalette.dirty)
            }
            Spacer()
            Text(target)
                .flowFont(14)
                .foregroundColor(FlowPalette.secondaryText)
        }
        .frame(height: 60)
    }

    private var target: String {
        switch step.type {
        case .count:
            return "\(step.target ?? 0) 次"
        case .timer:
            let seconds = (step.durationMs ?? 0) / 1_000
            if seconds > 0 && seconds % 60 == 0 {
                return "\(seconds / 60) 分钟"
            }
            return "\(seconds) 秒"
        }
    }
}
