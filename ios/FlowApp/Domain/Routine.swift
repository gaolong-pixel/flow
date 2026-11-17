import Foundation

struct Routine: Identifiable, Equatable {
    static let currentSchemaVersion = 1

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
}
