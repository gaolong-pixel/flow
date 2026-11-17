import Foundation

enum ParseStatus { case valid, incomplete, invalid, empty }

struct LineParseResult: Identifiable {
    var id: Int { lineNumber }
    let lineNumber: Int
    let raw: String
    let status: ParseStatus
    let step: RoutineStep?
    let message: String?
}

struct RoutineParseResult {
    let lines: [LineParseResult]
    var steps: [RoutineStep] { lines.compactMap(\.step) }
    var hasBlockingErrors: Bool {
        lines.contains { $0.status == .invalid || $0.status == .incomplete }
    }
}

struct RoutineTextParser {
    func parse(_ text: String) -> RoutineParseResult {
        let results = text.components(separatedBy: .newlines).enumerated().compactMap { index, raw -> LineParseResult? in
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { return nil }
            return parseLine(index + 1, line)
        }
        return RoutineParseResult(lines: results)
    }

    private func parseLine(_ number: Int, _ line: String) -> LineParseResult {
        let countPattern = #"^(.+?)\s+(\d+)\s*次(?:\s*[x×]\s*(\d+))?$"#
        if let m = line.firstMatch(pattern: countPattern) {
            let name = m[1].trimmingCharacters(in: .whitespaces)
            let target = Int(m[2]) ?? 0
            let repeatCount = m[3].isEmpty ? 1 : (Int(m[3]) ?? 0)
            guard !name.isEmpty, target > 0, repeatCount > 0 else {
                return invalid(number, line, "次数或重复次数必须大于 0")
            }
            let step = RoutineStep(id: UUID().uuidString, name: name, type: .count,
                                   target: target, countMode: .increment, durationMs: nil,
                                   category: nil, repeatCount: repeatCount, order: number - 1)
            return valid(number, line, step)
        }

        let timerPattern = #"^(.+?)\s+(\d+)\s*(秒|分钟|分|小时|时)(?:\s*[x×]\s*(\d+))?$"#
        if let m = line.firstMatch(pattern: timerPattern) {
            let name = m[1].trimmingCharacters(in: .whitespaces)
            let amount = Int64(m[2]) ?? 0
            let repeatCount = m[4].isEmpty ? 1 : (Int(m[4]) ?? 0)
            let multiplier: Int64
            switch m[3] {
            case "秒": multiplier = 1_000
            case "分钟", "分": multiplier = 60_000
            case "小时", "时": multiplier = 3_600_000
            default: multiplier = 1_000
            }
            let duration = amount * multiplier
            guard !name.isEmpty, duration > 0, repeatCount > 0 else {
                return invalid(number, line, "时长或重复次数必须大于 0")
            }
            let category: TimerCategory = name.contains("休息") ? .rest : .normal
            let step = RoutineStep(id: UUID().uuidString, name: name, type: .timer,
                                   target: nil, countMode: nil, durationMs: duration,
                                   category: category, repeatCount: repeatCount, order: number - 1)
            return valid(number, line, step)
        }

        if line.range(of: #"^(.+?)\s+\d+$"#, options: .regularExpression) != nil {
            return LineParseResult(lineNumber: number, raw: line, status: .incomplete,
                                   step: nil, message: "请补充单位，例如：次、秒或分钟")
        }
        return invalid(number, line, "无法识别该步骤，请使用“名称 数字次/秒/分钟”格式")
    }

    private func valid(_ n: Int, _ raw: String, _ step: RoutineStep) -> LineParseResult {
        LineParseResult(lineNumber: n, raw: raw, status: .valid, step: step, message: nil)
    }
    private func invalid(_ n: Int, _ raw: String, _ message: String) -> LineParseResult {
        LineParseResult(lineNumber: n, raw: raw, status: .invalid, step: nil, message: message)
    }
}

private extension String {
    struct RegexMatch {
        let captures: [String]
        subscript(_ index: Int) -> String { index < captures.count ? captures[index] : "" }
    }

    func firstMatch(pattern: String) -> RegexMatch? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let result = regex.firstMatch(in: self, range: NSRange(startIndex..., in: self)) else { return nil }
        let captures = (0..<result.numberOfRanges).map { index -> String in
            guard let range = Range(result.range(at: index), in: self) else { return "" }
            return String(self[range])
        }
        return RegexMatch(captures: captures)
    }
}
