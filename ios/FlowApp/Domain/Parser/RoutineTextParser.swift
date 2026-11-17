import Foundation

/// Parser for the V1.1 line grammar.
///
/// The parser is stateless and line-oriented. It returns candidates without
/// IDs or order; committing to a RoutineDraft belongs to a later layer.
struct RoutineTextParser {

    private enum Attribute {
        case countComplete
        case timerRest
        case timerNormal
    }

    private enum AttributeResult {
        case none(String)
        case found(String, Attribute)
        case incomplete(ParseErrorCode)
        case invalid(ParseErrorCode)
    }

    private enum RepeatResult {
        case none(String)
        case found(String, Int)
        case incomplete(String, ParseErrorCode)
        case invalid(String, ParseErrorCode)
    }

    private enum QuantityResult {
        case found(name: String, type: StepType, target: Int?, durationMs: Int64?)
        case incomplete(ParseErrorCode)
        case invalid(ParseErrorCode)
    }

    func parse(_ text: String) -> ParseResult {
        let normalized = text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        let rawLines = splitLogicalLines(normalized)

        let lines = rawLines.enumerated().map { index, raw in
            parseLine(lineNumber: index + 1, raw: raw)
        }

        let valid = lines.filter { $0.status == .valid }.count
        let empty = lines.filter { $0.status == .empty }.count
        let incomplete = lines.filter { $0.status == .incomplete }.count
        let invalid = lines.filter { $0.status == .invalid }.count

        let stats = ParseStats(
            valid: valid,
            empty: empty,
            incomplete: incomplete,
            invalid: invalid
        )
        return ParseResult(
            lines: lines,
            stats: stats,
            // Candidates are exposed for commit only when every non-empty line
            // is valid; per-line candidates remain available on ParsedLine.
            steps: (incomplete == 0 && invalid == 0) ? lines.compactMap(\.step) : [],
            diagnostics: lines.compactMap(\.diagnostic)
        )
    }

    private func parseLine(lineNumber: Int, raw: String) -> ParsedLine {
        let trimmed = raw.trimmingFlowWhitespace
        if trimmed.isEmpty {
            return ParsedLine(
                lineNumber: lineNumber,
                raw: raw,
                status: .empty
            )
        }

        let attributeResult = extractAttribute(trimmed)
        let attributeValue: Attribute?
        var working: String

        switch attributeResult {
        case .none(let text):
            attributeValue = nil
            working = text
        case .found(let text, let attribute):
            attributeValue = attribute
            working = text.trimmingTrailingFlowWhitespace
            if hasTrailingAttribute(working) {
                return invalid(lineNumber: lineNumber, raw: raw, code: .invalidAttribute)
            }
        case .incomplete(let code):
            return incomplete(lineNumber: lineNumber, raw: raw, code: code)
        case .invalid(let code):
            return invalid(lineNumber: lineNumber, raw: raw, code: code)
        }

        let repeatResult = extractRepeat(working)
        let repeatCount: Int
        switch repeatResult {
        case .none(let text):
            repeatCount = 1
            working = text
        case .found(let text, let count):
            if hasTrailingAttribute(text) {
                return invalid(lineNumber: lineNumber, raw: raw, code: .invalidAttributeOrder)
            }
            repeatCount = count
            working = text
        case .incomplete(let text, let code):
            if hasTrailingAttribute(text) {
                return invalid(lineNumber: lineNumber, raw: raw, code: .invalidAttributeOrder)
            }
            return incomplete(lineNumber: lineNumber, raw: raw, code: code)
        case .invalid(_, let code):
            return invalid(lineNumber: lineNumber, raw: raw, code: code)
        }

        if hasTrailingAttribute(working) {
            return invalid(lineNumber: lineNumber, raw: raw, code: .invalidAttributeOrder)
        }

        let quantityResult = parseQuantity(working)
        let name: String
        let type: StepType
        let target: Int?
        let durationMs: Int64?

        switch quantityResult {
        case .found(let parsedName, let parsedType, let parsedTarget, let parsedDuration):
            name = parsedName
            type = parsedType
            target = parsedTarget
            durationMs = parsedDuration
        case .incomplete(let code):
            return incomplete(lineNumber: lineNumber, raw: raw, code: code)
        case .invalid(let code):
            return invalid(lineNumber: lineNumber, raw: raw, code: code)
        }

        let countMode: CountMode?
        let category: TimerCategory?
        switch type {
        case .count:
            switch attributeValue {
            case .countComplete:
                countMode = .complete
            case .none:
                countMode = .increment
            default:
                return invalid(lineNumber: lineNumber, raw: raw, code: .invalidAttributeType)
            }
            category = nil
        case .timer:
            countMode = nil
            let inferred: TimerCategory = name.contains("休息") ? .rest : .normal
            switch attributeValue {
            case .timerRest:
                category = .rest
            case .timerNormal:
                category = .normal
            case .none:
                category = inferred
            default:
                return invalid(lineNumber: lineNumber, raw: raw, code: .invalidAttributeType)
            }
        }

        let candidate = StepCandidate(
            name: name,
            type: type,
            target: target,
            countMode: countMode,
            durationMs: durationMs,
            category: category,
            repeatCount: repeatCount
        )
        if !candidate.isValid {
            return invalid(lineNumber: lineNumber, raw: raw, code: .invalidLine)
        }

        return ParsedLine(
            lineNumber: lineNumber,
            raw: raw,
            status: .valid,
            step: candidate
        )
    }

