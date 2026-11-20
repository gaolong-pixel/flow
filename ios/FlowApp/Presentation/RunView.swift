import Combine
import SwiftUI
import UIKit

struct RunView: View {
    @ObservedObject var coordinator: RunCoordinator
    let snapshot: PlanRunSnapshot

    @Environment(\.scenePhase) private var scenePhase
    @State private var showExitConfirmation = false
    private let ticker = Timer.publish(every: 0.2, on: .main, in: .common).autoconnect()

    private var node: RoutineStep? { snapshot.node }
    private var isTimer: Bool { node?.type == .timer }
    private var progress: Double {
        guard snapshot.totalNodes > 0 else { return 0 }
        return Double(snapshot.completedNodes) / Double(snapshot.totalNodes)
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            progressSection
                .padding(.horizontal, FlowMetrics.pagePadding)
                .padding(.top, 10)
                .accessibilityIdentifier("run-progress")

            VStack(spacing: 0) {
                if let node { FlowTypeBadge(type: node.type) }

                Text(node?.name ?? "运行结束")
                    .font(.system(size: isTimer ? 24 : 26, weight: .semibold))
                    .foregroundColor(FlowPalette.text)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .padding(.top, 18)

                if isTimer {
                    Text(formatRunCountdown(snapshot.remainingMs))
                        .flowMonoFont(76, weight: .semibold)
                        .monospacedDigit()
                        .foregroundColor(timerColor)
                        .padding(.top, 20)
                        .accessibilityIdentifier("run-countdown")
                    Text(timerStatusText)
                        .flowFont(15)
                        .foregroundColor(timerColor)
                        .padding(.top, 10)
                    timerProgress
                        .padding(.top, 18)
                } else {
                    HStack(alignment: .bottom, spacing: 6) {
                        Text("\(node?.target ?? 0)")
                            .flowFont(84, weight: .semibold)
                            .foregroundColor(FlowPalette.text)
                        Text("次")
                            .flowFont(20)
                            .foregroundColor(FlowPalette.secondaryText)
                            .padding(.bottom, 14)
                    }
                    .padding(.top, 20)
                    Text("完成目标后点击下方按钮")
                        .flowFont(13)
                        .foregroundColor(FlowPalette.secondaryText)
                        .padding(.top, 8)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, FlowMetrics.pagePadding)

            loopRow
                .padding(.horizontal, FlowMetrics.pagePadding)
                .padding(.bottom, 6)

            actions
                .frame(height: 56)
                .padding(.horizontal, FlowMetrics.pagePadding)

            Text("返回会询问是否退出，运行进度不会保存。")
                .flowFont(11)
                .foregroundColor(FlowPalette.secondaryText)
                .padding(.top, 8)
                .padding(.bottom, 8)
        }
        .background(FlowPalette.background.ignoresSafeArea())
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
        HStack(spacing: 4) {
            Button {
                showExitConfirmation = true
            } label: {
                Image(systemName: "xmark")
                    .flowFont(16, weight: .medium)
                    .foregroundColor(FlowPalette.secondaryText)
                    .frame(width: 44, height: 48)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("退出")
            .accessibilityIdentifier("run-exit")

            Text(snapshot.plan.name)
                .flowFont(13)
                .foregroundColor(FlowPalette.secondaryText)
                .lineLimit(1)
            Spacer()
        }
        .padding(.horizontal, FlowMetrics.pagePadding - 8)
        .frame(height: 48)
    }

    private var progressSection: some View {
        VStack(spacing: 8) {
            HStack {
                Text("STEP \(String(format: "%02d", snapshot.nodeIndex + 1))")
                    .flowFont(13)
                    .foregroundColor(FlowPalette.secondaryText)
                Spacer()
                Text("\(snapshot.nodeIndex + 1) / \(snapshot.totalNodes)")
                    .flowFont(13)
                    .foregroundColor(FlowPalette.secondaryText)
            }
            FlowProgressBar(progress: progress)
        }
    }

    private var loopRow: some View {
        HStack {
            Text("单节点循环")
                .flowFont(14)
                .foregroundColor(FlowPalette.secondaryText)
            Spacer()
            Toggle("", isOn: Binding(
                get: { snapshot.loopCurrentNode },
                set: { value in _ = coordinator.setLoop(value) }
            ))
            .labelsHidden()
            .tint(FlowPalette.primary)
            .accessibilityIdentifier("run-loop-toggle")
        }
    }

    @ViewBuilder
    private var actions: some View {
        if !isTimer && snapshot.nodeState == .ready {
            Button("开始") { coordinator.startNode() }
                .buttonStyle(FlowPrimaryButtonStyle(height: 56))
                .accessibilityIdentifier("run-primary")
        } else if !isTimer && snapshot.nodeState == .running {
            Button("完成当前节点") { coordinator.completeNode() }
                .buttonStyle(FlowPrimaryButtonStyle(height: 56))
                .accessibilityIdentifier("run-primary")
        } else if isTimer && snapshot.nodeState == .ready {
            Button("开始计时") { coordinator.startNode() }
                .buttonStyle(FlowPrimaryButtonStyle(height: 56))
                .accessibilityIdentifier("run-primary")
        } else if snapshot.nodeState == .running {
            HStack(spacing: 10) {
                Button("暂停") { coordinator.pause() }
                    .buttonStyle(FlowPrimaryButtonStyle(height: 56))
                    .accessibilityIdentifier("run-primary")
                Button("完成当前节点") { coordinator.completeNode() }
                    .buttonStyle(FlowSecondaryButtonStyle(height: 48))
                    .accessibilityIdentifier("run-secondary")
            }
        } else if snapshot.nodeState == .paused {
            HStack(spacing: 10) {
                Button("继续计时") { coordinator.resume() }
                    .buttonStyle(FlowPrimaryButtonStyle(height: 56))
                    .accessibilityIdentifier("run-primary")
                Button("完成当前节点") { coordinator.completeNode() }
                    .buttonStyle(FlowSecondaryButtonStyle(height: 48))
                    .accessibilityIdentifier("run-secondary")
            }
        } else if snapshot.nodeState == .expired {
            Button("完成当前节点") { coordinator.completeNode() }
                .buttonStyle(FlowPrimaryButtonStyle(height: 56))
                .accessibilityIdentifier("run-primary")
        }
    }

    private var timerProgress: some View {
        let total = snapshot.totalTimerMs ?? 0
        let remaining = min(max(snapshot.remainingMs ?? total, 0), max(total, 0))
        let fraction = total > 0 ? Double(total - remaining) / Double(total) : 0
        return GeometryReader { geometry in
            let barWidth = geometry.size.width * 0.7
            ZStack(alignment: .leading) {
                Capsule().fill(FlowPalette.outline)
                Capsule()
                    .fill(FlowPalette.primary)
                    .frame(width: barWidth * min(max(fraction, 0), 1))
            }
            .frame(width: barWidth, height: 5)
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .frame(height: 5)
    }

    private var timerColor: Color {
        switch snapshot.nodeState {
        case .ready: return FlowPalette.text
        case .running: return FlowPalette.primary
        case .paused: return FlowPalette.dirty
        case .expired: return FlowPalette.invalid
        }
    }

    private var timerStatusText: String {
        switch snapshot.nodeState {
        case .ready: return "准备开始"
        case .running: return "正在计时"
        case .paused: return "已暂停"
        case .expired: return "时间到"
        }
    }
}
