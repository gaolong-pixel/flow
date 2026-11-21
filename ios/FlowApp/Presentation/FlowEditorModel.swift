import Foundation
import SwiftUI

struct UiNotice: Equatable {
    let message: String
    let isError: Bool
}

@MainActor
final class FlowEditorModel: ObservableObject {
    @Published private(set) var state: EditorState?
    @Published private(set) var editorText: String = ""
    @Published private(set) var planName: String = ""
    @Published private(set) var nameError: String?
    @Published private(set) var startupError: String?
    @Published private(set) var modeMessage: String?
    @Published private(set) var notice: UiNotice?

    private var controller: EditorController?

    private let persist: ((Routine) throws -> Void)?

    /// Draft saving is only legal once at least one valid node exists.
    var canSave: Bool {
        guard let state else { return false }
        if state.mode == .text {
            return !state.hasBlockingErrors && !state.parseResult.steps.isEmpty
        }
        return !state.draft.steps.isEmpty
    }

    init(draft: RoutineDraft? = nil, persist: ((Routine) throws -> Void)? = nil) {
        self.persist = persist
        let now = Int64((Date().timeIntervalSince1970 * 1_000).rounded())
        let initialDraft = draft ?? RoutineDraft(
            id: UUID().uuidString,
            name: "未命名计划",
            steps: [],
            createdAt: now,
            updatedAt: now
        )
        planName = initialDraft.name

        do {
            let controller = try EditorController(initialDraft: initialDraft, initialMode: .cards)
            self.controller = controller
            self.state = controller.state
            self.editorText = controller.state.textBuffer
        } catch {
            self.startupError = "编辑器初始化失败，请稍后再试。"
        }
    }

    func updateName(_ value: String) {
        planName = value
        nameError = nil
        notice = nil
    }

    func updateText(_ text: String) {
        guard let controller else { return }
        editorText = text
        state = controller.onTextChanged(text)
        notice = nil
        modeMessage = nil
    }

    func applyText() {
        guard let controller, state?.mode == .text else { return }
        let result = controller.commitText()
        state = result.state
        switch result {
        case .success(let resultState):
            editorText = resultState.textBuffer
            notice = UiNotice(message: "已应用文本修改。", isError: false)
            modeMessage = nil
        case .failure(let error, _):
            notice = UiNotice(message: commitErrorMessage(error), isError: true)
        }
    }

    func savePlan() {
        guard let controller else { return }
        guard canSave else {
            notice = UiNotice(
                message: "至少添加一个节点后才能保存流程。",
                isError: true
            )
            modeMessage = nil
            return
        }

        switch controller.rename(planName) {
        case .failure(let error, let resultState):
            state = resultState
            nameError = nameErrorMessage(error)
            notice = UiNotice(
                message: nameError ?? "流程名称不合法。",
                isError: true
            )
            modeMessage = nil

        case .success(let renamedState):
            state = renamedState
            planName = renamedState.draft.name
            nameError = nil

            if renamedState.mode == .cards {
                // Card edits live in the draft directly; persist the draft and
                // the name together. The name is not part of the step text.
                do {
                    try persist?(renamedState.draft.toRoutine())
                    notice = UiNotice(message: "已保存修改。", isError: false)
                } catch {
                    notice = UiNotice(message: "保存到计划存储失败。", isError: true)
                }
                modeMessage = nil
                return
            }

            let result = controller.commitText()
            state = result.state

            switch result {
            case .success(let resultState):
                editorText = resultState.textBuffer
                do {
                    try persist?(resultState.draft.toRoutine())
                    notice = UiNotice(message: "已保存修改。", isError: false)
                } catch {
                    notice = UiNotice(message: "保存到计划存储失败。", isError: true)
                }
                modeMessage = nil
            case .failure(let error, _):
                notice = UiNotice(message: commitErrorMessage(error), isError: true)
            }
        }
    }