    private func extractAttribute(_ line: String) -> AttributeResult {
        if line.hasSuffix("]") {
            if let lastOpen = line.lastIndex(of: "[") {
                let token = String(line[lastOpen...])
                if let attribute = Self.attributeTokens[token] {
                    let text = String(line[..<lastOpen]).trimmingTrailingFlowWhitespace
                    return .found(text, attribute)
                }
                let prefix = String(line[..<lastOpen])
                if hasQuantityLike(prefix) {
                    return .invalid(.invalidAttribute)
                }
            }
            return .none(line)
        }

        if let lastOpen = line.lastIndex(of: "[") {
            let suffix = String(line[lastOpen...])
            let prefix = String(line[..<lastOpen])
            let isPartialAttribute = Self.attributeTokens.keys.contains { token in
                token.hasPrefix(suffix) && token != suffix
            }
            if isPartialAttribute {
                return .incomplete(.incompleteAttribute)
            }
            if hasQuantityLike(prefix) {
                return .invalid(.invalidAttribute)
            }
        }

        return .none(line)
    }

    private func extractRepeat(_ line: String) -> RepeatResult {
        let trimmed = line.trimmingTrailingFlowWhitespace
        if trimmed.isEmpty {
            return .none(trimmed)
        }

        var digitStart = trimmed.unicodeScalars.count
        let scalars = Array(trimmed.unicodeScalars)
        while digitStart > 0 && isAsciiDigit(scalars[digitStart - 1]) {
            digitStart -= 1
        }

        if digitStart == scalars.count {
            if let last = scalars.last, isRepeatMarker(last) {
                let text = String(String.UnicodeScalarView(scalars[0..<(scalars.count - 1)]))
                    .trimmingTrailingFlowWhitespace
                return .incomplete(text, .incompleteRepeatCount)
            }
            return .none(trimmed)
        }

        let digits = String(String.UnicodeScalarView(scalars[digitStart..<scalars.count]))
        var markerCheck = digitStart
        while markerCheck > 0 && isFlowWhitespace(scalars[markerCheck - 1]) {
            markerCheck -= 1
        }
        if markerCheck == 0 || !isRepeatMarker(scalars[markerCheck - 1]) {
            return .none(trimmed)
        }

        let markerIndex = markerCheck - 1
        let core = String(String.UnicodeScalarView(scalars[0..<markerIndex]))
            .trimmingTrailingFlowWhitespace
        if hasTrailingAttribute(core) {
            return .invalid(core, .invalidAttributeOrder)
        }

        guard let count = Int(digits) else {
            return .invalid(core, .invalidOverflow)
        }
        if count < 1 {
            return .invalid(core, .invalidRepeatCount)
        }
        if count > Int(Int32.max) {
            return .invalid(core, .invalidOverflow)
        }

        return .found(core, count)
    }

