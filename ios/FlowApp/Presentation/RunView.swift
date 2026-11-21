import Combine
import SwiftUI
import UIKit

struct RunView: View {
    @ObservedObject var coordinator: RunCoordinator
    let snapshot: PlanRunSnapshot

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showExitConfirmation = false
    @State private var completedExpanded = false
    @State private var shakeX: CGFloat = 0
    @State private var expiryPulse: Double = 0
    @State private var completionFlash: Double = 0
    @State private var lastCompletedNodes: Int
    private let ticker = Timer.publish(every: 0.2, on: .main, in: .common).autoconnect()

    init(coordinator: RunCoordinator, snapshot: PlanRunSnapshot) {
        _coordinator = ObservedObject(wrappedValue: coordinator)
        self.snapshot = snapshot
        _lastCompletedNodes = State(initialValue: snapshot.completedNodes)
    }

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
                header
                completedDrawer
                roller
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                bottomDock
            }
            CompletionFlashView(progress: completionFlash)
                .allowsHitTesting(false)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            coordinator.refresh()
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            shakeX = 0
            expiryPulse = 0
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
        .onChange(of: snapshot.completedNodes) { value in
            guard value > lastCompletedNodes else {
                lastCompletedNodes = value
                return
            }
            lastCompletedNodes = value
            playCompletionFeedback()
        }
        .task(id: coordinator.expiryFeedbackToken) {
            guard let token = coordinator.expiryFeedbackToken else { return }
            defer { coordinator.consumeExpiryFeedback(token) }
            // The token and the Active snapshot are published from the same
            // coordinator call. Give SwiftUI one frame to deliver the snapshot
            // before reading the authoritative controller state.
            try? await Task.sleep(nanoseconds: 60_000_000)
            guard let current = coordinator.currentSnapshot,
                  current.timerRunId == token,
                  current.nodeState == .expired else {
                return
            }
            await playExpiryFeedback()
        }
        .alert("退出本次运行？", isPresented: $showExitConfirmation) {
            Button("继续运行", role: .cancel) {}
            Button("退出运行", role: .destructive) {
                _ = coordinator.exitCurrent()
            }
        } message: {
            Text(exitMessage)
        }
    }

    private var exitMessage: String {
        "当前进度：已完成 \(snapshot.completedNodes) / \(snapshot.totalNodes) 个节点。退出后当前节点状态、计时和完成进度都会丢弃，不会保存。"
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button {
                    showExitConfirmation = true
                } label: {
                    ZStack {
                        Circle()
                            .fill(Color.white.opacity(0.05))
                            .overlay(
                                Circle().stroke(Color.white.opacity(0.07), lineWidth: 1)
                            )
                        FlowBackChevron(size: 17, color: FlowPalette.runSecondaryText)
                    }
                    .frame(width: 40, height: 40)
                    .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("退出")
                .accessibilityIdentifier("run-exit")

                VStack(alignment: .leading, spacing: 2) {
                    Text(snapshot.plan.name)
                        .flowFont(16.5, weight: .semibold)
                        .foregroundColor(FlowPalette.runText)
                        .lineLimit(1)
                    Text("当前 · \(node?.name ?? "运行结束")")
                        .flowFont(10.5)
                        .foregroundColor(FlowPalette.runSecondaryText)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Text("\(snapshot.nodeIndex + 1) / \(snapshot.totalNodes)")
                    .flowFont(10.5, weight: .medium)
                    .foregroundColor(FlowPalette.runSecondaryText)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.white.opacity(0.05))
                    .overlay(
                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                            .stroke(Color.white.opacity(0.07), lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                    .accessibilityIdentifier("run-position")
            }

            HStack {
                Text("流程进度")
                    .flowFont(9.5)
                    .tracking(0.4)
                    .foregroundColor(FlowPalette.runMutedText)
                Spacer()
                Text("\(snapshot.completedNodes) / \(snapshot.totalNodes) · \(overallPercent)%")
                    .flowFont(10, weight: .medium)
                    .foregroundColor(FlowPalette.runSecondaryText)
            }
            .padding(.top, 12)

            FlowRunProgressBar(progress: overallProgress)
                .padding(.top, 7)
        }
        .padding(.horizontal, FlowMetrics.pagePadding)
        .padding(.bottom, 10)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("run-progress")
        .accessibilityLabel("整体进度，已完成 \(overallPercent)%")
    }

    // MARK: - Completed / skipped archive

    private var archiveContentHeight: CGFloat {
        let count = snapshot.completedNodeIndices.count
        if count == 0 { return 54 }
        return min(148, 24 + CGFloat(count) * 34 + CGFloat(max(0, count - 1)) * 8)
    }

    private var completedDrawer: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.18)) {
                    completedExpanded.toggle()
                }
            } label: {
                HStack(spacing: 8) {
                    Circle()
                        .fill(
                            snapshot.completedNodeIndices.isEmpty
                                ? FlowPalette.runMutedText
                                : FlowPalette.success
                        )
                        .frame(width: 6, height: 6)
                    Text("已完成 \(snapshot.completedNodeIndices.count)")
                        .flowFont(11, weight: .medium)
                        .foregroundColor(
                            snapshot.completedNodeIndices.isEmpty
                                ? FlowPalette.runSecondaryText
                                : FlowPalette.runText
                        )
                    Spacer()
                    Text(completedExpanded ? "收起" : "展开")
                        .flowFont(10)
                        .foregroundColor(FlowPalette.runMutedText)
                    FlowChevron(size: 11, color: FlowPalette.runMutedText)
                        .rotationEffect(.degrees(completedExpanded ? 90 : 0))
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(Color.white.opacity(0.035))
                .overlay(
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .stroke(Color.white.opacity(0.065), lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("run-completed-toggle")
            .accessibilityLabel(completedExpanded ? "收起已完成节点" : "展开已完成节点")

            if completedExpanded {
                ScrollView {
                    VStack(spacing: 8) {
                        if snapshot.completedNodeIndices.isEmpty {
                            Text("还没有已完成节点。完成当前节点后会收进这里。")
                            .flowFont(10.5)
                            .foregroundColor(FlowPalette.runMutedText)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        ForEach(snapshot.completedNodeIndices, id: \.self) { index in
                            if let step = snapshot.plan.steps[safe: index] {
                                archivedRow(step: step, index: index)
                            }
                        }
                    }
                    .padding(12)
                }
                .frame(height: archiveContentHeight)
                .background(FlowPalette.runSurface.opacity(0.62))
                .overlay(
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .stroke(Color.white.opacity(0.05), lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                .padding(.top, 7)
                .transition(.opacity.combined(with: .move(edge: .top)))
                .accessibilityIdentifier("run-completed-list")
            }
        }
        .padding(.horizontal, FlowMetrics.pagePadding)
        .padding(.bottom, 10)
    }

    private func archivedRow(step: RoutineStep, index: Int) -> some View {
        HStack(spacing: 9) {
            ZStack {
                Circle().fill(FlowPalette.success.opacity(0.14))
                Image(systemName: "checkmark")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundColor(FlowPalette.success)
            }
            .frame(width: 18, height: 18)

            VStack(alignment: .leading, spacing: 1) {
                Text(step.name)
                    .flowFont(11.5, weight: .medium)
                    .foregroundColor(FlowPalette.runText)
                    .lineLimit(1)
                Text(compactMeta(step: step, remainingMs: nil))
                    .flowFont(9.5)
                    .foregroundColor(FlowPalette.runMutedText)
                    .lineLimit(1)
            }
            Spacer()
            Text("已完成")
                .flowFont(9.5)
                .foregroundColor(FlowPalette.success)
        }
        .accessibilityIdentifier("run-archived-\(index)")
    }

    // MARK: - Roller

    private var roller: some View {
        ScrollViewReader { proxy in
            ZStack {
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVStack(spacing: 12) {
                        ForEach(snapshot.visibleNodeIndices, id: \.self) { index in
                            rollerCard(index)
                                .id(index)
                        }
                    }
                    .padding(.horizontal, FlowMetrics.pagePadding)
                    .padding(.top, 8)
                    .padding(.bottom, 120)
                }
                .onAppear {
                    DispatchQueue.main.async {
                        scrollToCurrent(proxy: proxy, animated: false)
                    }
                }
                .onChange(of: snapshot.nodeIndex) { _ in
                    scrollToCurrent(proxy: proxy, animated: true)
                }
                .onChange(of: snapshot.visibleNodeIndices) { _ in
                    scrollToCurrent(proxy: proxy, animated: true)
                }

                VStack(spacing: 0) {
                    LinearGradient(
                        colors: [FlowPalette.runBackground.opacity(0.72), .clear],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .frame(height: 22)
                    Spacer()
                    LinearGradient(
                        colors: [.clear, FlowPalette.runBackground.opacity(0.78)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .frame(height: 30)
                }
                .allowsHitTesting(false)
            }
        }
    }

    private func scrollToCurrent(proxy: ScrollViewProxy, animated: Bool) {
        guard snapshot.visibleNodeIndices.contains(snapshot.nodeIndex) else { return }
        if animated {
            withAnimation(.easeInOut(duration: 0.28)) {
                proxy.scrollTo(snapshot.nodeIndex, anchor: .center)
            }
        } else {
            proxy.scrollTo(snapshot.nodeIndex, anchor: .center)
        }
    }

    @ViewBuilder
    private func rollerCard(_ index: Int) -> some View {
        if reduceMotion {
            baseCard(index)
        } else if #available(iOS 17.0, *) {
            baseCard(index)
                .scrollTransition(.interactive, axis: .vertical) { content, phase in
                    content
                        .opacity(max(0.22, 1 - abs(phase.value) * 0.58))
                        .scaleEffect(CGFloat(1 - abs(phase.value) * 0.08))
                        .rotation3DEffect(
                            .degrees(phase.value * -10),
                            axis: (x: 1, y: 0, z: 0),
                            perspective: 0.55
                        )
                }
                .scaleEffect(index == snapshot.nodeIndex ? 1.02 : 1)
                .zIndex(index == snapshot.nodeIndex ? 1 : 0)
        } else {
            let distance = abs(index - snapshot.nodeIndex)
            baseCard(index)
                .scaleEffect(
                    index == snapshot.nodeIndex
                        ? 1.02
                        : max(0.84, CGFloat(1 - Double(distance) * 0.04))
                )
                .opacity(
                    index == snapshot.nodeIndex
                        ? 1
                        : max(0.30, 1 - Double(distance) * 0.14)
                )
                .rotation3DEffect(
                    .degrees(Double(index - snapshot.nodeIndex) * -6),
                    axis: (x: 1, y: 0, z: 0),
                    perspective: 0.55
                )
                .zIndex(index == snapshot.nodeIndex ? 1 : 0)
        }
    }

    @ViewBuilder
    private func baseCard(_ index: Int) -> some View {
        if let step = snapshot.plan.steps[safe: index] {
            if index == snapshot.nodeIndex {
                currentNodeCard(step: step, index: index)
            } else {
                compactNodeButton(step: step, index: index)
            }
        }
    }

    private func currentNodeCard(step: RoutineStep, index: Int) -> some View {
        let typeColor = step.type == .timer ? FlowPalette.warm : FlowPalette.primaryBright
        let borderColor = expiryPulse > 0.05
            ? FlowPalette.runDanger.opacity(0.35 + expiryPulse * 0.55)
            : typeColor.opacity(0.34)
        let shape = RoundedRectangle(cornerRadius: 26, style: .continuous)

        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                typeCapsule(step.type)
                HStack(spacing: 5) {
                    Circle().fill(typeColor).frame(width: 4, height: 4)
                    Text("当前执行")
                        .flowFont(8.5, weight: .medium)
                        .foregroundColor(typeColor)
                }
                .padding(.horizontal, 7)
                .padding(.vertical, 3)
                .background(typeColor.opacity(0.10))
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                Spacer()
                Text(String(format: "%02d", index + 1))
                    .flowMonoFont(10, weight: .regular)
                    .foregroundColor(FlowPalette.runMutedText)
            }

            Text(step.name)
                .flowFont(21, weight: .semibold)
                .foregroundColor(FlowPalette.runText)
                .lineLimit(2)
                .lineSpacing(3)
                .padding(.top, 9)

            Text(currentMeta(step: step, index: index))
                .flowFont(10.5)
                .foregroundColor(FlowPalette.runSecondaryText)
                .lineLimit(1)
                .padding(.top, 4)

            Group {
                if step.type == .timer {
                    TimerDialView(
                        diameter: 184,
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
                    CountDialView(
                        diameter: 184,
                        target: step.target ?? 0,
                        state: snapshot.nodeState,
                        overallFraction: overallProgress,
                        reduceMotion: reduceMotion
                    )
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 5)
        }
        .padding(.horizontal, 17)
        .padding(.vertical, 15)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [
                    FlowPalette.runSurfaceRaised.opacity(0.98),
                    FlowPalette.runSurface.opacity(0.94)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        )
        .clipShape(shape)
        .overlay(shape.stroke(borderColor, lineWidth: 1))
        .shadow(color: typeColor.opacity(0.22), radius: 22, x: 0, y: 12)
        .offset(x: shakeX)
        .overlay(
            shape.stroke(
                FlowPalette.runDanger.opacity(expiryPulse * 0.55),
                lineWidth: 2
            )
            .allowsHitTesting(false)
        )
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("run-current-node")
    }

    private func compactNodeButton(step: RoutineStep, index: Int) -> some View {
        let statusValue = snapshot.nodeStatuses[safe: index] ?? .pending
        let deferred = statusValue == .deferred || statusValue == .skipped
        let typeColor = step.type == .timer ? FlowPalette.warm : FlowPalette.primaryBright
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        let status = compactStatusLabel(statusValue)

        return Button {
            FlowHaptics.tick()
            _ = coordinator.selectNode(index)
        } label: {
            HStack(spacing: 11) {
                ZStack {
                    Circle().fill(typeColor.opacity(0.10))
                    FlowTypeGlyph(type: step.type, size: 13, onDark: true)
                }
                .frame(width: 30, height: 30)

                VStack(alignment: .leading, spacing: 3) {
                    Text(step.name)
                        .flowFont(14, weight: .medium)
                        .foregroundColor(FlowPalette.runText)
                        .lineLimit(1)
                    Text(compactMeta(
                        step: step,
                        remainingMs: snapshot.remainingFor(index)
                    ))
                    .flowFont(9.5)
                    .foregroundColor(FlowPalette.runMutedText)
                    .lineLimit(1)
                }
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 3) {
                    Text(step.type == .timer ? "TIMER" : "COUNT")
                        .flowFont(8, weight: .semibold)
                        .tracking(0.5)
                        .foregroundColor(typeColor.opacity(0.90))
                    Text(status)
                        .flowFont(9.5, weight: .medium)
                        .foregroundColor(deferred ? FlowPalette.warm : FlowPalette.runSecondaryText)
                }
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 11)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                deferred
                    ? FlowPalette.warm.opacity(0.055)
                    : Color.white.opacity(0.035)
            )
            .overlay(
                shape.stroke(
                    deferred
                        ? FlowPalette.warm.opacity(0.24)
                        : Color.white.opacity(0.065),
                    lineWidth: 1
                )
            )
            .clipShape(shape)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("run-node-\(index)")
        .accessibilityLabel("切换到节点 \(step.name)，\(status)")
    }

    private func typeCapsule(_ type: StepType) -> some View {
        let color = type == .timer ? FlowPalette.warm : FlowPalette.primaryBright
        return HStack(spacing: 5) {
            FlowTypeGlyph(type: type, size: 10, onDark: true)
            Text(type == .timer ? "TIMER" : "COUNT")
                .flowFont(8.5, weight: .semibold)
                .tracking(0.6)
                .foregroundColor(color)
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 4)
        .background(color.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
    }

    // MARK: - Bottom dock

    private var bottomDock: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                HStack(spacing: 4) {
                    Text("单节点循环")
                        .flowFont(9.5)
                        .foregroundColor(FlowPalette.runSecondaryText)
                    Toggle("", isOn: Binding(
                        get: { snapshot.loopCurrentNode },
                        set: { value in _ = coordinator.setLoop(value) }
                    ))
                    .labelsHidden()
                    .tint(FlowPalette.primary)
                    .scaleEffect(0.72)
                    .frame(width: 44, height: 26)
                    .accessibilityIdentifier("run-loop-toggle")
                    .accessibilityLabel("单节点循环")
                }
                .padding(.leading, 10)
                .padding(.trailing, 4)
                .padding(.vertical, 2)
                .background(Color.white.opacity(0.04))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(Color.white.opacity(0.06), lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                Spacer()

                dockTextButton("结束", identifier: "run-end", accessibilityLabel: "结束流程") {
                    showExitConfirmation = true
                }
            }
            .padding(.horizontal, FlowMetrics.pagePadding)

            HStack(spacing: 10) {
                primaryDockButton
                if needsSecondaryComplete {
                    dockActionButton(
                        label: "完成当前节点",
                        icon: "checkmark",
                        primary: false,
                        identifier: "run-secondary"
                    ) {
                        FlowHaptics.commit()
                        _ = coordinator.completeNode()
                    }
                }
            }
            .padding(.horizontal, FlowMetrics.pagePadding)
            .padding(.top, 8)

            Text(needsSecondaryComplete
                ? "完成会记录本节点；切换节点不会丢失进度"
                : "点击上方节点可切换执行目标")
                .flowFont(9.5)
                .foregroundColor(FlowPalette.runMutedText)
                .multilineTextAlignment(.center)
                .padding(.top, 7)
                .padding(.bottom, 10)
        }
        .background(
            LinearGradient(
                colors: [
                    FlowPalette.runBackground.opacity(0),
                    FlowPalette.runBackground.opacity(0.92),
                    FlowPalette.runBackground
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private var needsSecondaryComplete: Bool {
        isTimer && (snapshot.nodeState == .running || snapshot.nodeState == .paused)
    }

    @ViewBuilder
    private var primaryDockButton: some View {
        switch (isTimer, snapshot.nodeState) {
        case (false, .ready):
            dockActionButton(label: "开始", icon: "play.fill", primary: true, identifier: "run-primary") {
                FlowHaptics.tick()
                _ = coordinator.startNode()
            }
        case (true, .ready):
            dockActionButton(label: "开始计时", icon: "play.fill", primary: true, identifier: "run-primary") {
                FlowHaptics.tick()
                _ = coordinator.startNode()
            }
        case (false, .running):
            dockActionButton(label: "完成当前节点", icon: "checkmark", primary: true, identifier: "run-primary") {
                FlowHaptics.commit()
                _ = coordinator.completeNode()
            }
        case (true, .running):
            dockActionButton(label: "暂停", icon: "pause.fill", primary: true, identifier: "run-primary") {
                FlowHaptics.tick()
                _ = coordinator.pause()
            }
        case (true, .paused):
            dockActionButton(label: "继续计时", icon: "play.fill", primary: true, identifier: "run-primary") {
                FlowHaptics.tick()
                _ = coordinator.resume()
            }
        case (true, .expired):
            dockActionButton(label: "完成当前节点", icon: "checkmark", primary: true, identifier: "run-primary") {
                FlowHaptics.commit()
                _ = coordinator.completeNode()
            }
        default:
            EmptyView()
        }
    }

    private func dockActionButton(
        label: String,
        icon: String,
        primary: Bool,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .semibold))
                Text(label)
                    .flowFont(13, weight: primary ? .semibold : .medium)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundColor(primary ? .white : Color.white.opacity(0.88))
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(
                Group {
                    if primary {
                        LinearGradient(
                            colors: [
                                Color(red: 0.31, green: 0.55, blue: 1.0),
                                Color(red: 0.18, green: 0.42, blue: 1.0)
                            ],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    } else {
                        Color.white.opacity(0.065)
                    }
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(
                        primary ? Color.clear : Color.white.opacity(0.11),
                        lineWidth: 1
                    )
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
        .accessibilityLabel(label)
    }

    private func dockTextButton(
        _ label: String,
        identifier: String,
        accessibilityLabel: String,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(label)
                .flowFont(10.5)
                .foregroundColor(
                    enabled
                        ? FlowPalette.runSecondaryText
                        : FlowPalette.runMutedText.opacity(0.45)
                )
                .padding(.horizontal, 9)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityIdentifier(identifier)
        .accessibilityLabel(accessibilityLabel)
    }

    // MARK: - Feedback

    private func playCompletionFeedback() {
        if reduceMotion {
            withAnimation(.easeOut(duration: 0.12)) { completionFlash = 0.35 }
            Task {
                try? await Task.sleep(nanoseconds: 130_000_000)
                withAnimation(.easeOut(duration: 0.16)) { completionFlash = 0 }
            }
        } else {
            completionFlash = 0
            withAnimation(.easeOut(duration: 0.52)) { completionFlash = 1 }
            Task {
                try? await Task.sleep(nanoseconds: 540_000_000)
                withAnimation(.easeOut(duration: 0.16)) { completionFlash = 0 }
            }
        }
    }

    private func playExpiryFeedback() async {
        if reduceMotion {
            withAnimation(.easeOut(duration: 0.12)) { expiryPulse = 1 }
            try? await Task.sleep(nanoseconds: 160_000_000)
            withAnimation(.easeOut(duration: 0.22)) { expiryPulse = 0 }
            return
        }
        for offset in [-16, 15, -13, 10, -7, 5, -3, 2] {
            withAnimation(.linear(duration: 0.05)) {
                shakeX = CGFloat(offset)
            }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        withAnimation(.linear(duration: 0.05)) { shakeX = 0 }
        withAnimation(.easeOut(duration: 0.12)) { expiryPulse = 1 }
        try? await Task.sleep(nanoseconds: 120_000_000)
        withAnimation(.easeOut(duration: 0.32)) { expiryPulse = 0 }
    }

    // MARK: - Copy helpers

    private func currentMeta(step: RoutineStep, index: Int) -> String {
        switch step.type {
        case .count:
            return "目标 \(step.target ?? 0) 次"
        case .timer:
            let state: String
            switch snapshot.nodeState {
            case .ready: state = "待开始"
            case .running: state = "进行中"
            case .paused: state = "已暂停"
            case .expired: state = "已到期"
            }
            return "时长 \(formatRunDuration(step.durationMs)) · \(state) · 剩余 \(formatRunCountdown(snapshot.remainingMs))"
        }
    }

    private func compactMeta(step: RoutineStep, remainingMs: Int64?) -> String {
        switch step.type {
        case .count:
            return "目标 \(step.target ?? 0) 次"
        case .timer:
            if let remainingMs {
                return "时长 \(formatRunDuration(step.durationMs)) · 剩余 \(formatRunCountdown(remainingMs))"
            }
            return "时长 \(formatRunDuration(step.durationMs))"
        }
    }

    private func compactStatusLabel(_ status: NodeStatus) -> String {
        switch status {
        case .deferred: return "暂缓"
        case .pending: return "待执行"
        case .completed: return "已完成"
        case .skipped: return "暂缓"
        case .current: return "当前"
        }
    }
}

// MARK: - COUNT dial

private struct CountDialView: View {
    let diameter: CGFloat
    let target: Int
    let state: NodeRunState
    let overallFraction: Double
    let reduceMotion: Bool

    @State private var breath: CGFloat = 0

    private var running: Bool { state == .running }
    private var status: String { running ? "进行中" : "准备开始" }
    private var statusColor: Color {
        running ? FlowPalette.primaryBright : FlowPalette.runSecondaryText
    }
    private var ringOpacity: Double {
        running && !reduceMotion ? 0.22 + Double(breath) * 0.26 : 0.15
    }
    private var haloOpacity: Double {
        running && !reduceMotion ? 0.10 + Double(breath) * 0.12 : 0.04
    }
    private var breathingScale: CGFloat {
        guard running, !reduceMotion else { return 1 }
        return 0.985 + breath * 0.07
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(
                    RadialGradient(
                        colors: [
                            FlowPalette.primary.opacity(
                                running ? 0.16 + Double(breath) * 0.22 : 0.08
                            ),
                            .clear
                        ],
                        center: .center,
                        startRadius: 0,
                        endRadius: diameter * 0.60
                    )
                )
                .frame(width: diameter * 1.20, height: diameter * 1.20)

            Circle()
                .stroke(Color.white.opacity(0.055), lineWidth: 2)
                .frame(width: diameter - 6, height: diameter - 6)

            Circle()
                .trim(from: 0, to: min(1, max(0, overallFraction)))
                .stroke(FlowPalette.success.opacity(0.62), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .frame(width: diameter - 6, height: diameter - 6)

            Circle()
                .stroke(Color.white.opacity(0.07), lineWidth: 9)
                .padding(22)

            Circle()
                .stroke(FlowPalette.primaryBright.opacity(haloOpacity), lineWidth: 1.5)
                .frame(
                    width: diameter * (1.00 + breath * 0.08),
                    height: diameter * (1.00 + breath * 0.08)
                )

            // A full, decorative breathing ring. It is not a progress arc:
            // COUNT is target-only and the app does not count repetitions.
            Circle()
                .stroke(
                    LinearGradient(
                        colors: [
                            Color(red: 0.72, green: 0.82, blue: 1.0),
                            Color(red: 0.36, green: 0.55, blue: 1.0),
                            Color(red: 0.18, green: 0.42, blue: 1.0)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 9
                )
                .padding(22)
                .opacity(ringOpacity)
                .shadow(color: FlowPalette.primaryBright.opacity(0.28), radius: 10)

            VStack(spacing: 2) {
                Text("目标次数")
                    .flowFont(9.5, weight: .medium)
                    .tracking(1.5)
                    .foregroundColor(FlowPalette.runMutedText)

                HStack(alignment: .bottom, spacing: 5) {
                    Text("\(target)")
                        .flowFont(78, weight: .semibold)
                        .foregroundColor(FlowPalette.runText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                    Text("次")
                        .flowFont(15)
                        .foregroundColor(FlowPalette.runSecondaryText)
                        .padding(.bottom, 12)
                }

                HStack(spacing: 6) {
                    Circle().fill(statusColor).frame(width: 4, height: 4)
                    Text(status)
                        .flowFont(9.5, weight: .medium)
                        .tracking(0.5)
                        .foregroundColor(statusColor)
                }

                Spacer().frame(height: 7)

                Text(running ? "不着急，完成后再继续" : "点击下方开始")
                    .flowFont(9)
                    .foregroundColor(FlowPalette.runMutedText)
            }
            .frame(width: diameter * 0.64)
        }
        .frame(width: diameter, height: diameter)
        .scaleEffect(breathingScale)
        .onAppear {
            if running && !reduceMotion {
                withAnimation(.easeInOut(duration: 3.8).repeatForever(autoreverses: true)) {
                    breath = 1
                }
            }
        }
        .onChange(of: state) { newState in
            if newState == .running && !reduceMotion {
                withAnimation(.easeInOut(duration: 3.8).repeatForever(autoreverses: true)) {
                    breath = 1
                }
            } else {
                withAnimation(.easeOut(duration: 0.2)) {
                    breath = 0
                }
            }
        }
    }
}

// MARK: - TIMER dial

private struct TimerDialView: View {
    let diameter: CGFloat
    let overallFraction: Double
    let remainingFraction: Double
    let countdown: String
    let state: NodeRunState
    let reduceMotion: Bool

    @State private var breathe = false

    private var running: Bool { state == .running }
    private var targetRemaining: Double {
        state == .ready ? 1 : min(1, max(0, remainingFraction))
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
    private var ringColors: [Color] {
        if state == .expired {
            return [FlowPalette.runDanger.opacity(0.9), FlowPalette.runDanger]
        }
        return [
            Color(red: 0.66, green: 0.78, blue: 1.0),
            Color(red: 0.36, green: 0.55, blue: 1.0),
            Color(red: 0.18, green: 0.42, blue: 1.0)
        ]
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(
                    RadialGradient(
                        colors: [
                            FlowPalette.primary.opacity(running ? 0.17 + (breathe ? 0.08 : 0) : 0.11),
                            .clear
                        ],
                        center: .center,
                        startRadius: 0,
                        endRadius: diameter * 0.62
                    )
                )
                .frame(width: diameter * 1.24, height: diameter * 1.24)

            Circle()
                .stroke(Color.white.opacity(0.055), lineWidth: 2)
                .frame(width: diameter - 6, height: diameter - 6)

            Circle()
                .trim(from: 0, to: min(1, max(0, overallFraction)))
                .stroke(FlowPalette.success.opacity(0.62), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .frame(width: diameter - 6, height: diameter - 6)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.42), value: overallFraction)

            Circle()
                .stroke(Color.white.opacity(0.075), lineWidth: 10)
                .padding(22)

            if targetRemaining > 0.001 {
                Circle()
                    .trim(from: 0, to: targetRemaining)
                    .stroke(
                        LinearGradient(
                            colors: ringColors,
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        style: StrokeStyle(lineWidth: 10, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                    .padding(22)
                    .shadow(color: FlowPalette.primaryBright.opacity(0.30), radius: 12)
                    .animation(reduceMotion ? nil : .linear(duration: 0.22), value: targetRemaining)
            }

            VStack(spacing: 4) {
                HStack(spacing: 6) {
                    Circle().fill(statusColor).frame(width: 4, height: 4)
                    Text(status)
                        .flowFont(10, weight: .medium)
                        .tracking(0.5)
                        .foregroundColor(statusColor)
                }
                Text(countdown)
                    .flowMonoFont(flowTimerNumberSize(characters: countdown.count), weight: .semibold)
                    .monospacedDigit()
                    .foregroundColor(state == .expired ? FlowPalette.runDanger : FlowPalette.runText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.55)
                    .accessibilityIdentifier("run-countdown")
            }
            .frame(width: diameter * 0.64)
        }
        .frame(width: diameter, height: diameter)
        .onAppear {
            guard running, !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 2.6).repeatForever(autoreverses: true)) {
                breathe = true
            }
        }
        .onChange(of: state) { newState in
            if newState == .running && !reduceMotion {
                withAnimation(.easeInOut(duration: 2.6).repeatForever(autoreverses: true)) {
                    breathe = true
                }
            } else {
                withAnimation(.easeOut(duration: 0.2)) {
                    breathe = false
                }
            }
        }
    }
}

// MARK: - Completion flash

private struct CompletionFlashView: View {
    let progress: Double

    var body: some View {
        GeometryReader { geometry in
            if progress > 0.001 {
                let minSide = min(geometry.size.width, geometry.size.height)
                let eased = 1 - (1 - progress) * (1 - progress)
                let radius = minSide * (0.10 + eased * 0.46)
                ZStack {
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [
                                    FlowPalette.primaryBright.opacity(0.16 * (1 - progress)),
                                    .clear
                                ],
                                center: .center,
                                startRadius: 0,
                                endRadius: radius * 1.25
                            )
                        )
                        .frame(width: radius * 2.5, height: radius * 2.5)
                    Circle()
                        .stroke(
                            FlowPalette.success.opacity(0.55 * (1 - progress)),
                            lineWidth: 2
                        )
                        .frame(width: radius * 2, height: radius * 2)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .ignoresSafeArea()
    }
}

// MARK: - Safe indexing

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
