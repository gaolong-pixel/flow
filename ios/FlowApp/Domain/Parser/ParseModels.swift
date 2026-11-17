import Foundation

enum ParseStatus: String, Equatable {
    case valid = "VALID"
    case empty = "EMPTY"
    case incomplete = "INCOMPLETE"
    case invalid = "INVALID"
}

/// Stable diagnostic codes; UI layers own user-facing text.
enum ParseErrorCode: String, Equatable {
    case incompleteMissingQuantity = "INCOMPLETE_MISSING_QUANTITY"
    case incompleteMissingUnit = "INCOMPLETE_MISSING_UNIT"
    case incompleteRepeatCount = "INCOMPLETE_REPEAT_COUNT"
    case incompleteAttribute = "INCOMPLETE_ATTRIBUTE"
    case invalidName = "INVALID_NAME"
    case invalidNumber = "INVALID_NUMBER"
    case invalidUnit = "INVALID_UNIT"
    case invalidDecimal = "INVALID_DECIMAL"
    case invalidCompound = "INVALID_COMPOUND"
    case invalidRepeatCount = "INVALID_REPEAT_COUNT"
    case invalidAttribute = "INVALID_ATTRIBUTE"
    case invalidAttributeType = "INVALID_ATTRIBUTE_TYPE"
    case invalidAttributeOrder = "INVALID_ATTRIBUTE_ORDER"
    case invalidTrailing = "INVALID_TRAILING"
    case invalidOverflow = "INVALID_OVERFLOW"
    case invalidLine = "INVALID_LINE"
}

struct ParseDiagnostic: Equatable {
    let lineNumber: Int
    let code: ParseErrorCode
}

/// A parsed step value without a persistent ID or order.
/// IDs/order are assigned by the draft/commit layer in phase 5.
struct StepCandidate: Equatable {
    let name: String
    let type: StepType
    let target: Int?
    let countMode: CountMode?
    let durationMs: Int64?
    let category: TimerCategory?
    let repeatCount: Int

    init(
        name: String,
        type: StepType,
        target: Int? = nil,
        countMode: CountMode? = nil,
        durationMs: Int64? = nil,
        category: TimerCategory? = nil,
        repeatCount: Int = 1
    ) {
        self.name = name
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
        if repeatCount < 1 || repeatCount > Int(Int32.max) {
            errors.append(.invalidStepRepeatCount)
        }

        switch type {
        case .count:
            if target == nil || (target ?? 0) <= 0 || (target ?? 0) > Int(Int32.max) {
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
}

struct ParsedLine: Equatable {
    let lineNumber: Int
    let raw: String
    let status: ParseStatus
    let step: StepCandidate?
    let diagnostic: ParseDiagnostic?

    init(
        lineNumber: Int,
        raw: String,
        status: ParseStatus,
        step: StepCandidate? = nil,
        diagnostic: ParseDiagnostic? = nil
    ) {
        self.lineNumber = lineNumber
        self.raw = raw
        self.status = status
        self.step = step
        self.diagnostic = diagnostic
    }
}

struct ParseStats: Equatable {
    let valid: Int
    let empty: Int
    let incomplete: Int
    let invalid: Int

    var total: Int {
        valid + empty + incomplete + invalid
    }
}

struct ParseResult: Equatable {
    let lines: [ParsedLine]
    let stats: ParseStats
    let steps: [StepCandidate]
    let diagnostics: [ParseDiagnostic]

    var hasBlockingErrors: Bool {
        stats.incomplete > 0 || stats.invalid > 0
    }

    var canCommit: Bool {
        !hasBlockingErrors
    }
}