    private func parseQuantity(_ core: String) -> QuantityResult {
        let ns = core as NSString
        let fullRange = NSRange(location: 0, length: ns.length)
        guard let match = Self.quantityRegex.firstMatch(in: core, range: fullRange),
              match.range.location == 0,
              match.range.length == ns.length else {
            return classifyNoQuantity(core)
        }

        let namePart = ns.substring(with: match.range(at: 1))
        let digits = ns.substring(with: match.range(at: 2))
        let unit = ns.substring(with: match.range(at: 3))
        let digitStart = match.range(at: 2).location

        if digitStart > 0 {
            let before = ns.substring(with: NSRange(location: digitStart - 1, length: 1))
            if Self.decimalSeparators.contains(before) {
                return .invalid(.invalidDecimal)
            }
            if Self.numberSigns.contains(before) {
                return .invalid(.invalidNumber)
            }
        }
        if isCompoundQuantity(namePart) {
            return .invalid(.invalidCompound)
        }

        let name = namePart.trimmingFlowWhitespace
        if name.isEmpty {
            return .invalid(.invalidName)
        }

        switch unit {
        case "次":
            guard let target = Int(digits) else {
                return .invalid(.invalidOverflow)
            }
            guard target > 0 else {
                return .invalid(.invalidNumber)
            }
            guard target <= Int(Int32.max) else {
                return .invalid(.invalidOverflow)
            }
            return .found(name: name, type: .count, target: target, durationMs: nil)

        case "秒", "分钟", "分", "小时", "时":
            guard let amount = Int64(digits) else {
                return .invalid(.invalidOverflow)
            }
            guard amount > 0 else {
                return .invalid(.invalidNumber)
            }

            let multiplier: Int64
            switch unit {
            case "秒":
                multiplier = 1_000
            case "分钟", "分":
                multiplier = 60_000
            case "小时", "时":
                multiplier = 3_600_000
            default:
                return .invalid(.invalidUnit)
            }

            guard amount <= Int64.max / multiplier else {
                return .invalid(.invalidOverflow)
            }
            let durationMs = amount * multiplier
            guard durationMs >= 1_000, durationMs % 1_000 == 0 else {
                return .invalid(.invalidUnit)
            }
            return .found(name: name, type: .timer, target: nil, durationMs: durationMs)

        default:
            return .invalid(.invalidUnit)
        }
    }

    private func classifyNoQuantity(_ core: String) -> QuantityResult {
        let trimmed = core.trimmingFlowWhitespace
        if trimmed.isEmpty {
            return .invalid(.invalidName)
        }
        if trimmed.hasSuffix("个") || trimmed.hasSuffix("毫秒") || trimmed.hasSuffix("ms") {
            return .invalid(.invalidUnit)
        }

        if let unitAtEnd = Self.recognizedUnitTokens.first(where: { trimmed.hasSuffix($0) }) {
            let prefix = String(trimmed.dropLast(unitAtEnd.count))
            if prefix.unicodeScalars.contains(where: { isQuantitySignal($0) }) {
                return .invalid(.invalidUnit)
            }
            return .incomplete(.incompleteMissingQuantity)
        }

        if hasRecognizedUnitPrecededByQuantitySignal(trimmed) {
            return .invalid(.invalidUnit)
        }
        if let last = trimmed.unicodeScalars.last, isAsciiDigit(last) {
            return .incomplete(.incompleteMissingUnit)
        }
        return .incomplete(.incompleteMissingQuantity)
    }

    private func hasRecognizedUnitPrecededByQuantitySignal(_ text: String) -> Bool {
        for unit in Self.recognizedUnitTokens {
            var searchStart = text.startIndex
            while let range = text.range(of: unit, range: searchStart..<text.endIndex) {
                if range.lowerBound > text.startIndex {
                    let before = text.index(before: range.lowerBound)
                    if let scalar = text[before].unicodeScalars.first,
                       isQuantitySignal(scalar) {
                        return true
                    }
                }
                searchStart = range.upperBound
            }
        }
        return false
    }

