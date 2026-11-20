import SwiftUI

struct PlanHomeView: View {
    private let store: PlanStore
    @EnvironmentObject private var runCoordinator: RunCoordinator
    @State private var plans: [Routine] = []
    @State private var editing: Routine?
    @State private var setupPlan: Routine?
    @State private var setupMessage: String?
    @State private var errorMessage: String?
    @State private var planPendingDeletion: Routine?

    init(store: PlanStore) {
        self.store = store
    }

    var body: some View {
        ZStack {
            FlowPalette.background.ignoresSafeArea()
            content
        }
        .onAppear { reload() }
        .preferredColorScheme(.light)
        .dynamicTypeSize(.small ... .xxxLarge)
        .alert("删除这个流程？", isPresented: Binding(
            get: { planPendingDeletion != nil },
            set: { if !$0 { planPendingDeletion = nil } }
        )) {
            Button("取消", role: .cancel) { planPendingDeletion = nil }
            Button("删除", role: .destructive) {
                if let plan = planPendingDeletion { delete(plan) }
                planPendingDeletion = nil
            }
        } message: {
            Text("删除后，这个流程及其节点将无法恢复。")
        }
    }

    @ViewBuilder
    private var content: some View {
        switch runCoordinator.runtime {
        case .active(let snapshot):
            RunView(coordinator: runCoordinator, snapshot: snapshot)
        case .finished(let summary):
            RunResultView(summary: summary) {
                runCoordinator.dismissResult()
                reload()
            }
        case .idle:
            if let summary = runCoordinator.lastExitSummary {
                RunExitResultView(summary: summary) {
                    runCoordinator.clearExitSummary()
                    reload()
                }
            } else if let editing {
                FlowRootView(routine: editing, store: store) {
                    self.editing = nil
                    reload()
                }
            } else if let setupPlan {
                RunSetupView(
                    plan: setupPlan,
                    message: setupMessage,
                    onBack: {
                        self.setupPlan = nil
                        self.setupMessage = nil
                    },
                    onStart: {
                        start(setupPlan)
                    }
                )
            } else {
                home
            }
        }
    }

