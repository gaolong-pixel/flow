import Foundation

struct RoutineStep: Identifiable, Equatable {
    let id: String
    let name: String
    let type: StepType
    let target: Int?
    let countMode: CountMode?
    let durationMs: Int64?
    let category: TimerCategory?
    let repeatCount: Int

    init(
        id: String,
        name: String,
        type: StepType,
        target: Int? = nil,
        countMode: CountMode? = nil,
        durationMs: Int64? = nil,
        category: TimerCategory? = nil,
        repeatCount: Int = 1
    ) {
        self.id = id
        // Step names are normalized at the model boundary; internal
        // whitespace is preserved.
        self.name = name.trimmingFlowWhitespace
        self.type = type
        self.target = target
        self.countMode = countMode
        self.durationMs = durationMs
        self.category = category
        self.repeatCount = repeatCount
    }

    var validationErrors: [ValidationError] {
        var errors: [ValidationError] = []

        if name.isBlankStepName {
            errors.append(.emptyStepName)
        }
        if repeatCount < 1 {
            errors.append(.invalidStepRepeatCount)
        }

        switch type {
        case .count:
            if target == nil || (target ?? 0) <= 0 {
                errors.append(.invalidCountTarget)
            }
            if countMode == nil {
                errors.append(.missingCountMode)
            }
            if durationMs != nil || category != nil {
                errors.append(.countHasTimerFields)
            }
        case .timer:
            if durationMs == nil || (durationMs ?? 0) < 1_000 || (durationMs ?? 0) % 1_000 != 0 {
                errors.append(.invalidTimerDuration)
            }
            if category == nil {
                errors.append(.missingTimerCategory)
            }
            if target != nil || countMode != nil {
                errors.append(.timerHasCountFields)
            }
        }

        return errors
    }

    var isValid: Bool {
        validationErrors.isEmpty
    }

    /// Copies a step with a new identity while preserving every other field.
    func duplicated(newID: String) -> RoutineStep {
        RoutineStep(
            id: newID,
            name: name,
            type: type,
            target: target,
            countMode: countMode,
            durationMs: durationMs,
            category: category,
            repeatCount: repeatCount
        )
    }

    static func count(
        id: String = UUID().uuidString,
        name: String,
        target: Int,
        countMode: CountMode = .increment,
        repeatCount: Int = 1
    ) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .count,
            target: target,
            countMode: countMode,
            repeatCount: repeatCount
        )
    }

    static func timer(
        id: String = UUID().uuidString,
        name: String,
        durationMs: Int64,
        category: TimerCategory = .normal,
        repeatCount: Int = 1
    ) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .timer,
            durationMs: durationMs,
            category: category,
            repeatCount: repeatCount
        )
    }
}

extension Array where Element == RoutineStep {
    var duplicateStepIDs: Set<String> {
        Dictionary(grouping: self, by: \.id)
            .filter { $0.value.count > 1 }
            .keys
            .reduce(into: Set<String>()) { $0.insert($1) }
    }
}


extension String {
    static let stepNameWhitespace: Set<Unicode.Scalar> = {
        let codePoints: [UInt32] = [
            0x0009, 0x000A, 0x000B, 0x000C, 0x000D,
            0x0020, 0x0085, 0x00A0, 0x1680,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000
        ] + Array(0x2000...0x200A).map { UInt32($0) }
        return Set(codePoints.compactMap { Unicode.Scalar($0) })
    }()

    static let flowWhitespaceCharacterSet: CharacterSet = {
        let characters = stepNameWhitespace.map { String($0) }.joined()
        return CharacterSet(charactersIn: characters)
    }()

    /// Explicit Unicode White_Space rule shared with the Kotlin implementation.
    var isBlankStepName: Bool {
        unicodeScalars.allSatisfy { Self.stepNameWhitespace.contains($0) }
    }
}
