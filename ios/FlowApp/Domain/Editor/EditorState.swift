import Foundation

enum EditorMode: String, Equatable, CaseIterable {
    case text = "TEXT"
    case cards = "CARDS"
}

enum SyncStatus: String, Equatable {
    case synced = "SYNCED"
    case dirty = "DIRTY"
    case invalid = "INVALID"
}

/// Immutable editing snapshot. RoutineDraft remains the only editable data
/// source; lastCommittedSteps is a read-only snapshot used for ID matching and
/// recovery only.
struct EditorState: Equatable {
    let mode: EditorMode
    let draft: RoutineDraft
    let textBuffer: String
    let parseResult: ParseResult
    let syncStatus: SyncStatus
    let lastCommittedSteps: [RoutineStep]
    let lastCommittedText: String

    var hasBlockingErrors: Bool {
        parseResult.hasBlockingErrors
    }

    var canEnterCards: Bool {
        syncStatus == .synced && !hasBlockingErrors
    }
}

enum EditorError: String, Error, Equatable {
    case wrongMode = "WRONG_MODE"
    case textBlocked = "TEXT_BLOCKED"
    case draftInvalid = "DRAFT_INVALID"
    case invalidStep = "INVALID_STEP"
    case duplicateStepID = "DUPLICATE_STEP_ID"
    case stepNotFound = "STEP_NOT_FOUND"
    case invalidMove = "INVALID_MOVE"
    case invalidRepeatCount = "INVALID_REPEAT_COUNT"
    case invalidName = "INVALID_NAME"
    case nameTooLong = "NAME_TOO_LONG"
    case serializationFailed = "SERIALIZATION_FAILED"
}

enum EditorResult {
    case success(EditorState)
    case failure(EditorError, EditorState)

    var state: EditorState {
        switch self {
        case .success(let state), .failure(_, let state):
            return state
        }
    }
}
