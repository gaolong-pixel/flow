import Foundation

final class PlanStore {
    private let fileURL: URL
    private let lock = NSLock()
    private struct Document: Codable { let schemaVersion: Int; let plans: [PlanDTO] }
    private struct PlanDTO: Codable {
        let id: String; let name: String; let createdAt: Int64; let updatedAt: Int64
        let steps: [StepDTO]
    }
    private struct StepDTO: Codable {
        let id: String; let name: String; let type: String
        let target: Int?; let durationMs: Int64?
    }
    init(fileURL: URL) { self.fileURL = fileURL }
    func loadAll() throws -> [Routine] {
        lock.lock(); defer { lock.unlock() }
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return [] }
        let data = try Data(contentsOf: fileURL)
        let doc = try JSONDecoder().decode(Document.self, from: data)
        guard doc.schemaVersion == 1 else { throw CocoaError(.fileReadCorruptFile) }
        return try doc.plans.map { plan in
            let steps = try plan.steps.map { step -> RoutineStep in
                guard let type = StepType(rawValue: step.type) else { throw CocoaError(.fileReadCorruptFile) }
                if type == .count {
                    guard let target = step.target, target > 0, step.durationMs == nil else {
                        throw CocoaError(.fileReadCorruptFile)
                    }
                    return RoutineStep(id: step.id, name: step.name, type: .count,
                                       target: target, countMode: .complete, repeatCount: 1)
                }
                guard let duration = step.durationMs, duration > 0, step.target == nil else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                return RoutineStep(id: step.id, name: step.name, type: .timer,
                                   durationMs: duration, category: .normal, repeatCount: 1)
            }
            let routine = Routine(id: plan.id, name: plan.name, version: 1, repeatCount: 1,
                                  steps: steps, createdAt: plan.createdAt, updatedAt: plan.updatedAt)
            guard routine.isValid else { throw CocoaError(.fileReadCorruptFile) }
            return routine
        }
    }
    func save(_ plan: Routine) throws {
        // A plan with no steps is an unsaved draft, never a valid record.
        guard !plan.steps.isEmpty else {
            throw CocoaError(.validationMissingMandatoryProperty)
        }
        lock.lock(); defer { lock.unlock() }
        var plans = try loadAllUnlocked()
        if let i = plans.firstIndex(where: { $0.id == plan.id }) { plans[i] = plan } else { plans.append(plan) }
        try writeUnlocked(plans)
    }
    func delete(id: String) throws {
        lock.lock(); defer { lock.unlock() }
        try writeUnlocked(try loadAllUnlocked().filter { $0.id != id })
    }
    private func loadAllUnlocked() throws -> [Routine] {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return [] }
        let data = try Data(contentsOf: fileURL)
        let doc = try JSONDecoder().decode(Document.self, from: data)
        guard doc.schemaVersion == 1 else { throw CocoaError(.fileReadCorruptFile) }
        return try doc.plans.map { plan in
            let steps = try plan.steps.map { step -> RoutineStep in
                guard let type = StepType(rawValue: step.type) else { throw CocoaError(.fileReadCorruptFile) }
                if type == .count {
                    guard let target = step.target, target > 0, step.durationMs == nil else { throw CocoaError(.fileReadCorruptFile) }
                    return RoutineStep(id: step.id, name: step.name, type: .count, target: target,
                                       countMode: .complete, repeatCount: 1)
                }
                guard let duration = step.durationMs, duration > 0, step.target == nil else { throw CocoaError(.fileReadCorruptFile) }
                return RoutineStep(id: step.id, name: step.name, type: .timer, durationMs: duration,
                                   category: .normal, repeatCount: 1)
            }
            let routine = Routine(id: plan.id, name: plan.name, version: 1, repeatCount: 1,
                                  steps: steps, createdAt: plan.createdAt, updatedAt: plan.updatedAt)
            guard routine.isValid else { throw CocoaError(.fileReadCorruptFile) }
            return routine
        }
    }
    private func writeUnlocked(_ plans: [Routine]) throws {
        let dto = Document(schemaVersion: 1, plans: plans.map { plan in
            PlanDTO(id: plan.id, name: plan.name, createdAt: plan.createdAt, updatedAt: plan.updatedAt,
                    steps: plan.steps.map { step in
                        StepDTO(id: step.id, name: step.name, type: step.type.rawValue,
                                target: step.target, durationMs: step.durationMs)
                    })
        })
        let data = try JSONEncoder().encode(dto)
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try data.write(to: fileURL, options: .atomic)
    }
}
