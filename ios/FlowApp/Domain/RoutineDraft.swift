import Foundation

enum DraftError: Error, Equatable {
    case invalidDraft([ValidationError])
    case invalidStep([ValidationError])
    case duplicateStepID(String)
    case stepNotFound(String)
    case invalidMove(Int, Int)
    case invalidRepeatCount(Int)
}

/// The single editable source of truth for an in-progress routine.
///
/// Step order is defined only by `steps`; there is no per-step order field.
struct RoutineDraft: Identifiable, Equatable {
    let id: String
    let name: String
    let version: Int
    let repeatCount: Int
    let steps: [RoutineStep]
    let createdAt: Int64
    let updatedAt: Int64

    init(
        id: String,
        name: String,
        version: Int = Routine.currentSchemaVersion,
        repeatCount: Int = 1,
        steps: [RoutineStep] = [],
        createdAt: Int64,
        updatedAt: Int64
    ) {
        self.id = id
        self.name = name
        self.version = version
        self.repeatCount = repeatCount
        self.steps = steps
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    var validationErrors: [ValidationError] {
        var errors: [ValidationError] = []

        if repeatCount < 1 {
            errors.append(.invalidRoutineRepeatCount)
        }
        for _ in steps.duplicateStepIDs {
            errors.append(.duplicateStepID)
        }
        for step in steps {
            errors.append(contentsOf: step.validationErrors)
        }

        return errors
    }

    var isValid: Bool {
        validationErrors.isEmpty
    }

    func validated() throws -> RoutineDraft {
        let errors = validationErrors
        if !errors.isEmpty {
            throw DraftError.invalidDraft(errors)
        }
        return self
    }

    func renaming(_ newName: String) -> RoutineDraft {
        RoutineDraft(
            id: id,
            name: newName,
            version: version,
            repeatCount: repeatCount,
            steps: steps,
            createdAt: createdAt,
            updatedAt: Self.nowMillis()
        )
    }

    func settingRepeatCount(_ newCount: Int) throws -> RoutineDraft {
        guard newCount >= 1 else {
            throw DraftError.invalidRepeatCount(newCount)
        }
        return RoutineDraft(
            id: id,
            name: name,
            version: version,
            repeatCount: newCount,
            steps: steps,
            createdAt: createdAt,
            updatedAt: Self.nowMillis()
        )
    }

    func addingStep(_ step: RoutineStep) throws -> RoutineDraft {
        guard !steps.contains(where: { $0.id == step.id }) else {
            throw DraftError.duplicateStepID(step.id)
        }
        guard step.isValid else {
            throw DraftError.invalidStep(step.validationErrors)
        }

        return try RoutineDraft(
            id: id,
            name: name,
            version: version,
            repeatCount: repeatCount,
            steps: steps + [step],
            createdAt: createdAt,
            updatedAt: Self.nowMillis()
        ).validated()
    }

    func updatingStep(_ step: RoutineStep) throws -> RoutineDraft {
        guard let index = steps.firstIndex(where: { $0.id == step.id }) else {
            throw DraftError.stepNotFound(step.id)
        }
        guard step.isValid else {
            throw DraftError.invalidStep(step.validationErrors)
        }

        var updatedSteps = steps
        updatedSteps[index] = step

        return try RoutineDraft(
            id: id,
            name: name,
            version: version,
            repeatCount: repeatCount,
            steps: updatedSteps,
            createdAt: createdAt,
            updatedAt: Self.nowMillis()
        ).validated()
    }

    func deletingStep(id stepID: String) throws -> RoutineDraft {
        guard steps.contains(where: { $0.id == stepID }) else {
            throw DraftError.stepNotFound(stepID)
        }

        return try RoutineDraft(
            id: id,
            name: name,
            version: version,
            repeatCount: repeatCount,
            steps: steps.filter { $0.id != stepID },
            createdAt: createdAt,
            updatedAt: Self.nowMillis()
        ).validated()
    }

    func movingStep(from: Int, to: Int) throws -> RoutineDraft {
        guard steps.indices.contains(from), steps.indices.contains(to) else {
            throw DraftError.invalidMove(from, to)
        }
        guard from != to else {
            return self
        }

        var reordered = steps
        let step = reordered.remove(at: from)
        reordered.insert(step, at: to)

        return try RoutineDraft(
            id: id,
            name: name,
            version: version,
            repeatCount: repeatCount,
            steps: reordered,
            createdAt: createdAt,
            updatedAt: Self.nowMillis()
        ).validated()
    }

    func duplicatingStep(id stepID: String) throws -> RoutineDraft {
        guard let index = steps.firstIndex(where: { $0.id == stepID }) else {
            throw DraftError.stepNotFound(stepID)
        }

        let source = steps[index]
        let copy = source.duplicated(newID: UUID().uuidString)

        var duplicated = steps
        duplicated.insert(copy, at: index + 1)

        return try RoutineDraft(
            id: id,
            name: name,
            version: version,
            repeatCount: repeatCount,
            steps: duplicated,
            createdAt: createdAt,
            updatedAt: Self.nowMillis()
        ).validated()
    }

    func toRoutine() throws -> Routine {
        _ = try validated()
        return Routine(
            id: id,
            name: name,
            version: version,
            repeatCount: repeatCount,
            steps: steps,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }

    static func new(
        name: String = "",
        repeatCount: Int = 1,
        steps: [RoutineStep] = [],
        id: String = UUID().uuidString,
        now: Int64 = nowMillis()
    ) throws -> RoutineDraft {
        try RoutineDraft(
            id: id,
            name: name,
            version: Routine.currentSchemaVersion,
            repeatCount: repeatCount,
            steps: steps,
            createdAt: now,
            updatedAt: now
        ).validated()
    }

    static func from(_ routine: Routine) throws -> RoutineDraft {
        try RoutineDraft(
            id: routine.id,
            name: routine.name,
            version: routine.version,
            repeatCount: routine.repeatCount,
            steps: routine.steps,
            createdAt: routine.createdAt,
            updatedAt: routine.updatedAt
        ).validated()
    }

    private static func nowMillis() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1_000).rounded())
    }
}
