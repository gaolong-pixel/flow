import Combine
import SwiftUI
import UIKit

struct RunView: View {
    @ObservedObject var coordinator: RunCoordinator
    let snapshot: PlanRunSnapshot

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showExitConfirmation = false
    private let ticker = Timer.publish(every: 0.2, on: .main, in: .common).autoconnect()

    private var node: RoutineStep? { snapshot.node }
    private var isTimer: Bool { node?.type == .timer }
    private var overallProgress: Double {
        flowOverallProgress(completed: snapshot.completedNodes, total: snapshot.totalNodes)
    }
    private var overallPercent: Int {
        flowOverallPercent(completed: snapshot.completedNodes, total: snapshot.totalNodes)
    }

    var body: some View {
        ZStack {
            FlowDarkArt()
            VStack(spacing: 0) {
                topBar
                progressHeader
                    .padding(.horizontal, FlowMetrics.pagePadding)
                    .padding(.top, 2)
                    .accessibilityIdentifier("run-progress")

                stage
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                loopRow
                actions
                Spacer().frame(height: 30)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            coordinator.refresh()
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .onReceive(ticker) { _ in
            if scenePhase == .active {
                coordinator.refresh()
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                coordinator.onSceneBecameActive()
            }
        }
        .alert("退出本次运行？", isPresented: $showExitConfirmation) {
            Button("继续运行", role: .cancel) {}
            Button("退出运行", role: .destructive) {
                _ = coordinator.exitCurrent()
            }
        } message: {
            Text("当前进度：已完成 \(snapshot.completedNodes) / \(snapshot.totalNodes) 个节点。退出后当前节点状态、计时和完成进度都会丢弃，不会保存。")
        }
    }

    private var topBar: some View {
        ZStack {
            VStack(spacing: 2) {
                Text("\(snapshot.nodeIndex + 1) / \(snapshot.totalNodes)")
                    .flowFont(10.5)
                    .foregroundColor(FlowPalette.runMutedText)
                Text(node?.name ?? "")
                    .flowFont(17, weight: .semibold)
                    .foregroundColor(FlowPalette.runText)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .padding(.horizontal, 52)
            }
            .frame(maxWidth: .infinity)

            HStack {
                Button {
                    showExitConfirmation = true
                } label: {
                    FlowBackChevron(size: 18, color: FlowPalette.runSecondaryText)
                        .frame(width: 40, height: 40)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("退出")
                .accessibilityIdentifier("run-exit")

                Spacer()
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 72)
    }

    private var progressHeader: some View {
        VStack(spacing: 7) {
            HStack {
                Text("整体进度")
                    .flowFont(9.5)
                    .foregroundColor(FlowPalette.runMutedText)
                Spacer()
                Text("\(overallPercent)%")
                    .flowFont(10.5, weight: .medium)
                    .monospacedDigit()
                    .foregroundColor(FlowPalette.runSecondaryText)
            }
            FlowRunProgressBar(progress: overallProgress)
        }
        .padding(.bottom, 6)
    }

    @ViewBuilder
    private var stage: some View {
        if let node {
            GeometryReader { geometry in
                let diameter = min(
                    max(188, min(geometry.size.width - 8, geometry.size.height - 6)),
                    340
                )
                ZStack {
                    if isTimer {
                        FlowTimerRingView(
                            diameter: diameter,
                            overallFraction: overallProgress,
                            remainingFraction: flowTimerRemainingFraction(
                                totalMs: snapshot.totalTimerMs,
                                remainingMs: snapshot.remainingMs
                            ),
                            countdown: formatRunCountdown(snapshot.remainingMs),
                            state: snapshot.nodeState,
                            reduceMotion: reduceMotion
                        )
                    } else {
                        FlowCountRingView(
                            diameter: diameter,
                            target: node.target ?? 0,
                            state: snapshot.nodeState,
                            pulseKey: snapshot.nodeState,
                            reduceMotion: reduceMotion
                        )
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .padding(.horizontal, FlowMetrics.pagePadding)
        }
    }

    private var loopRow: some View {
        HStack(spacing: 8) {
            Text("单节点循环")
                .flowFont(10.5)
                .foregroundColor(FlowPalette.runSecondaryText)
            Toggle("", isOn: Binding(
                get: { snapshot.loopCurrentNode },
                set: { value in _ = coordinator.setLoop(value) }
            ))
            .labelsHidden()
            .tint(FlowPalette.primary)
            .scaleEffect(0.72)
            .frame(width: 38, height: 24)
            .accessibilityIdentifier("run-loop-toggle")
        }
        .padding(.leading, 14)
        .padding(.trailing, 6)
        .padding(.vertical, 4)
        .background(Color.white.opacity(0.04))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.white.opacity(0.06), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.top, 10)
        .padding(.bottom, 12)
    }

    @ViewBuilder
    private var actions: some View {
        let state = snapshot.nodeState
        HStack(alignment: .top, spacing: 34) {
            if !isTimer && state == .ready {
                RunCircleAction(
                    icon: "play.fill",
                    label: "开始",
                    accessibilityLabel: "开始",
                    primary: true,
                    identifier: "run-primary"
                ) { FlowHaptics.tick(); coordinator.startNode() }
                RunCircleAction(
                    icon: "xmark",
                    label: "退出",
                    accessibilityLabel: "退出",
                    primary: false,
                    identifier: "run-cancel"
                ) { showExitConfirmation = true }
            } else if !isTimer && state == .running {
                RunCircleAction(
                    icon: "checkmark",
                    label: "完成",
                    accessibilityLabel: "完成当前节点",
                    primary: true,
                    identifier: "run-primary"
                ) { FlowHaptics.commit(); coordinator.completeNode() }
                RunCircleAction(
                    icon: "xmark",
                    label: "退出",
                    accessibilityLabel: "退出",
                    primary: false,
                    identifier: "run-cancel"
                ) { showExitConfirmation = true }
            } else if isTimer && state == .ready {
                RunCircleAction(
                    icon: "play.fill",
                    label: "开始",
                    accessibilityLabel: "开始计时",
                    primary: true,
                    identifier: "run-primary"
                ) { FlowHaptics.tick(); coordinator.startNode() }
                RunCircleAction(
                    icon: "xmark",
                    label: "退出",
                    accessibilityLabel: "退出",
                    primary: false,
                    identifier: "run-cancel"
                ) { showExitConfirmation = true }
            } else if isTimer && state == .running {
                RunCircleAction(
                    icon: "pause.fill",
                    label: "暂停",
                    accessibilityLabel: "暂停",
                    primary: false,
                    identifier: "run-primary"
                ) { FlowHaptics.tick(); coordinator.pause() }
                RunCircleAction(
                    icon: "checkmark",
                    label: "完成",
                    accessibilityLabel: "完成当前节点",
                    primary: false,
                    identifier: "run-secondary"
                ) { FlowHaptics.commit(); coordinator.completeNode() }
                RunCircleAction(
                    icon: "xmark",
                    label: "退出",
                    accessibilityLabel: "退出",
                    primary: false,
                    identifier: "run-cancel"
                ) { showExitConfirmation = true }
            } else if isTimer && state == .paused {
                RunCircleAction(
                    icon: "play.fill",
                    label: "继续",
                    accessibilityLabel: "继续计时",
                    primary: true,
                    identifier: "run-primary"
                ) { FlowHaptics.tick(); coordinator.resume() }
                RunCircleAction(
                    icon: "checkmark",
                    label: "完成",
                    accessibilityLabel: "完成当前节点",
                    primary: false,
                    identifier: "run-secondary"
                ) { FlowHaptics.commit(); coordinator.completeNode() }
                RunCircleAction(
                    icon: "xmark",
                    label: "退出",
                    accessibilityLabel: "退出",
                    primary: false,
                    identifier: "run-cancel"
                ) { showExitConfirmation = true }
            } else if isTimer && state == .expired {
                RunCircleAction(
                    icon: "checkmark",
                    label: "完成",
                    accessibilityLabel: "完成当前节点",
                    primary: true,
                    identifier: "run-primary"
                ) { FlowHaptics.commit(); coordinator.completeNode() }
                RunCircleAction(
                    icon: "xmark",
                    label: "退出",
                    accessibilityLabel: "退出",
                    primary: false,
                    identifier: "run-cancel"
                ) { showExitConfirmation = true }
            }
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }
}

private struct RunCircleAction: View {
    let icon: String
    let label: String
    let accessibilityLabel: String
    let primary: Bool
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 7) {
                ZStack {
                    if primary {
                        Circle()
                            .fill(FlowPalette.primary.opacity(0.10))
                            .frame(width: 78, height: 78)
                    }
                    Circle()
                        .fill(
                            primary
                                ? AnyShapeStyle(LinearGradient(
                                    colors: [Color(red: 0.31, green: 0.55, blue: 1.0), Color(red: 0.18, green: 0.42, blue: 1.0)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ))
                                : AnyShapeStyle(Color.white.opacity(0.06))
                        )
                        .frame(width: primary ? 66 : 58, height: primary ? 66 : 58)
                        .overlay(
                            Circle()
                                .stroke(
                                    primary ? Color.clear : Color.white.opacity(0.12),
                                    lineWidth: 1
                                )
                        )
                        .shadow(color: primary ? FlowPalette.primary.opacity(0.30) : .clear, radius: 12, x: 0, y: 8)
                    Image(systemName: icon)
                        .font(.system(size: 21, weight: .medium))
                        .foregroundColor(Color.white.opacity(primary ? 0.98 : 0.82))
                }
                Text(label)
                    .flowFont(10.5)
                    .foregroundColor(primary ? Color.white.opacity(0.78) : FlowPalette.runSecondaryText)
            }
            .frame(width: 78)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
        .accessibilityLabel(accessibilityLabel)
    }
}

// MARK: - TIMER ring

private struct FlowTimerRingView: View {
    let diameter: CGFloat
    let overallFraction: Double
    let remainingFraction: Double
    let countdown: String
    let state: NodeRunState
    let reduceMotion: Bool

    @State private var glowPulse = false

    private var targetRemaining: Double {
        state == .ready ? 1 : min(max(remainingFraction, 0), 1)
    }

    private var status: String {
        switch state {
        case .ready: return "准备开始"
        case .running: return "剩余时间"
        case .paused: return "已暂停"
        case .expired: return "时间到"
        }
    }

    private var statusColor: Color {
        switch state {
        case .ready: return FlowPalette.runSecondaryText
        case .running: return FlowPalette.primaryBright
        case .paused: return FlowPalette.warm
        case .expired: return FlowPalette.runDanger
        }
    }

    private var timeSize: CGFloat {
        flowTimerNumberSize(characters: countdown.count)
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(
                    RadialGradient(
                        colors: [FlowPalette.primary.opacity(glowPulse ? 0.26 : 0.18), .clear],
                        center: .center,
                        startRadius: 0,
                        endRadius: diameter * 0.58
                    )
                )
                .frame(width: diameter * 1.08, height: diameter * 1.08)
                .blur(radius: 18)

            Circle()
                .stroke(Color.white.opacity(0.06), lineWidth: 2)

            Circle()
                .trim(from: 0, to: CGFloat(min(max(overallFraction, 0), 1)))
                .stroke(
                    FlowPalette.success.opacity(0.65),
                    style: StrokeStyle(lineWidth: 2, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
                .animation(reduceMotion ? nil : .easeOut(duration: 0.42), value: overallFraction)

            Circle()
                .stroke(Color.white.opacity(0.09), lineWidth: 10)
                .padding(26)

            Circle()
                .trim(from: 0, to: CGFloat(targetRemaining))
                .stroke(
                    LinearGradient(
                        colors: [
                            Color(red: 0.66, green: 0.78, blue: 1.0),
                            Color(red: 0.36, green: 0.55, blue: 1.0),
                            Color(red: 0.18, green: 0.42, blue: 1.0)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    style: StrokeStyle(lineWidth: 10, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
                .padding(26)
                .shadow(color: FlowPalette.primaryBright.opacity(glowPulse ? 0.42 : 0.26), radius: 14, x: 0, y: 0)
                .animation(reduceMotion ? nil : .linear(duration: 0.2), value: targetRemaining)

            VStack(spacing: 7) {
                HStack(spacing: 6) {
                    Circle().fill(statusColor).frame(width: 4, height: 4)
                    Text(status)
                        .flowFont(10, weight: .medium)
                        .tracking(0.5)
                        .foregroundColor(statusColor)
                }
                Text(countdown)
                    .flowMonoFont(timeSize, weight: .semibold)
                    .monospacedDigit()
                    .foregroundColor(FlowPalette.runText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.55)
                    .accessibilityIdentifier("run-countdown")
            }
            .frame(width: diameter * 0.62)
        }
        .frame(width: diameter, height: diameter)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 2.6).repeatForever(autoreverses: true)) {
                glowPulse = true
            }
        }
    }
}

// MARK: - COUNT ring

private struct FlowCountRingView: View {
    let diameter: CGFloat
    let target: Int
    let state: NodeRunState
    let pulseKey: NodeRunState
    let reduceMotion: Bool

    @State private var ringScale: CGFloat = 1
    @State private var scanAngle: Double = 0

    private var isRunning: Bool { state == .running }
    private var status: String { isRunning ? "进行中" : "准备开始" }
    private var statusColor: Color {
        isRunning ? FlowPalette.primaryBright : FlowPalette.runSecondaryText
    }

    private var numberSize: CGFloat {
        switch String(target).count {
        case ...2: return 72
        case 3: return 64
        case 4: return 54
        default: return 44
        }
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(
                    RadialGradient(
                        colors: [FlowPalette.primary.opacity(0.20), .clear],
                        center: .center,
                        startRadius: 0,
                        endRadius: diameter * 0.6
                    )
                )
                .frame(width: diameter * 1.12, height: diameter * 1.12)
                .blur(radius: 18)

            Circle()
                .stroke(Color.white.opacity(0.075), lineWidth: 9)
                .padding(22)

            Circle()
                .stroke(
                    LinearGradient(
                        colors: [FlowPalette.primaryBright.opacity(0.10), FlowPalette.primary.opacity(0.02)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 9
                )
                .padding(22)

            Circle()
                .trim(from: 0, to: 58.0 / 360.0)
                .stroke(
                    LinearGradient(
                        colors: [
                            Color(red: 0.66, green: 0.78, blue: 1.0),
                            Color(red: 0.36, green: 0.55, blue: 1.0),
                            Color(red: 0.18, green: 0.42, blue: 1.0)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    style: StrokeStyle(lineWidth: 9, lineCap: .round)
                )
                .rotationEffect(.degrees(-90 + scanAngle))
                .padding(22)
                .shadow(color: FlowPalette.primaryBright.opacity(0.35), radius: 10, x: 0, y: 0)

            VStack(spacing: 6) {
                Text("目标次数")
                    .flowFont(9.5, weight: .medium)
                    .tracking(1.4)
                    .foregroundColor(FlowPalette.runMutedText)

                HStack(alignment: .bottom, spacing: 5) {
                    Text("\(target)")
                        .flowFont(numberSize, weight: .semibold)
                        .foregroundColor(FlowPalette.runText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                    Text("次")
                        .flowFont(15)
                        .foregroundColor(FlowPalette.runSecondaryText)
                        .padding(.bottom, numberSize * 0.12)
                }

                HStack(spacing: 6) {
                    Circle().fill(statusColor).frame(width: 4, height: 4)
                    Text(status)
                        .flowFont(10, weight: .medium)
                        .tracking(0.5)
                        .foregroundColor(statusColor)
                }
            }
            .frame(width: diameter * 0.64)
        }
        .frame(width: diameter, height: diameter)
        .scaleEffect(ringScale)
        .onAppear {
            if !reduceMotion {
                withAnimation(.linear(duration: 18).repeatForever(autoreverses: false)) {
                    scanAngle = 360
                }
            }
        }
        .task(id: pulseKey) {
            guard !reduceMotion else { return }
            withAnimation(.spring(response: 0.22, dampingFraction: 0.55)) {
                ringScale = 1.01
            }
            try? await Task.sleep(nanoseconds: 150_000_000)
            withAnimation(.easeOut(duration: 0.26)) {
                ringScale = 1
            }
        }
    }
}
