import SwiftUI

struct FlowEditorView: View {
    private enum EditorMode: String, CaseIterable { case text = "文字", cards = "卡片" }
    @State private var routineName = "我的流程"
    @State private var text = "俯卧撑 15次\n休息 60秒\n深蹲 20次"
    @State private var mode: EditorMode = .text
    private let parser = RoutineTextParser()
    private var parsed: RoutineParseResult { parser.parse(text) }

    var body: some View {
        VStack(spacing: 16) {
            TextField("流程名称", text: $routineName)
                .textFieldStyle(.roundedBorder)

            Picker("编辑模式", selection: $mode) {
                ForEach(EditorMode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)

            if mode == .text {
                VStack(alignment: .leading, spacing: 8) {
                    Text("每行输入一个步骤").font(.headline)
                    TextEditor(text: $text)
                        .padding(8)
                        .frame(minHeight: 240)
                        .background(Color(uiColor: .secondarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                    Text("例如：俯卧撑 15次、休息 60秒、阅读 20分钟")
                        .font(.caption).foregroundStyle(.secondary)
                }
            } else {
                List(parsed.lines) { line in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(line.raw).font(.headline)
                        Text(line.message ?? line.step.map(summary) ?? "")
                            .font(.subheadline)
                            .foregroundStyle(line.message == nil ? .secondary : .red)
                    }
                    .padding(.vertical, 5)
                }
                .listStyle(.plain)
            }

            HStack {
                Text("识别到 \(parsed.steps.count) 个有效步骤")
                    .font(.footnote).foregroundStyle(.secondary)
                Spacer()
                Button("保存") { /* TODO: local Repository */ }
                    .buttonStyle(.bordered)
                Button("开始") { /* TODO: Execution Engine in Phase 2 */ }
                    .buttonStyle(.borderedProminent)
                    .disabled(parsed.steps.isEmpty || parsed.hasBlockingErrors)
            }
        }
        .padding()
        .navigationTitle("编辑流程")
    }

    private func summary(_ step: RoutineStep) -> String {
        switch step.type {
        case .count: return "次数 · \(step.target ?? 0) 次"
        case .timer: return "计时 · \((step.durationMs ?? 0) / 1000) 秒"
        case .manual: return "手动完成"
        }
    }
}
