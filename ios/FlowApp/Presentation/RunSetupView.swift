import SwiftUI

struct RunSetupView: View {
    let plan: Routine
    let message: String?
    let onBack: () -> Void
    /// Returns an error message when the run cannot start, nil on success.
    let onStart: () -> String?
    /// Lets the host switch the status bar to light content while the dark
    /// launch overlay is visible.
    var onLaunchingChanged: (Bool) -> Void = { _ in }
    var onEdit: () -> Void = {}
    var onDelete: () -> Void = {}

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var localError: String?
    @State private var launchPhase: LaunchPhase = .idle
    @State private var launchTask: Task<Void, Never>?
    @State private var startCommitted = false
    @State private var showDeleteConfirmation = false

    fileprivate enum LaunchPhase: Int, Equatable {
        case idle = 0
        case ready
        case three
        case two
        case one

        var fraction: CGFloat {
            switch self {
            case .idle: return 0
            case .ready: return 0.10
            case .three: return 0.40
            case .two: return 0.70
            case .one: return 1
            }
        }

        var label: String {
            switch self {
            case .idle: return ""
            case .ready: return "准备好了吗？"
            case .three: return "3"
            case .two: return "2"
            case .one: return "1"
            }
        }
    }

    var body: some View {
        ZStack {
            FlowLightArt(mode: .prepare)

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(alignment: .lastTextBaseline, spacing: 7) {
                            Text("\(plan.steps.count)")
                                .flowFont(34, weight: .semibold)
                                .foregroundColor(FlowPalette.text)
                            Text("个节点")
                                .flowFont(18, weight: .medium)
                                .foregroundColor(FlowPalette.secondaryText)
                                .padding(.bottom, 2)
                        }
                        .padding(.top, 24)

                        HStack(spacing: 6) {
                            FlowClockIcon(size: 14, color: FlowPalette.secondaryText)
                            Text(flowPlanDurationLabel(steps: plan.steps) ?? "按顺序执行")
                                .flowFont(12.5)
                                .foregroundColor(FlowPalette.secondaryText)
                        }
                        .padding(.top, 8)

                        VStack(spacing: 0) {
                            ForEach(Array(plan.steps.enumerated()), id: \.element.id) { index, step in
                                SetupStepRow(
                                    step: step,
                                    index: index,
                                    isFirst: index == 0,
                                    isLast: index == plan.steps.count - 1
                                )
                            }
                        }
                        .padding(.top, 24)

                        Spacer(minLength: 110)
                    }
                    .padding(.horizontal, FlowMetrics.pagePadding)
                }
                .scrollIndicators(.hidden)