    private func isQuantitySignal(_ scalar: Unicode.Scalar) -> Bool {
        if scalar.value >= 0x30 && scalar.value <= 0x39 {
            return true
        }
        if scalar.value >= 0x41 && scalar.value <= 0x5A {
            return true
        }
        if scalar.value >= 0x61 && scalar.value <= 0x7A {
            return true
        }
        return Self.chineseNumeralScalars.contains(scalar)
    }

    private func hasQuantityLike(_ text: String) -> Bool {
        let value = text.trimmingFlowWhitespace
        let ns = value as NSString
        return Self.quantityLikeRegex.firstMatch(
            in: value,
            range: NSRange(location: 0, length: ns.length)
        ) != nil
    }

    private func hasTrailingAttribute(_ text: String) -> Bool {
        let trimmed = text.trimmingTrailingFlowWhitespace
        return Self.attributeTokens.keys.contains { trimmed.hasSuffix($0) }
    }

    private func isCompoundQuantity(_ prefix: String) -> Bool {
        let prefixScalars = Array(prefix.unicodeScalars)
        for unit in Self.recognizedUnitTokens {
            let unitScalars = Array(unit.unicodeScalars)
            guard prefixScalars.count >= unitScalars.count else { continue }
            let suffixStart = prefixScalars.count - unitScalars.count
            if prefixScalars[suffixStart...].elementsEqual(unitScalars),
               suffixStart > 0 {
                return isAsciiDigit(prefixScalars[suffixStart - 1])
            }
        }
        return false
    }

    private func splitLogicalLines(_ text: String) -> [String] {
        text.components(separatedBy: "\n")
    }

    private func isAsciiDigit(_ scalar: Unicode.Scalar) -> Bool {
        scalar.value >= 0x30 && scalar.value <= 0x39
    }

    private func isRepeatMarker(_ scalar: Unicode.Scalar) -> Bool {
        scalar == "x" || scalar == "X" || scalar == "×"
    }

    private func isFlowWhitespace(_ scalar: Unicode.Scalar) -> Bool {
        String.flowWhitespaceCharacterSet.contains(scalar)
    }

    private func incomplete(
        lineNumber: Int,
        raw: String,
        code: ParseErrorCode
    ) -> ParsedLine {
        ParsedLine(
            lineNumber: lineNumber,
            raw: raw,
            status: .incomplete,
            diagnostic: ParseDiagnostic(lineNumber: lineNumber, code: code)
        )
    }

    private func invalid(
        lineNumber: Int,
        raw: String,
        code: ParseErrorCode
    ) -> ParsedLine {
        ParsedLine(
            lineNumber: lineNumber,
            raw: raw,
            status: .invalid,
            diagnostic: ParseDiagnostic(lineNumber: lineNumber, code: code)
        )
    }

    private static let ws =
        "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"

    private static let digits = "[0-9]+"

    private static let quantityRegex: NSRegularExpression = {
        try! NSRegularExpression(
            pattern: "^(.*?)(\(digits))\(ws)*(次|秒|分钟|分|小时|时)$"
        )
    }()

    private static let quantityLikeRegex: NSRegularExpression = {
        try! NSRegularExpression(
            pattern: ".*\(digits)\(ws)*(次|秒|分钟|分|小时|时)" +
                "(?:\(ws)*[xX×]\(ws)*\(digits))?$"
        )
    }()

    private static let attributeTokens: [String: Attribute] = [
        "[计数=完成]": .countComplete,
        "[分类=休息]": .timerRest,
        "[分类=普通]": .timerNormal
    ]

    private static let recognizedUnitTokens = ["分钟", "小时", "秒", "分", "时", "次"]
    private static let decimalSeparators: Set<String> = [".", "．", ",", "，"]
    private static let numberSigns: Set<String> = ["-", "+"]
    private static let chineseNumeralScalars: Set<Unicode.Scalar> =
        Set("零〇一二两三四五六七八九十百千万亿".unicodeScalars)
}