    private var home: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Flow")
                            .flowFont(30, weight: .semibold)
                            .foregroundColor(FlowPalette.text)
                        Text("把流程变成可执行的步骤")
                            .flowFont(13)
                            .foregroundColor(FlowPalette.secondaryText)
                    }
                    Spacer()
                    Button("＋ 新建") { create() }
                        .flowFont(14, weight: .medium)
                        .foregroundColor(.white)
                        .frame(width: 92, height: 44)
                        .background(FlowPalette.primary)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("home-new")
                }
                .padding(.top, 8)

                if let errorMessage {
                    Text(errorMessage)
                        .flowFont(13)
                        .foregroundColor(FlowPalette.invalid)
                        .padding(.top, 12)
                }

                HStack {
                    Text("我的流程")
                        .flowFont(20, weight: .semibold)
                        .foregroundColor(FlowPalette.text)
                    Spacer()
                    Text("\(plans.count) 个流程")
                        .flowFont(13)
                        .foregroundColor(FlowPalette.secondaryText)
                }
                .padding(.top, 34)

                if plans.isEmpty {
                    emptyState
                        .padding(.top, 14)
                } else {
                    VStack(spacing: 14) {
                        ForEach(plans) { plan in
                            planCard(plan)
                        }
                    }
                    .padding(.top, 14)
                }

                Spacer(minLength: 24)
            }
            .padding(.horizontal, FlowMetrics.pagePadding)
            .padding(.bottom, 20)
        }
        .scrollIndicators(.hidden)
    }

    private var emptyState: some View {
        FlowCard(padding: 24) {
            VStack(spacing: 0) {
                FlowGraphic()
                Text("还没有流程")
                    .flowFont(18, weight: .semibold)
                    .foregroundColor(FlowPalette.text)
                    .padding(.top, 18)
                Text("创建一套流程，让每一步都有明确安排。")
                    .flowFont(13)
                    .foregroundColor(FlowPalette.secondaryText)
                    .multilineTextAlignment(.center)
                    .padding(.top, 6)
                Button("创建第一个流程") { create() }
                    .flowFont(14, weight: .medium)
                    .foregroundColor(.white)
                    .frame(height: 46)
                    .padding(.horizontal, 18)
                    .background(FlowPalette.primary)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .buttonStyle(.plain)
                    .padding(.top, 18)
                    .accessibilityIdentifier("home-empty-new")
            }
            .frame(maxWidth: .infinity)
        }
    }

    private func planCard(_ plan: Routine) -> some View {
        FlowCard(padding: 18) {
            Button {
                guard !runCoordinator.isActive else { return }
                editing = plan
            } label: {
                VStack(alignment: .leading, spacing: 0) {
                    Text(plan.name)
                        .flowFont(18, weight: .semibold)
                        .foregroundColor(FlowPalette.text)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Text("\(plan.steps.count) 个节点")
                        .flowFont(13)
                        .foregroundColor(FlowPalette.secondaryText)
                        .padding(.top, 5)

                    if !plan.steps.isEmpty {
                        VStack(spacing: 0) {
                            ForEach(Array(plan.steps.prefix(3).enumerated()), id: \.element.id) { index, step in
                                PlanTimelineRow(
                                    step: step,
                                    index: index,
                                    isLast: index == plan.steps.prefix(3).count - 1
                                )
                            }
                        }
                        .padding(.top, 14)
                        if plan.steps.count > 3 {
                            Text("还有 \(plan.steps.count - 3) 个节点")
                                .flowFont(12)
                                .foregroundColor(FlowPalette.secondaryText)
                                .padding(.top, 6)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            HStack(spacing: 10) {
                Button("开始") {
                    openSetup(plan)
                }
                .flowFont(14, weight: .medium)
                .foregroundColor(plan.steps.isEmpty ? FlowPalette.secondaryText : .white)
                .frame(width: 84, height: 44)
                .background(plan.steps.isEmpty ? FlowPalette.segmentBackground : FlowPalette.primary)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .buttonStyle(.plain)
                .disabled(plan.steps.isEmpty)
                .accessibilityIdentifier("plan-start")

                Button("编辑") {
                    guard !runCoordinator.isActive else { return }
                    editing = plan
                }
                .flowFont(14)
                .foregroundColor(FlowPalette.primary)
                .buttonStyle(.plain)
                .padding(.horizontal, 4)

                Spacer()

                Menu {
                    Button("删除", role: .destructive) {
                        planPendingDeletion = plan
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .flowFont(18, weight: .medium)
                        .foregroundColor(FlowPalette.secondaryText)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityIdentifier("plan-more")
            }
            .padding(.top, 18)
        }
    }

    private func compactTarget(_ step: RoutineStep) -> String {
        switch step.type {
        case .count:
            return "\(step.target ?? 0) 次"
        case .timer:
            return formatRunCountdown(step.durationMs)
        }
    }

    private func openSetup(_ plan: Routine) {
        guard !runCoordinator.isActive else { return }
        setupPlan = plan
        setupMessage = nil
    }

    private func start(_ plan: Routine) -> String? {
        switch runCoordinator.start(plan: plan) {
        case .started:
            setupPlan = nil
            setupMessage = nil
            return nil
        case .rejected(.alreadyRunning):
            return "已有运行中的计划，请先完成或退出。"
        case .rejected(.emptyPlan):
            return "计划没有节点，无法运行。"
        }
    }

    private func reload() {
        do {
            plans = try store.loadAll()
            errorMessage = nil
        } catch {
            errorMessage = "读取计划失败"
        }
    }

    private func create() {
        guard let plan = try? RoutineDraft.new(name: "未命名计划").toRoutine() else { return }
        do {
            try store.save(plan)
            reload()
            editing = plan
        } catch {
            errorMessage = "保存计划失败"
        }
    }

    private func delete(_ plan: Routine) {
        do {
            try store.delete(id: plan.id)
            reload()
        } catch {
            errorMessage = "删除计划失败"
        }
    }
}


private struct FlowGraphic: View {
    var body: some View {
        ZStack {
            Circle()
                .fill(FlowPalette.accentSoft)
                .frame(width: 72, height: 72)
            VStack(spacing: 0) {
                Circle().fill(FlowPalette.primary).frame(width: 7, height: 7)
                Rectangle().fill(FlowPalette.primary.opacity(0.30)).frame(width: 1, height: 8)
                Circle().fill(FlowPalette.primary).frame(width: 7, height: 7)
                Rectangle().fill(FlowPalette.primary.opacity(0.30)).frame(width: 1, height: 8)
                Circle().fill(FlowPalette.primary).frame(width: 7, height: 7)
            }
        }
    }
}

private struct PlanTimelineRow: View {
    let step: RoutineStep
    let index: Int
    let isLast: Bool

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                if index > 0 {
                    Rectangle()
                        .fill(FlowPalette.connector)
                        .frame(width: 1, height: 19)
                        .frame(maxHeight: .infinity, alignment: .top)
                }
                if !isLast {
                    Rectangle()
                        .fill(FlowPalette.connector)
                        .frame(width: 1, height: 19)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                }
                Circle()
                    .fill(step.type == .count ? FlowPalette.primary : FlowPalette.dirty)
                    .frame(width: 8, height: 8)
            }
            .frame(width: 16, height: 38)

            Text(step.name)
                .flowFont(15)
                .foregroundColor(FlowPalette.text)
                .lineLimit(1)
            Spacer()
            Text(compactTarget(step))
                .flowFont(13)
                .foregroundColor(FlowPalette.secondaryText)
        }
        .frame(height: 38)
    }

    private func compactTarget(_ step: RoutineStep) -> String {
        switch step.type {
        case .count:
            return "\(step.target ?? 0) 次"
        case .timer:
            return formatRunCountdown(step.durationMs)
        }
    }
}

struct FlowRootView: View {
    var onBack: () -> Void = {}

    @StateObject private var model: FlowEditorModel
    private let store: PlanStore?

    init(routine: Routine? = nil, store: PlanStore? = nil, onBack: @escaping () -> Void = {}) {
        self.onBack = onBack
        self.store = store
        if let routine, let draft = try? RoutineDraft.from(routine) {
            _model = StateObject(wrappedValue: FlowEditorModel(draft: draft) { plan in
                try store?.save(plan)
            })
        } else {
            _model = StateObject(wrappedValue: FlowEditorModel(draft: nil) { plan in
                try store?.save(plan)
            })
        }
    }
    @State private var showRestoreConfirmation = false
    @FocusState private var nameFocused: Bool
    @State private var cardForm: CardStepForm?
    @State private var stepToDelete: RoutineStep?
    @State private var showDeleteConfirmation = false

    var body: some View {
        ZStack {
            FlowPalette.background.ignoresSafeArea()

            if let state = model.state {
                editor(state: state)
            } else {
                startupError
            }
        }
        .preferredColorScheme(.light)
        .alert("恢复上次保存", isPresented: $showRestoreConfirmation) {
            Button("取消", role: .cancel) {}
            Button("恢复", role: .destructive) {
                model.restore()
            }
        } message: {
            Text("当前未保存的输入将被清除，确定恢复上次保存的内容吗？")
        }
        .sheet(item: $cardForm) { form in
            CardStepEditorView(form: form) { updated in
                saveCardForm(updated)
            }
        }
        .confirmationDialog(
            "删除步骤",
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                if let step = stepToDelete {
                    _ = model.deleteStep(id: step.id)
                }
                stepToDelete = nil
            }
            Button("取消", role: .cancel) {
                stepToDelete = nil
            }
        } message: {
            Text("确定删除“\(stepToDelete?.name ?? "")”吗？此操作不会删除其他步骤。")
        }
    }

    private func editor(state: EditorState) -> some View {
        VStack(spacing: 0) {
            header

            ScrollView {
                VStack(spacing: 0) {
                    flowNameCard
                        .padding(.top, 24)
                    modeSection(selected: state.mode)
                        .padding(.top, 22)
                    syncSection(status: state.syncStatus)
                        .padding(.top, 12)

                    if let message = model.modeMessage {
                        modeMessage(message)
                            .padding(.top, 10)
                    }

                    content(state: state)
                        .padding(.top, 24)
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, FlowMetrics.pagePadding)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)

            saveBar
        }
    }

    private var header: some View {
        ZStack {
            Text("编辑流程")
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
                .accessibilityIdentifier("editor-back")
                Spacer()
            }
            .padding(.horizontal, FlowMetrics.pagePadding - 8)
        }
        .frame(height: 48)
    }

    private var flowNameCard: some View {
        let tooLong = model.planName.count > EditorController.maxNameLength
        let error = model.nameError ?? (tooLong
            ? "流程名称最多 \(EditorController.maxNameLength) 个字符。"
            : nil)
        return VStack(alignment: .leading, spacing: 0) {
            Text("流程名称")
                .flowFont(13)
                .foregroundColor(FlowPalette.secondaryText)
            TextField(
                "例如：晨间拉伸",
                text: Binding(
                    get: { model.planName },
                    set: { model.updateName($0) }
                )
            )
            .flowFont(17, weight: .medium)
            .foregroundColor(FlowPalette.text)
            .focused($nameFocused)
            .textFieldStyle(.plain)
            .padding(.horizontal, 16)
            .frame(height: 54)
            .background(FlowPalette.surface)
            .clipShape(RoundedRectangle(cornerRadius: FlowMetrics.inputRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: FlowMetrics.inputRadius, style: .continuous)
                    .stroke(
                        error != nil ? FlowPalette.invalid :
                            (nameFocused ? FlowPalette.primary : FlowPalette.outline),
                        lineWidth: 1
                    )
            )
            .padding(.top, 8)
            .accessibilityIdentifier("plan-name-field")

            Text(error ?? "\(model.planName.count)/\(EditorController.maxNameLength)")
                .flowFont(12)
                .foregroundColor(error == nil ? FlowPalette.secondaryText : FlowPalette.invalid)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.top, 5)
        }
    }

    private func modeSection(selected: EditorMode) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("编辑方式")
                .flowFont(13)
                .foregroundColor(FlowPalette.secondaryText)
            HStack(spacing: 4) {
                modeButton("卡片编辑", mode: .cards, selected: selected)
                modeButton("文本编辑", mode: .text, selected: selected)
            }
            .padding(3)
            .frame(height: 46)
            .background(FlowPalette.segmentBackground)
            .clipShape(RoundedRectangle(cornerRadius: FlowMetrics.inputRadius, style: .continuous))
            .padding(.top, 8)
        }
    }

    private func modeButton(
        _ title: String,
        mode: EditorMode,
        selected: EditorMode
    ) -> some View {
        let isSelected = selected == mode
        return Button {
            model.selectMode(mode)
        } label: {
            Text(title)
                .flowFont(16, weight: .medium)
                .foregroundColor(isSelected ? FlowPalette.primary : FlowPalette.secondaryText)
                .frame(maxWidth: .infinity)
                .frame(height: 40)
                .background(isSelected ? FlowPalette.surface : Color.clear)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private func syncSection(status: SyncStatus) -> some View {
        HStack(spacing: 6) {
            Circle()
                .fill(syncColor(status))
                .frame(width: 6, height: 6)
            Text(syncLabel(status))
                .flowFont(12)
                .foregroundColor(syncColor(status))
            Spacer()
        }
    }

    private func modeMessage(_ message: String) -> some View {
        Text(message)
            .flowFont(13)
            .foregroundColor(FlowPalette.invalid)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(FlowPalette.dangerSoft)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(FlowPalette.invalid.opacity(0.18), lineWidth: 1)
            )
    }

    private func content(state: EditorState) -> some View {
        Group {
            switch state.mode {
            case .text:
                textModeContent(state: state)
            case .cards:
                cardsModeContent(state: state)
            }
        }
    }

    private var saveBar: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(FlowPalette.outline)
                .frame(height: 1)
            Button("保存流程") {
                model.savePlan()
            }
            .buttonStyle(FlowPrimaryButtonStyle())
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .accessibilityIdentifier("editor-save-top")
        }
        .background(FlowPalette.surface)
    }

    private func textModeContent(state: EditorState) -> some View {
        let errors = lineErrors(state)
        return VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("步骤文本")
                    .flowFont(18, weight: .semibold)
                    .foregroundColor(FlowPalette.text)
                Spacer()
                Text("一行一个步骤")
                    .flowFont(13)
                    .foregroundColor(FlowPalette.secondaryText)
            }

            formatHint
                .padding(.top, 10)

            if let notice = model.notice {
                noticeBanner(notice)
                    .padding(.top, 8)
            }

            if !errors.isEmpty {
                VStack(spacing: 6) {
                    ForEach(errors) { error in
                        errorRow(error)
                    }
                }
                .padding(.top, 8)
            }

            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(FlowPalette.surface)
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(FlowPalette.outline, lineWidth: 1)
                    )

                TextEditor(
                    text: Binding(
                        get: { model.editorText },
                        set: { model.updateText($0) }
                    )
                )
                .flowFont(15)
                .foregroundColor(FlowPalette.text)
                .scrollContentBackground(.hidden)
                .background(Color.clear)
                .padding(16)
                .accessibilityIdentifier("editor-text")

                if model.editorText.isEmpty {
                    Text("在这里输入流程步骤，一行一个步骤。")
                        .flowFont(15)
                        .foregroundColor(FlowPalette.secondaryText)
                        .padding(.horizontal, 21)
                        .padding(.vertical, 24)
                        .allowsHitTesting(false)
                }
            }
            .frame(minHeight: 180, maxHeight: 360)
            .padding(.top, 12)

            HStack(spacing: 12) {
                Button {
                    model.applyText()
                } label: {
                    Text("应用文本修改")
                        .flowFont(15, weight: .medium)
                        .foregroundColor(FlowPalette.primary)
                        .frame(maxWidth: .infinity)
                        .frame(height: 44)
                        .background(FlowPalette.primarySoft)
                        .clipShape(RoundedRectangle(cornerRadius: FlowMetrics.buttonRadius, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("editor-save")

                Button {
                    showRestoreConfirmation = true
                } label: {
                    Text("恢复")
                        .flowFont(15)
                        .foregroundColor(FlowPalette.secondaryText)
                        .frame(height: 44)
                        .padding(.horizontal, 8)
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 12)
        }
    }

    private var formatHint: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("格式示例")
                .flowFont(13, weight: .medium)
                .foregroundColor(FlowPalette.primary)
            Text("俯卧撑 15次 · 阅读 20分钟 · 拉伸 45秒 x2")
                .flowFont(13)
                .foregroundColor(FlowPalette.text)
            Text("一行一个步骤；数字和单位表示目标。")
                .flowFont(12)
                .foregroundColor(FlowPalette.secondaryText)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlowPalette.primarySoft)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func noticeBanner(_ notice: UiNotice) -> some View {
        let color = notice.isError ? FlowPalette.invalid : FlowPalette.synced
        return Text(notice.message)
            .flowFont(13, weight: .regular)
            .foregroundColor(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(color.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func errorRow(_ error: EditorLineError) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text("第 \(error.lineNumber) 行")
                    .flowFont(11, weight: .semibold)
                    .foregroundColor(FlowPalette.invalid)
                Spacer()
                Text(error.status == .incomplete ? "未完成" : "不合法")
                    .flowFont(11, weight: .regular)
                    .foregroundColor(FlowPalette.invalid)
            }
            Text("“\(error.raw.isEmpty ? "空行" : error.raw)”")
                .flowFont(11, weight: .regular)
                .foregroundColor(FlowPalette.secondaryText)
                .lineLimit(1)
            Text(error.message)
                .flowFont(13, weight: .regular)
                .foregroundColor(FlowPalette.text)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlowPalette.invalid.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(FlowPalette.invalid.opacity(0.14), lineWidth: 1)
        )
    }

    private func lineErrors(_ state: EditorState) -> [EditorLineError] {
        state.parseResult.diagnostics.compactMap { diagnostic in
            guard let line = state.parseResult.lines.first(where: {
                $0.lineNumber == diagnostic.lineNumber
            }) else {
                return nil
            }
            return EditorLineError(
                id: diagnostic.lineNumber,
                lineNumber: diagnostic.lineNumber,
                status: line.status,
                raw: line.raw,
                message: parseErrorMessage(diagnostic.code)
            )
        }
    }

    private func parseErrorMessage(_ code: ParseErrorCode) -> String {
        switch code {
        case .incompleteMissingQuantity:
            return "缺少数量，请补充目标数量。"
        case .incompleteMissingUnit:
            return "缺少单位，请补充“次”“秒”“分钟”或“小时”。"
        case .incompleteRepeatCount:
            return "重复次数未完成，请在 x 后补充数字。"
        case .incompleteAttribute:
            return "属性未完成，请补全方括号内的内容。"
        case .invalidName:
            return "步骤名称不能为空。"
        case .invalidNumber:
            return "数量必须是正整数。"
        case .invalidUnit:
            return "单位无法识别，请使用“次”“秒”“分钟”或“小时”。"
        case .invalidDecimal:
            return "暂不支持小数，请输入整秒或整数次。"
        case .invalidCompound:
            return "一行只能描述一个步骤，请拆分复合时长。"
        case .invalidRepeatCount:
            return "重复次数必须大于零。"
        case .invalidAttribute:
            return "属性格式不正确，请检查名称、等号和方括号。"
        case .invalidAttributeType:
            return "属性与步骤类型不匹配。"
        case .invalidAttributeOrder:
            return "属性必须放在行尾。"
        case .invalidTrailing:
            return "数量或属性后存在无法识别的内容。"
        case .invalidOverflow:
            return "数量过大，请缩小数值。"
        case .invalidLine:
            return "该行无法解析，请检查格式。"
        }
    }

    private func cardsModeContent(state: EditorState) -> some View {
        let steps = state.draft.steps
        return VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("流程节点")
                    .flowFont(18, weight: .semibold)
                    .foregroundColor(FlowPalette.text)
                Spacer()
                Text("\(steps.count) 个节点")
                    .flowFont(13)
                    .foregroundColor(FlowPalette.secondaryText)
            }

            if let notice = model.notice {
                noticeBanner(notice)
                    .padding(.top, 10)
            }

            if steps.isEmpty {
                Text("还没有节点，先添加一个节点。")
                    .flowFont(14)
                    .foregroundColor(FlowPalette.secondaryText)
                    .padding(.top, 12)
            } else {
                VStack(spacing: 12) {
                    ForEach(Array(steps.enumerated()), id: \.element.id) { index, step in
                        stepCard(
                            step: step,
                            index: index,
                            isFirst: index == 0,
                            isLast: index == steps.count - 1
                        )
                    }
                }
                .padding(.top, 12)
            }

            Button {
                cardForm = CardStepForm.new(type: .count)
            } label: {
                HStack(spacing: 6) {
                    Text("+")
                        .flowFont(19, weight: .medium)
                    Text("添加节点")
                        .flowFont(15, weight: .medium)
                }
                .foregroundColor(FlowPalette.primary)
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .contentShape(Rectangle())
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(
                            Color(red: 0.784, green: 0.835, blue: 0.922),
                            style: StrokeStyle(lineWidth: 1, dash: [8, 6])
                        )
                )
            }
            .buttonStyle(.plain)
            .padding(.top, 14)
            .accessibilityLabel("添加节点")
            .accessibilityIdentifier("add-node")
        }
    }

    private func stepCard(
        step: RoutineStep,
        index: Int,
        isFirst: Bool,
        isLast: Bool
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Text("\(index + 1)")
                    .flowFont(14, weight: .semibold)
                    .foregroundColor(FlowPalette.primary)
                    .frame(width: 30, height: 30)
                    .background(FlowPalette.primarySoft)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                Text(step.name)
                    .flowFont(17, weight: .semibold)
                    .foregroundColor(FlowPalette.text)
                    .lineLimit(1)
                Spacer()
            }

            HStack {
                FlowTypeBadge(type: step.type)
                Spacer()
                Text(stepDetail(step))
                    .flowFont(15, weight: .medium)
                    .foregroundColor(FlowPalette.text)
            }
            .padding(.top, 10)

            Rectangle()
                .fill(FlowPalette.outline)
                .frame(height: 1)
                .padding(.top, 12)

            HStack(spacing: 14) {
                Button("编辑") {
                    cardForm = CardStepForm.from(step)
                }
                Button("复制") {
                    _ = model.duplicateStep(id: step.id)
                }
                Button("上移") {
                    _ = model.moveStep(from: index, to: index - 1)
                }
                .disabled(isFirst)
                Button("下移") {
                    _ = model.moveStep(from: index, to: index + 1)
                }
                .disabled(isLast)
                Spacer()
                Button("删除") {
                    stepToDelete = step
                    showDeleteConfirmation = true
                }
                .foregroundColor(FlowPalette.invalid)
            }
            .flowFont(14)
            .foregroundColor(FlowPalette.primary)
            .buttonStyle(.plain)
            .padding(.top, 8)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlowPalette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(FlowPalette.outline, lineWidth: 1)
        )
    }

    private func stepDetail(_ step: RoutineStep) -> String {
        switch step.type {
        case .count:
            return "\(step.target ?? 0) 次"
        case .timer:
            return formatDurationLabel(step.durationMs)
        }
    }

    private func formatDurationLabel(_ durationMs: Int64?) -> String {
        let seconds = (durationMs ?? 0) / 1_000
        if seconds > 0 && seconds % 3_600 == 0 {
            return "\(seconds / 3_600) 小时"
        }
        if seconds > 0 && seconds % 60 == 0 {
            return "\(seconds / 60) 分钟"
        }
        return "\(seconds) 秒"
    }

    private func saveCardForm(_ form: CardStepForm) -> String? {
        let targetValue = Int(form.target)
        let durationSeconds = Int64(form.durationSeconds)
        let durationMs: Int64?
        if let durationSeconds, durationSeconds <= Int64.max / 1_000 {
            durationMs = durationSeconds * 1_000
        } else {
            durationMs = nil
        }
        let repeatValue = 1

        if form.type == .count && targetValue == nil {
            return "请输入有效的目标次数。"
        }
        if form.type == .timer && durationMs == nil {
            return "请输入有效的计时时长（秒）。"
        }
        let candidate = StepCandidate(
            name: form.name,
            type: form.type,
            target: form.type == .count ? targetValue : nil,
            countMode: form.type == .count ? form.countMode : nil,
            durationMs: form.type == .timer ? durationMs : nil,
            category: form.type == .timer ? form.category : nil,
            repeatCount: repeatValue
        )

        let result: String?
        if let editingID = form.editingID {
            let step = RoutineStep(
                id: editingID,
                name: candidate.name,
                type: candidate.type,
                target: candidate.target,
                countMode: candidate.countMode,
                durationMs: candidate.durationMs,
                category: candidate.category,
                repeatCount: candidate.repeatCount
            )
            result = model.updateStep(step)
        } else {
            result = model.addStep(candidate)
        }

        if result == nil {
            cardForm = nil
        }
        return result
    }

    private var footer: some View {
        Text("保存后返回流程列表，点击“开始”运行流程。")
            .flowFont(11)
            .foregroundColor(FlowPalette.secondaryText)
            .frame(maxWidth: .infinity)
    }

    private var startupError: some View {
        VStack(spacing: 10) {
            Text("Flow")
                .flowFont(30, weight: .semibold)
                .foregroundColor(FlowPalette.text)
            Text(model.startupError ?? "编辑器初始化失败，请稍后再试。")
                .flowFont(14, weight: .regular)
                .foregroundColor(FlowPalette.secondaryText)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
    }

    private func syncLabel(_ status: SyncStatus) -> String {
        switch status {
        case .synced:
            return "已同步"
        case .dirty:
            return "有未提交修改"
        case .invalid:
            return "存在输入错误"
        }
    }

    private func syncColor(_ status: SyncStatus) -> Color {
        switch status {
        case .synced:
            return FlowPalette.synced
        case .dirty:
            return FlowPalette.dirty
        case .invalid:
            return FlowPalette.invalid
        }
    }
}

private struct EditorLineError: Identifiable {
    let id: Int
    let lineNumber: Int
    let status: ParseStatus
    let raw: String
    let message: String
}