                bottomBar
            }

            if launchPhase != .idle {
                FlowLaunchOverlayView(
                    planName: plan.name,
                    phase: launchPhase,
                    reduceMotion: reduceMotion,
                    onCancel: cancelLaunch
                )
                .transition(.opacity)
                .zIndex(2)
            }
        }
        .onDisappear {
            cancelLaunch()
        }
        .onChange(of: scenePhase) { phase in
            if phase != .active {
                cancelLaunch()
            }
        }
        .alert("删除这个流程？", isPresented: $showDeleteConfirmation) {
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) { onDelete() }
        } message: {
            Text("删除后，这个流程及其节点将无法恢复。")
        }
    }

    private var header: some View {
        ZStack {
            Text(plan.name)
                .flowFont(16, weight: .semibold)
                .foregroundColor(FlowPalette.text)
                .lineLimit(1)
                .truncationMode(.tail)
                .padding(.horizontal, 52)
                .frame(maxWidth: .infinity)

            HStack {
                Button(action: onBack) {
                    FlowBackChevron(size: 18, color: FlowPalette.text)
                        .frame(width: 40, height: 40)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("返回")
                .accessibilityIdentifier("setup-back")

                Spacer()

                Menu {
                    Button("编辑流程") { onEdit() }
                    Button("删除流程", role: .destructive) { showDeleteConfirmation = true }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundColor(FlowPalette.secondaryText)
                        .frame(width: 40, height: 40)
                        .contentShape(Rectangle())
                }
                .accessibilityIdentifier("plan-more")
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 52)
    }

    private var bottomBar: some View {
        VStack(spacing: 10) {
            if let text = localError ?? message {
                Text(text)
                    .flowFont(12)
                    .foregroundColor(FlowPalette.invalid)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(FlowPalette.dangerSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }

            Button(action: beginLaunch) {
                Text("开始执行")
            }
            .buttonStyle(FlowPrimaryButtonStyle())
            .disabled(plan.steps.isEmpty)
            .opacity(plan.steps.isEmpty ? 0.45 : 1)
            .accessibilityIdentifier("setup-start")

            Text("\(plan.steps.count) 个节点 · 按顺序执行")
                .flowFont(10.5)
                .foregroundColor(FlowPalette.secondaryText)
                .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, FlowMetrics.pagePadding)
        .padding(.top, 24)
        .padding(.bottom, 30)
        .background(
            LinearGradient(
                colors: [.clear, FlowPalette.background.opacity(0.94), FlowPalette.background],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    // MARK: - Launch countdown (presentation only)

    private func beginLaunch() {
        guard launchPhase == .idle, !startCommitted, !plan.steps.isEmpty else { return }
        localError = nil
        launchPhase = .ready
        onLaunchingChanged(true)
        FlowHaptics.tick()

        launchTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            guard !Task.isCancelled else { return }
            launchPhase = .three
            FlowHaptics.tick()
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled else { return }
            launchPhase = .two
            FlowHaptics.tick()
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled else { return }
            launchPhase = .one
            FlowHaptics.tick()
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled else { return }
            FlowHaptics.commit()
            finishLaunch()
        }
    }

    private func finishLaunch() {
        guard !startCommitted else { return }
        startCommitted = true
        launchTask?.cancel()
        launchTask = nil
        launchPhase = .idle
        localError = onStart()
        onLaunchingChanged(false)
        if localError != nil {
            startCommitted = false
        }
    }

    private func cancelLaunch() {
        guard launchPhase != .idle else { return }
        launchTask?.cancel()
        launchTask = nil
        launchPhase = .idle
        startCommitted = false
        onLaunchingChanged(false)
    }
}

private struct SetupStepRow: View {
    let step: RoutineStep
    let index: Int
    let isFirst: Bool
    let isLast: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack(alignment: .top) {
                if !isFirst {
                    Rectangle()
                        .fill(FlowPalette.primary.opacity(0.24))
                        .frame(width: 1.5, height: 20)
                        .frame(maxHeight: .infinity, alignment: .top)
                }
                if !isLast {
                    Rectangle()
                        .fill(FlowPalette.primary.opacity(0.24))
                        .frame(width: 1.5, height: 26)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                }
                Text("\(index + 1)")
                    .flowFont(11, weight: .semibold)
                    .foregroundColor(.white)
                    .frame(width: 27, height: 27)
                    .background(FlowPalette.primary)
                    .clipShape(Circle())
            }
            .frame(width: 27, height: 54)

            VStack(alignment: .leading, spacing: 2) {
                Text(step.name)
                    .flowFont(15.5, weight: .semibold)
                    .foregroundColor(FlowPalette.text)
                    .lineLimit(1)
                    .padding(.top, 2)
                HStack(spacing: 5) {
                    FlowTypeGlyph(type: step.type, size: 12)
                    Text(target)
                        .flowFont(12.5)
                        .foregroundColor(FlowPalette.secondaryText)
                }
            }

            Spacer(minLength: 0)
        }
        .frame(height: 54)
    }

    private var target: String {
        if step.type == .count {
            return "\(step.target ?? 0) 次"
        }
        return formatRunDuration(step.durationMs)
    }
}

// MARK: - Launch overlay

private struct FlowLaunchOverlayView: View {
    let planName: String
    let phase: RunSetupView.LaunchPhase
    let reduceMotion: Bool
    let onCancel: () -> Void

    var body: some View {
        ZStack {
            FlowPalette.runBackground.ignoresSafeArea()

            VStack(spacing: 0) {
                Text("即将开始")
                    .flowFont(10, weight: .medium)
                    .tracking(1.8)
                    .foregroundColor(FlowPalette.runMutedText)

                Spacer().frame(height: 20)

                ZStack {
                    Circle()
                        .stroke(FlowPalette.runTrack, lineWidth: 3.5)
                    Circle()
                        .trim(from: 0, to: phase.fraction)
                        .stroke(
                            FlowPalette.primaryBright,
                            style: StrokeStyle(lineWidth: 3.5, lineCap: .round)
                        )
                        .rotationEffect(.degrees(-90))
                        .animation(
                            reduceMotion ? nil : .linear(duration: 0.3),
                            value: phase
                        )

                    Text(phase.label)
                        .flowFont(phase == .ready ? 21 : 72, weight: .semibold)
                        .foregroundColor(FlowPalette.runText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.55)
                        .contentTransition(.opacity)
                        .animation(
                            reduceMotion ? nil : .easeInOut(duration: 0.28),
                            value: phase
                        )
                }
                .frame(width: 200, height: 200)

                Spacer().frame(height: 18)

                Text(planName)
                    .flowFont(13)
                    .foregroundColor(FlowPalette.runSecondaryText)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: 260)
            }
            .padding(.top, 34)

            VStack {
                Spacer()
                Button(action: onCancel) {
                    Text("取消")
                        .flowFont(12, weight: .medium)
                        .foregroundColor(FlowPalette.runMutedText)
                        .padding(.horizontal, 18)
                        .frame(height: 36)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("launch-cancel")
                .padding(.bottom, 38)
            }
        }
    }
}
