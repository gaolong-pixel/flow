import SwiftUI

struct FlowRootView: View {
    var onBack: () -> Void = {}

    @StateObject private var model = FlowEditorModel()
    @State private var showRestoreConfirmation = false
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
        ScrollView {
            VStack(spacing: 0) {
                header
                Spacer().frame(height: 14)
                flowNameCard(name: state.draft.name)
                Spacer().frame(height: 16)
                modeSection(selected: state.mode)
                Spacer().frame(height: 12)
                syncSection(status: state.syncStatus)

                if let message = model.modeMessage {
                    Spacer().frame(height: 10)
                    modeMessage(message)
                }

                Spacer().frame(height: 16)
                content(state: state)
                Spacer().frame(height: 16)
                footer
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 16)
        }
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
    }

    private var header: some View {
        HStack(spacing: 12) {
            Text("F")
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(.white)
                .frame(width: 40, height: 40)
                .background(FlowPalette.primary)
                .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text("Flow")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundColor(FlowPalette.text)
                Text("流程编辑器")
                    .font(.system(size: 12, weight: .regular))
                    .foregroundColor(FlowPalette.secondaryText)
            }

            Spacer()

            Button(action: onBack) {
                HStack(spacing: 4) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 12, weight: .semibold))
                    Text("返回")
                        .font(.system(size: 14, weight: .medium))
                }
                .foregroundColor(FlowPalette.primary)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("返回")
        }
    }

    private func flowNameCard(name: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("当前流程")
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(FlowPalette.secondaryText)
            Text(name)
                .font(.system(size: 22, weight: .semibold))
                .foregroundColor(FlowPalette.text)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlowPalette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(FlowPalette.outline, lineWidth: 1)
        )
    }

    private func modeSection(selected: EditorMode) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("编辑模式")
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(FlowPalette.secondaryText)

            HStack(spacing: 4) {
                modeButton("文本", mode: .text, selected: selected)
                modeButton("卡片", mode: .cards, selected: selected)
            }
            .padding(4)
            .background(FlowPalette.segmentBackground)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
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
                .font(.system(size: 15, weight: isSelected ? .semibold : .medium))
                .foregroundColor(isSelected ? FlowPalette.primary : FlowPalette.secondaryText)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 11)
                .background(isSelected ? FlowPalette.surface : Color.clear)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(isSelected ? FlowPalette.outline : Color.clear, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }

    private func syncSection(status: SyncStatus) -> some View {
        HStack(spacing: 8) {
            Text("同步状态")
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(FlowPalette.secondaryText)

            HStack(spacing: 6) {
                Circle()
                    .fill(syncColor(status))
                    .frame(width: 6, height: 6)
                Text(syncLabel(status))
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(syncColor(status))
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(syncColor(status).opacity(0.10))
            .clipShape(Capsule())

            Spacer()
        }
    }

    private func modeMessage(_ message: String) -> some View {
        Text(message)
            .font(.system(size: 13, weight: .regular))
            .foregroundColor(FlowPalette.invalid)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(FlowPalette.invalid.opacity(0.07))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(FlowPalette.invalid.opacity(0.18), lineWidth: 1)
            )
    }

    @ViewBuilder
    private func content(state: EditorState) -> some View {
        Group {
            switch state.mode {
            case .text:
                textModeContent(state: state)
            case .cards:
                cardsModeContent(state: state)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 330, alignment: .topLeading)
        .background(FlowPalette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(FlowPalette.outline, lineWidth: 1)
        )
    }

    private func textModeContent(state: EditorState) -> some View {
        let errors = lineErrors(state)
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("文本编辑")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(FlowPalette.text)
                Spacer()
                Text("文本模式")
                    .font(.system(size: 11, weight: .regular))
                    .foregroundColor(FlowPalette.secondaryText)
            }

            formatHint

            if let notice = model.notice {
                noticeBanner(notice)
            }

            if !errors.isEmpty {
                VStack(spacing: 6) {
                    ForEach(errors) { error in
                        errorRow(error)
                    }
                }
            }

            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(FlowPalette.background)
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(FlowPalette.outline.opacity(0.8), lineWidth: 1)
                    )

                TextEditor(
                    text: Binding(
                        get: { model.editorText },
                        set: { model.updateText($0) }
                    )
                )
                .font(.system(size: 15, design: .monospaced))
                .foregroundColor(FlowPalette.text)
                .scrollContentBackground(.hidden)
                .background(Color.clear)
                .padding(10)

                if model.editorText.isEmpty {
                    Text("在这里输入流程步骤，一行一个步骤。")
                        .font(.system(size: 15, weight: .regular))
                        .foregroundColor(FlowPalette.secondaryText)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 18)
                        .allowsHitTesting(false)
                }
            }
            .frame(minHeight: 220, maxHeight: 280)

            HStack(spacing: 10) {
                Button(action: model.save) {
                    Text("保存修改")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(FlowPalette.primary)
                        .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                }
                .buttonStyle(.plain)

                Button {
                    showRestoreConfirmation = true
                } label: {
                    Text("恢复上次保存")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(FlowPalette.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(FlowPalette.surface)
                        .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 15, style: .continuous)
                                .stroke(FlowPalette.outline, lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(18)
    }

    private var formatHint: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("格式示例")
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(FlowPalette.primary)
            Text("俯卧撑 15次 · 阅读 20分钟 · 拉伸 45秒 x2")
                .font(.system(size: 13, weight: .regular))
                .foregroundColor(FlowPalette.text)
            Text("一行一个步骤；数字和单位表示目标；x2 表示总共执行两次。")
                .font(.system(size: 11, weight: .regular))
                .foregroundColor(FlowPalette.secondaryText)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlowPalette.primarySoft.opacity(0.55))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func noticeBanner(_ notice: UiNotice) -> some View {
        let color = notice.isError ? FlowPalette.invalid : FlowPalette.synced
        return Text(notice.message)
            .font(.system(size: 13, weight: .regular))
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
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(FlowPalette.invalid)
                Spacer()
                Text(error.status == .incomplete ? "未完成" : "不合法")
                    .font(.system(size: 11, weight: .regular))
                    .foregroundColor(FlowPalette.invalid)
            }
            Text("“\(error.raw.isEmpty ? "空行" : error.raw)”")
                .font(.system(size: 11, weight: .regular))
                .foregroundColor(FlowPalette.secondaryText)
                .lineLimit(1)
            Text(error.message)
                .font(.system(size: 13, weight: .regular))
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
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("步骤卡片")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(FlowPalette.text)
                    Text("点击编辑可修改步骤字段")
                        .font(.system(size: 11, weight: .regular))
                        .foregroundColor(FlowPalette.secondaryText)
                }
                Spacer()
                if !steps.isEmpty {
                    Button("添加步骤") {
                        cardForm = CardStepForm.new(type: .count)
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(FlowPalette.primary)
                }
            }

            if let notice = model.notice {
                noticeBanner(notice)
            }

            if steps.isEmpty {
                emptyCards
            } else {
                ForEach(Array(steps.enumerated()), id: \.element.id) { index, step in
                    stepCard(
                        step: step,
                        index: index,
                        isFirst: index == 0,
                        isLast: index == steps.count - 1
                    )
                }
            }

            Spacer(minLength: 0)
        }
        .padding(18)
    }

    private var emptyCards: some View {
        VStack(spacing: 6) {
            Text("还没有步骤")
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(FlowPalette.text)
            Text("添加第一个计数或计时步骤。")
                .font(.system(size: 13, weight: .regular))
                .foregroundColor(FlowPalette.secondaryText)
            Button("添加步骤") {
                cardForm = CardStepForm.new(type: .count)
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(FlowPalette.primary)
            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
            .padding(.top, 6)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .background(FlowPalette.background)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(FlowPalette.outline.opacity(0.8), lineWidth: 1)
        )
    }

    private func stepCard(
        step: RoutineStep,
        index: Int,
        isFirst: Bool,
        isLast: Bool
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Text("\(index + 1)")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(FlowPalette.primary)
                    .frame(width: 30, height: 30)
                    .background(FlowPalette.primarySoft)
                    .clipShape(Circle())

                VStack(alignment: .leading, spacing: 2) {
                    Text(step.name)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(FlowPalette.text)
                        .lineLimit(1)
                    Text(step.type == .count ? "计数步骤" : "计时步骤")
                        .font(.system(size: 11, weight: .regular))
                        .foregroundColor(FlowPalette.secondaryText)
                }

                Spacer()

                Button("编辑") {
                    cardForm = CardStepForm.from(step)
                }
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(FlowPalette.primary)
            }

            Text(stepDetail(step))
                .font(.system(size: 14, weight: .regular))
                .foregroundColor(FlowPalette.text)

            Text("总重复 \(step.repeatCount) 次")
                .font(.system(size: 11, weight: .regular))
                .foregroundColor(FlowPalette.secondaryText)

            HStack(spacing: 10) {
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
            .font(.system(size: 12, weight: .medium))
            .foregroundColor(FlowPalette.primary)
            .buttonStyle(.plain)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlowPalette.background)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(FlowPalette.outline.opacity(0.8), lineWidth: 1)
        )
    }

    private func stepDetail(_ step: RoutineStep) -> String {
        switch step.type {
        case .count:
            let mode: String
            switch step.countMode {
            case .increment: mode = "逐次计数"
            case .complete: mode = "整组完成"
            case .none: mode = "未设置计数模式"
            }
            return "目标 \(step.target ?? 0) 次 · \(mode)"
        case .timer:
            let category: String
            switch step.category {
            case .normal: category = "普通计时"
            case .rest: category = "休息"
            case .none: category = "未设置分类"
            }
            return "计时 \(formatDurationLabel(step.durationMs)) · \(category)"
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
        let repeatValue = Int(form.repeatCount)

        if form.type == .count && targetValue == nil {
            return "请输入有效的目标次数。"
        }
        if form.type == .timer && durationMs == nil {
            return "请输入有效的计时时长（秒）。"
        }
        guard let repeatValue else {
            return "请输入有效的重复次数。"
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
        VStack(spacing: 6) {
            Button(action: {}) {
                Text("开始执行")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(FlowPalette.secondaryText)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                    .background(FlowPalette.segmentBackground)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(true)

            Text("执行功能暂未开放")
                .font(.system(size: 11, weight: .regular))
                .foregroundColor(FlowPalette.secondaryText)
        }
    }

    private var startupError: some View {
        VStack(spacing: 10) {
            Text("Flow")
                .font(.system(size: 30, weight: .semibold))
                .foregroundColor(FlowPalette.text)
            Text(model.startupError ?? "编辑器初始化失败，请稍后再试。")
                .font(.system(size: 14, weight: .regular))
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

enum FlowPalette {
    static let background = Color(red: 0.969, green: 0.965, blue: 0.949)
    static let surface = Color.white
    static let primary = Color(red: 0.184, green: 0.420, blue: 0.373)
    static let primarySoft = Color(red: 0.898, green: 0.941, blue: 0.922)
    static let text = Color(red: 0.118, green: 0.137, blue: 0.122)
    static let secondaryText = Color(red: 0.424, green: 0.451, blue: 0.427)
    static let outline = Color(red: 0.894, green: 0.882, blue: 0.851)
    static let segmentBackground = Color(red: 0.929, green: 0.922, blue: 0.894)
    static let synced = Color(red: 0.176, green: 0.420, blue: 0.341)
    static let dirty = Color(red: 0.580, green: 0.384, blue: 0.000)
    static let invalid = Color(red: 0.702, green: 0.149, blue: 0.118)
}
