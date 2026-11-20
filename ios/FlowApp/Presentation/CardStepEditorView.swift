import SwiftUI

struct CardStepForm: Identifiable, Equatable {
    let id = UUID()
    let editingID: String?
    var type: StepType
    var name: String
    var target: String
    var countMode: CountMode
    var durationSeconds: String
    var category: TimerCategory
    var repeatCount: String

    static func new(type: StepType) -> CardStepForm {
        CardStepForm(
            editingID: nil,
            type: type,
            name: "新步骤",
            target: "10",
            countMode: .increment,
            durationSeconds: "60",
            category: .normal,
            repeatCount: "1"
        )
    }

    static func from(_ step: RoutineStep) -> CardStepForm {
        CardStepForm(
            editingID: step.id,
            type: step.type,
            name: step.name,
            target: "\(step.target ?? 10)",
            countMode: step.countMode ?? .increment,
            durationSeconds: "\((step.durationMs ?? 60_000) / 1_000)",
            category: step.category ?? .normal,
            repeatCount: "\(step.repeatCount)"
        )
    }
}

struct CardStepEditorView: View {
    let initial: CardStepForm
    let onSave: (CardStepForm) -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var form: CardStepForm
    @State private var errorMessage: String?

    init(form: CardStepForm, onSave: @escaping (CardStepForm) -> String?) {
        self.initial = form
        self.onSave = onSave
        _form = State(initialValue: form)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("步骤类型", selection: $form.type) {
                        Text("计数步骤").tag(StepType.count)
                        Text("计时步骤").tag(StepType.timer)
                    }
                    .pickerStyle(.segmented)

                    TextField("步骤名称", text: $form.name)
                }

                if form.type == .count {
                    Section("计数设置") {
                        TextField("目标次数", text: $form.target)
                            .keyboardType(.numberPad)

                    }
                } else {
                    Section("计时设置") {
                        TextField("计时时长（秒）", text: $form.durationSeconds)
                            .keyboardType(.numberPad)

                    }
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .flowFont(13)
                            .foregroundColor(FlowPalette.invalid)
                    }
                }
            }
            .navigationTitle(initial.editingID == nil ? "添加节点" : "编辑节点")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        save()
                    }
                }
            }
            .onChange(of: form.type) { newType in
                errorMessage = nil
                if newType == .count && form.target.isEmpty {
                    form.target = "10"
                }
                if newType == .timer && form.durationSeconds.isEmpty {
                    form.durationSeconds = "60"
                }
            }
            .onChange(of: form.name) { _ in errorMessage = nil }
            .onChange(of: form.target) { _ in errorMessage = nil }
            .onChange(of: form.durationSeconds) { _ in errorMessage = nil }
            .onChange(of: form.repeatCount) { _ in errorMessage = nil }
            .onChange(of: form.countMode) { _ in errorMessage = nil }
            .onChange(of: form.category) { _ in errorMessage = nil }
        }
        .tint(FlowPalette.primary)
        .preferredColorScheme(.light)
        .presentationDetents([.medium, .large])
    }

    private func save() {
        if let error = onSave(form) {
            errorMessage = error
        } else {
            dismiss()
        }
    }
}
