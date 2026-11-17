import Foundation

enum SerializationError: String, Error, Equatable {
    case invalidStep = "INVALID_STEP"
    case invalidName = "INVALID_NAME"
    case unrepresentableDuration = "UNREPRESENTABLE_DURATION"
}

struct SerializationException: Error, Equatable {
    let code: SerializationError
    let stepIndex: Int
    let validationErrors: [ValidationError]

    init(
        code: SerializationError,
        stepIndex: Int,
        validationErrors: [ValidationError] = []
    ) {
        self.code = code
        self.stepIndex = stepIndex
        self.validationErrors = validationErrors
    }
}

/// Serializes candidates into the canonical V1.1 text form.
/// The operation is atomic: any invalid step prevents returning partial text.
struct RoutineTextSerializer {

    func serialize(_ steps: [StepCandidate]) throws -> String {
        if steps.isEmpty {
            return ""
        }

        var lines: [String] = []
        lines.reserveCapacity(steps.count)
        for (index, step) in steps.enumerated() {
            lines.append(try serializeStep(index: index, step: step))
        }
        return lines.joined(separator: "\n")
    }

    private func serializeStep(index: Int, step: StepCandidate) throws -> String {
        if step.name.contains("\n") || step.name.contains("\r") {
            throw SerializationException(code: .invalidName, stepIndex: index)
        }
        if !step.isValid {
            throw SerializationException(
                code: .invalidStep,
                stepIndex: index,
                validationErrors: step.validationErrors
            )
        }

        let name = step.name.trimmingFlowWhitespace
        if name.isEmpty {
            throw SerializationException(code: .invalidName, stepIndex: index)
        }

        let base: String
        switch step.type {
        case .count:
            guard let target = step.target else {
                throw SerializationException(code: .invalidStep, stepIndex: index)
            }
            base = "\(name) \(target)次"
        case .timer:
            guard let durationMs = step.durationMs else {
                throw SerializationException(code: .invalidStep, stepIndex: index)
            }
            base = "\(name) \(try formatDuration(index: index, durationMs: durationMs))"
        }

        let repeatPart = step.repeatCount > 1 ? " x \(step.repeatCount)" : ""
        let attribute: String
        switch step.type {
        case .count:
            switch step.countMode {
            case .complete:
                attribute = " [计数=完成]"
            case .increment:
                attribute = ""
            case .none:
                throw SerializationException(code: .invalidStep, stepIndex: index)
            }
        case .timer:
            guard let category = step.category else {
                throw SerializationException(code: .invalidStep, stepIndex: index)
            }
            let inferred: TimerCategory = name.contains("休息") ? .rest : .normal
            if category == inferred {
                attribute = ""
            } else if category == .rest {
                attribute = " [分类=休息]"
            } else {
                attribute = " [分类=普通]"
            }
        }

        return base + repeatPart + attribute
    }

    private func formatDuration(index: Int, durationMs: Int64) throws -> String {
        guard durationMs >= 1_000, durationMs % 1_000 == 0 else {
            throw SerializationException(
                code: .unrepresentableDuration,
                stepIndex: index
            )
        }
        if durationMs % 3_600_000 == 0 {
            return "\(durationMs / 3_600_000)小时"
        }
        if durationMs % 60_000 == 0 {
            return "\(durationMs / 60_000)分钟"
        }
        return "\(durationMs / 1_000)秒"
    }
}
