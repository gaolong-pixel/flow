import Foundation

enum StepType: String, Codable { case count = "COUNT", timer = "TIMER", manual = "MANUAL" }
enum CountMode: String, Codable { case increment = "INCREMENT", complete = "COMPLETE" }
enum TimerCategory: String, Codable { case normal = "NORMAL", rest = "REST" }

struct RoutineStep: Identifiable, Codable, Equatable {
    var id: String
    var name: String
    var type: StepType
    var target: Int?
    var countMode: CountMode?
    var durationMs: Int64?
    var category: TimerCategory?
    var repeatCount: Int
    var order: Int

    func validationErrors() -> [String] {
        var errors: [String] = []
        if name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { errors.append("步骤名称不能为空") }
        if repeatCount < 1 { errors.append("步骤重复次数必须大于 0") }
        switch type {
        case .count:
            if (target ?? 0) <= 0 { errors.append("次数目标必须大于 0") }
            if countMode == nil { errors.append("次数模式不能为空") }
            if durationMs != nil { errors.append("次数节点不能设置时长") }
        case .timer:
            if (durationMs ?? 0) <= 0 { errors.append("计时时长必须大于 0") }
            if target != nil || countMode != nil { errors.append("时间节点不能设置次数参数") }
        case .manual: break
        }
        return errors
    }
}

struct Routine: Identifiable, Codable {
    var id: String
    var name: String
    var version: Int
    var repeatCount: Int
    var steps: [RoutineStep]
    var createdAt: Int64
    var updatedAt: Int64
}