    func restore() {
        guard let controller else { return }
        let result = controller.restoreLastCommitted()
        state = result.state

        switch result {
        case .success(let resultState):
            editorText = resultState.textBuffer
            notice = UiNotice(message: "已恢复上次保存。", isError: false)
            modeMessage = nil
        case .failure(let error, _):
            notice = UiNotice(message: commitErrorMessage(error), isError: true)
        }
    }

    func addStep(_ candidate: StepCandidate) -> String? {
        guard let controller else { return "编辑器尚未就绪。" }
        return perform(controller.addStep(candidate), successMessage: "已添加步骤。")
    }

    func updateStep(_ step: RoutineStep) -> String? {
        guard let controller else { return "编辑器尚未就绪。" }
        return perform(controller.updateStep(step), successMessage: "已更新步骤。")
    }

    func deleteStep(id: String) -> String? {
        guard let controller else { return "编辑器尚未就绪。" }
        return perform(controller.deleteStep(id: id), successMessage: "已删除步骤。")
    }

    func duplicateStep(id: String) -> String? {
        guard let controller else { return "编辑器尚未就绪。" }
        return perform(controller.duplicateStep(id: id), successMessage: "已复制步骤。")
    }

    func moveStep(from: Int, to: Int) -> String? {
        guard let controller else { return "编辑器尚未就绪。" }
        return perform(
            controller.moveStep(from: from, to: to),
            successMessage: "已调整步骤顺序。"
        )
    }

    private func perform(_ result: EditorResult, successMessage: String) -> String? {
        state = result.state
        switch result {
        case .success(let resultState):
            editorText = resultState.textBuffer
            notice = UiNotice(message: successMessage, isError: false)
            modeMessage = nil
            return nil
        case .failure(let error, _):
            let message = commitErrorMessage(error)
            notice = UiNotice(message: message, isError: true)
            return message
        }
    }

    func selectMode(_ mode: EditorMode) {
        guard let controller, state?.mode != mode else { return }
        let result = controller.switchMode(mode)
        state = result.state

        switch result {
        case .success(let resultState):
            modeMessage = nil
            if resultState.mode == .text {
                editorText = resultState.textBuffer
            }
        case .failure(let error, let resultState):
            modeMessage = modeFailureMessage(error: error, state: resultState)
        }
    }

    private func modeFailureMessage(error: EditorError, state: EditorState) -> String {
        switch error {
        case .textBlocked:
            if state.hasBlockingErrors {
                return "当前文本存在输入错误，请修复后再切换到卡片模式。"
            }
            return "当前文本有未提交的修改，请先提交后再切换。"
        case .serializationFailed:
            return "当前流程暂时无法转换，请检查内容。"
        default:
            return "暂时无法切换编辑模式，请稍后再试。"
        }
    }

    private func nameErrorMessage(_ error: EditorError) -> String? {
        switch error {
        case .invalidName:
            return "流程名称不能为空。"
        case .nameTooLong:
            return "流程名称最多 \(EditorController.maxNameLength) 个字符。"
        default:
            return nil
        }
    }

    private func commitErrorMessage(_ error: EditorError) -> String {
        switch error {
        case .textBlocked:
            return "文本仍存在未完成或不合法的行，请修复后再保存。"
        case .draftInvalid:
            return "步骤数据校验未通过，请检查输入内容。"
        case .invalidStep:
            return "存在不合法的步骤，请检查名称、数量或单位。"
        case .duplicateStepID:
            return "步骤标识重复，暂时无法保存。"
        case .stepNotFound:
            return "目标步骤不存在，请重新编辑。"
        case .invalidMove:
            return "步骤顺序调整失败。"
        case .invalidRepeatCount:
            return "重复次数必须大于零。"
        case .invalidName:
            return "流程名称不能为空。"
        case .nameTooLong:
            return "流程名称最多 \(EditorController.maxNameLength) 个字符。"
        case .serializationFailed:
            return "当前内容暂时无法转换为规范文本，请检查输入。"
        case .wrongMode:
            return "当前模式下无法执行该操作。"
        }
    }
}
