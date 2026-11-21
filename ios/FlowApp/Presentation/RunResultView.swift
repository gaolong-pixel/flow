import SwiftUI

struct RunResultView: View {
    let summary: RunSummary
    let canRunAgain: Bool
    let onRunAgain: () -> Void
    let onBackToPlans: () -> Void

    var body: some View {
        ResultScaffold(
            summary: summary,
            completedNormally: true,
            canRunAgain: canRunAgain,
            onRunAgain: onRunAgain,
            onBackToPlans: onBackToPlans
        )
    }
}

struct RunExitResultView: View {
    let summary: RunSummary
    let canRunAgain: Bool
    let onRunAgain: () -> Void
    let onBackToPlans: () -> Void

    var body: some View {
        ResultScaffold(
            summary: summary,
            completedNormally: false,
            canRunAgain: canRunAgain,
            onRunAgain: onRunAgain,
            onBackToPlans: onBackToPlans
        )
    }
}

private struct ResultScaffold: View {
    let summary: RunSummary
    let completedNormally: Bool
    let canRunAgain: Bool
    let onRunAgain: () -> Void
    let onBackToPlans: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var confettiProgress: Double = 0

    private var celebrate: Bool {
        completedNormally
    }

    private var title: String {
        completedNormally ? "执行完成！" : "已退出流程"
    }

    private var subtitle: String {
        completedNormally ? "太棒了！继续保持！" : "当前节点未计入完成数量"
    }

    private var titleIdentifier: String {
        completedNormally ? "result-title" : "exit-result-title"
    }

    private var progressText: String {
        if completedNormally {
            return "\(summary.completedNodes) / \(summary.totalNodes) 个节点"
        }
        return "已完成 \(summary.completedNodes) / \(summary.totalNodes) 个节点"
    }

    var body: some View {
        ZStack {
            FlowLightArt(mode: completedNormally ? .done : .exit)

            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 0) {
                        ZStack {
                            if celebrate {
                                FlowConfettiView(progress: confettiProgress)
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                            }
                            FlowResultEmblemView(
                                completedNormally: completedNormally,
                                animationKey: summary.runId,
                                reduceMotion: reduceMotion
                            )
                        }
                        .frame(height: 250)
                        .padding(.top, 28)

                        Text(title)
                            .flowFont(24, weight: .semibold)
                            .foregroundColor(FlowPalette.text)
                            .multilineTextAlignment(.center)
                            .padding(.top, 8)
                            .accessibilityIdentifier(titleIdentifier)

                        Text(subtitle)
                            .flowFont(13)
                            .foregroundColor(FlowPalette.secondaryText)
                            .multilineTextAlignment(.center)
                            .padding(.top, 7)

                        HStack(spacing: 7) {
                            Text(progressText)
                                .flowFont(12.5, weight: .semibold)
                                .foregroundColor(completedNormally ? FlowPalette.text : FlowPalette.secondaryInk)
                                .accessibilityIdentifier("result-progress")
                            Circle()
                                .fill(FlowPalette.outlineStrong)
                                .frame(width: 3, height: 3)
                            Text("用时 \(formatRunElapsed(summary.elapsedMs))")
                                .flowFont(12)
                                .foregroundColor(FlowPalette.secondaryText)
                                .accessibilityIdentifier("result-duration")
                        }
                        .padding(.top, 14)

                        Spacer(minLength: 40)
                    }
                    .padding(.horizontal, FlowMetrics.pagePadding)
                }
                .scrollIndicators(.hidden)

                actionButtons
                    .padding(.horizontal, FlowMetrics.pagePadding)
                    .padding(.top, 8)
                    .padding(.bottom, 30)
            }
        }
        .task {
            if celebrate && !reduceMotion {
                withAnimation(.easeOut(duration: 1.1)) {
                    confettiProgress = 1
                }
                try? await Task.sleep(nanoseconds: 380_000_000)
                FlowHaptics.commit()
            } else {
                confettiProgress = 1
                if !reduceMotion {
                    try? await Task.sleep(nanoseconds: 320_000_000)
                    FlowHaptics.tick()
                }
            }
        }
    }

    @ViewBuilder
    private var actionButtons: some View {
        if completedNormally {
            VStack(spacing: 10) {
                if canRunAgain {
                    Button("再次执行", action: onRunAgain)
                        .buttonStyle(FlowPrimaryButtonStyle())
                        .accessibilityIdentifier("result-again")
                }
                if canRunAgain {
                    Button("返回首页", action: onBackToPlans)
                        .buttonStyle(FlowSecondaryButtonStyle(height: 52))
                        .accessibilityIdentifier("result-back")
                } else {
                    Button("返回首页", action: onBackToPlans)
                        .buttonStyle(FlowPrimaryButtonStyle())
                        .accessibilityIdentifier("result-back")
                }
            }
        } else {
            VStack(spacing: 10) {
                Button("返回首页", action: onBackToPlans)
                    .buttonStyle(FlowPrimaryButtonStyle())
                    .accessibilityIdentifier("exit-result-home")
                if canRunAgain {
                    Button("再次执行", action: onRunAgain)
                        .buttonStyle(FlowSecondaryButtonStyle(height: 52))
                        .accessibilityIdentifier("exit-result-again")
                }
            }
        }
    }
}

private struct FlowResultEmblemView: View {
    let completedNormally: Bool
    let animationKey: String
    let reduceMotion: Bool

    @State private var appeared = false

    var body: some View {
        ZStack {
            if completedNormally {
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [FlowPalette.primary.opacity(0.40), .clear],
                            center: .center,
                            startRadius: 0,
                            endRadius: 76
                        )
                    )
                    .frame(width: 152, height: 152)
                    .blur(radius: 10)
            }

            Circle()
                .fill(
                    completedNormally
                        ? AnyShapeStyle(
                            RadialGradient(
                                colors: [
                                    Color(red: 0.57, green: 0.73, blue: 1.0),
                                    Color(red: 0.31, green: 0.55, blue: 1.0),
                                    Color(red: 0.18, green: 0.42, blue: 1.0)
                                ],
                                center: UnitPoint(x: 0.34, y: 0.28),
                                startRadius: 2,
                                endRadius: 84
                            )
                        )
                        : AnyShapeStyle(
                            RadialGradient(
                                colors: [Color(red: 0.97, green: 0.98, blue: 1.0), Color(red: 0.91, green: 0.93, blue: 0.96)],
                                center: UnitPoint(x: 0.35, y: 0.3),
                                startRadius: 2,
                                endRadius: 84
                            )
                        )
                )
                .frame(width: 104, height: 104)
                .shadow(
                    color: completedNormally ? FlowPalette.primary.opacity(0.32) : Color.black.opacity(0.08),
                    radius: 18,
                    x: 0,
                    y: 10
                )

            if completedNormally {
                Image(systemName: "checkmark")
                    .font(.system(size: 44, weight: .semibold))
                    .foregroundColor(.white)
            } else {
                Capsule()
                    .fill(Color(red: 0.39, green: 0.45, blue: 0.55))
                    .frame(width: 32, height: 4)
            }
        }
        .scaleEffect(appeared ? 1 : 0.86)
        .opacity(appeared ? 1 : 0)
        .task(id: animationKey) {
            if reduceMotion {
                appeared = true
            } else {
                withAnimation(.spring(response: 0.52, dampingFraction: 0.72)) {
                    appeared = true
                }
            }
        }
    }
}
