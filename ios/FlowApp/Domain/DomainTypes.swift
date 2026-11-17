import Foundation

enum StepType: String, Codable, CaseIterable {
    case count = "COUNT"
    case timer = "TIMER"
}

enum CountMode: String, Codable, CaseIterable {
    case increment = "INCREMENT"
    case complete = "COMPLETE"
}

enum TimerCategory: String, Codable, CaseIterable {
    case normal = "NORMAL"
    case rest = "REST"
}

enum ValidationError: Error, Equatable {
    case emptyStepName
    case invalidStepRepeatCount
    case invalidCountTarget
    case missingCountMode
    case countHasTimerFields
    case invalidTimerDuration
    case missingTimerCategory
    case timerHasCountFields
    case duplicateStepID
    case invalidRoutineRepeatCount
}
