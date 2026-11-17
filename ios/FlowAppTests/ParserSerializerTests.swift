import XCTest
@testable import Flow

final class ParserSerializerTests: XCTestCase {

    private let parser = RoutineTextParser()
    private let serializer = RoutineTextSerializer()

    func testPassesAllSharedParserCases() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // FlowAppTests
            .deletingLastPathComponent() // ios
            .deletingLastPathComponent() // workspace root
            .appendingPathComponent("shared-spec/parser-cases.json")
        let data = try Data(contentsOf: url)
        guard let cases = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return XCTFail("parser-cases.json must be an array")
        }

        var valid = 0
        var empty = 0
        var incomplete = 0
        var invalid = 0
        var passed = 0
        var failures: [String] = []

        for (index, testCase) in cases.enumerated() {
            guard let input = testCase["input"] as? String,
                  let statusText = testCase["status"] as? String,
                  let expectedStatus = ParseStatus(rawValue: statusText) else {
                failures.append("case \(index): malformed case")
                continue
            }

            switch expectedStatus {
            case .valid: valid += 1
            case .empty: empty += 1
            case .incomplete: incomplete += 1
            case .invalid: invalid += 1
            }

            let result = parser.parse(input)
            guard result.lines.count == 1 else {
                failures.append("case \(index) '\(input)': expected one line")
                continue
            }

            let line = result.lines[0]
            guard line.status == expectedStatus else {
                failures.append(
                    "case \(index) '\(input)': expected \(expectedStatus.rawValue), " +
                        "got \(line.status.rawValue)"
                )
                continue
            }

            if expectedStatus == .valid {
                guard let expected = testCase["expected"] as? [String: Any],
                      let step = line.step else {
                    failures.append("case \(index) '\(input)': missing candidate")
                    continue
                }

                if step.name != (expected["name"] as? String) ||
                    step.type != StepType(rawValue: expected["type"] as? String ?? "") ||
                    step.target != intValue(expected["target"]) ||
                    step.countMode != CountMode(rawValue: expected["countMode"] as? String ?? "") ||
                    step.durationMs != int64Value(expected["durationMs"]) ||
                    step.category != TimerCategory(rawValue: expected["category"] as? String ?? "") ||
                    step.repeatCount != (intValue(expected["repeatCount"]) ?? -1) {
                    failures.append("case \(index) '\(input)': candidate mismatch \(step)")
                    continue
                }

                guard let canonical = try? serializer.serialize([step]),
                      let roundTripped = try? serializer.serialize(
                          parser.parse(canonical).steps
                      ) else {
                    failures.append("case \(index) '\(input)': round-trip serialization failed")
                    continue
                }
                let reparsed = parser.parse(canonical)
                guard reparsed.steps == [step] else {
                    failures.append("case \(index) '\(input)': round-trip mismatch '\(canonical)'")
                    continue
                }
                guard roundTripped == canonical else {
                    failures.append("case \(index) '\(input)': normalization did not converge")
                    continue
                }
            }

            passed += 1
        }

        print(
            "parser-cases: total=\(cases.count), valid=\(valid), empty=\(empty), " +
                "incomplete=\(incomplete), invalid=\(invalid), passed=\(passed), " +
                "failed=\(failures.count)"
        )

        XCTAssertEqual(cases.count, 88)
        XCTAssertEqual(valid, 40)
        XCTAssertEqual(empty, 3)
        XCTAssertEqual(incomplete, 9)
        XCTAssertEqual(invalid, 36)
        XCTAssertEqual(passed, 88)
        XCTAssertTrue(failures.isEmpty, failures.joined(separator: "\n"))
    }

    func testNormalizesLineEndingsAndPreservesEmptyLineNumbers() {
        let result = parser.parse("俯卧撑 15次\r\n\r\n休息 60秒\r俯卧撑 0次")

        XCTAssertEqual(result.lines.count, 4)
        XCTAssertEqual(
            result.lines.map(\.status),
            [.valid, .empty, .valid, .invalid]
        )
        XCTAssertEqual(result.lines.map(\.lineNumber), [1, 2, 3, 4])
        XCTAssertEqual(result.stats.valid, 2)
        XCTAssertEqual(result.stats.empty, 1)
        XCTAssertEqual(result.stats.invalid, 1)
        XCTAssertEqual(result.steps.count, 0)
        XCTAssertNotNil(result.lines[0].step)
        XCTAssertNotNil(result.lines[2].step)
        XCTAssertFalse(result.canCommit)
    }

    func testEmptyDocumentAndBlankLinesAreEmpty() {
        let emptyDocument = parser.parse("")
        XCTAssertEqual(emptyDocument.lines.count, 1)
        XCTAssertEqual(emptyDocument.lines[0].status, .empty)
        XCTAssertEqual(emptyDocument.stats.empty, 1)
        XCTAssertTrue(emptyDocument.canCommit)
        XCTAssertTrue(emptyDocument.steps.isEmpty)
        XCTAssertTrue(emptyDocument.diagnostics.isEmpty)

        let whitespaceOnly = parser.parse("   \n\t\n\u{3000}")
        XCTAssertEqual(whitespaceOnly.lines.count, 3)
        XCTAssertTrue(whitespaceOnly.lines.allSatisfy { $0.status == .empty })
        XCTAssertEqual(whitespaceOnly.stats.empty, 3)
        XCTAssertTrue(whitespaceOnly.canCommit)
    }

    func testExposesStableDiagnosticCodes() {
        assertCode("俯卧撑", .incomplete, .incompleteMissingQuantity)
        assertCode("俯卧撑 15", .incomplete, .incompleteMissingUnit)
        assertCode("俯卧撑 15次 x", .incomplete, .incompleteRepeatCount)
        assertCode("俯卧撑 15次 [", .incomplete, .incompleteAttribute)
        assertCode("俯卧撑 15个", .invalid, .invalidUnit)
        assertCode("阅读 1.5秒", .invalid, .invalidDecimal)
        assertCode("阅读 1小时30分钟", .invalid, .invalidCompound)
        assertCode("俯卧撑 15次 [分类=休息]", .invalid, .invalidAttributeType)
        assertCode("俯卧撑 15次 [计数=递增]", .invalid, .invalidAttribute)
        assertCode("阅读 9223372036854775807秒", .invalid, .invalidOverflow)
    }

    func testRejectsInvalidAttributesAndKeepsOtherLinesDiagnosable() {
        let result = parser.parse(
            "俯卧撑 15次\n" +
                "俯卧撑 15次 [计数=完成]\n" +
                "俯卧撑 15次 [计数=递增]\n" +
                "阅读 20分钟"
        )

        XCTAssertEqual(result.lines.count, 4)
        XCTAssertEqual(result.lines[0].status, .valid)
        XCTAssertEqual(result.lines[1].status, .valid)
        XCTAssertEqual(result.lines[2].status, .invalid)
        XCTAssertEqual(result.lines[3].status, .valid)
        XCTAssertEqual(result.steps.count, 0)
        XCTAssertNotNil(result.lines[0].step)
        XCTAssertNotNil(result.lines[1].step)
        XCTAssertNotNil(result.lines[3].step)
        XCTAssertEqual(result.diagnostics.count, 1)
        XCTAssertEqual(result.diagnostics[0].lineNumber, 3)
        XCTAssertFalse(result.canCommit)
    }

    func testBareNamesContainingUnitCharactersRemainIncomplete() {
        for input in ["时间", "得分", "阅读分钟", "第2组 时间", "名称[foo]", "名称]"] {
            let line = parser.parse(input).lines[0]
            XCTAssertEqual(line.status, .incomplete, "status for '\(input)'")
        }
    }

    func testInfersTimerCategoryAndHonorsExplicitOverride() {
        XCTAssertEqual(parser.parse("休息 60秒").lines[0].step?.category, .rest)
        XCTAssertEqual(parser.parse("休息 60秒 [分类=普通]").lines[0].step?.category, .normal)
        XCTAssertEqual(parser.parse("阅读 20分钟").lines[0].step?.category, .normal)
        XCTAssertEqual(parser.parse("阅读 20分钟 [分类=休息]").lines[0].step?.category, .rest)
    }

    func testTrimsNamesAndPreservesInternalWhitespaceAcrossRoundTrip() throws {
        let input = "  \u{00A0}第 2  组 深蹲\u{2007} 20次  "
        let parsed = parser.parse(input)
        XCTAssertTrue(parsed.canCommit)
        let step = parsed.lines[0].step
        XCTAssertEqual(step?.name, "第 2  组 深蹲")

        let canonical = try serializer.serialize(parsed.steps)
        let reparsed = parser.parse(canonical)
        XCTAssertTrue(reparsed.canCommit)
        XCTAssertEqual(reparsed.steps[0].name, step?.name)
    }

    func testTrimsCandidateNameButPreservesInternalWhitespace() throws {
        let step = count("  Push  ups  ", 15)
        let text = try serializer.serialize([step])
        XCTAssertEqual(text, "Push  ups 15次")
        XCTAssertEqual(parser.parse(text).lines[0].step?.name, "Push  ups")
    }

    func testRejectsCandidateNameThatIsBlankAfterTrim() {
        let invalid = count(" \u{00A0}\u{2007}\u{202F} ", 1)
        XCTAssertThrowsError(try serializer.serialize([invalid])) { error in
            XCTAssertEqual((error as? SerializationException)?.code, .invalidStep)
        }
    }

    func testSerializesCanonicalCountAndTimerForms() throws {
        let steps = [
            count("俯卧撑", 15),
            count("深蹲", 20, .complete, 3),
            timer("阅读", 1_200_000),
            timer("拉伸", 90_000, .normal, 2),
            timer("休息", 60_000, .rest),
            timer("拉伸", 45_000, .rest),
            timer("休息", 60_000, .normal),
            timer("阅读", 3_600_000),
            timer("阅读", 60_000),
            timer("阅读", 1_000),
            count("第2组 深蹲", 20)
        ]

        let expected = [
            "俯卧撑 15次",
            "深蹲 20次 x 3 [计数=完成]",
            "阅读 20分钟",
            "拉伸 90秒 x 2",
            "休息 1分钟",
            "拉伸 45秒 [分类=休息]",
            "休息 1分钟 [分类=普通]",
            "阅读 1小时",
            "阅读 1分钟",
            "阅读 1秒",
            "第2组 深蹲 20次"
        ].joined(separator: "\n")

        XCTAssertEqual(try serializer.serialize(steps), expected)
    }

    func testSerializesEmptyListAsEmptyString() throws {
        XCTAssertEqual(try serializer.serialize([]), "")
    }

    func testPreservesInputOrder() throws {
        let steps = [
            count("A", 1),
            timer("B", 1_000),
            count("C", 3)
        ]
        XCTAssertEqual(try serializer.serialize(steps), "A 1次\nB 1秒\nC 3次")
    }

    func testRejectsInvalidStepAtomically() {
        let invalid = StepCandidate(
            name: "阅读",
            type: .timer,
            durationMs: 1_500,
            category: .normal
        )
        XCTAssertThrowsError(try serializer.serialize([count("A", 1), invalid])) { error in
            guard let exception = error as? SerializationException else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertEqual(exception.code, .invalidStep)
            XCTAssertEqual(exception.stepIndex, 1)
        }
    }

    func testRejectsNamesWithNewlines() {
        let invalid = count("A\nB", 1)
        XCTAssertThrowsError(try serializer.serialize([invalid])) { error in
            XCTAssertEqual((error as? SerializationException)?.code, .invalidName)
        }
    }

    func testRoundTripPreservesAllSemanticFields() throws {
        let original = [
            count("俯卧撑", 15),
            count("深蹲", 20, .complete, 3),
            timer("阅读", 1_200_000, .normal, 2),
            timer("拉伸", 45_000, .rest),
            timer("休息", 60_000, .normal, 4)
        ]

        let text = try serializer.serialize(original)
        let parsed = parser.parse(text)
        XCTAssertTrue(parsed.canCommit)
        XCTAssertEqual(parsed.steps, original)
    }

    func testNormalizationConverges() throws {
        let inputs = [
            "俯卧撑15次",
            "俯卧撑 15 次 x 2 [计数=完成]",
            "阅读20分钟",
            "阅读 20分 [分类=休息]",
            "拉伸45秒x2[分类=休息]",
            "休息 60秒 [分类=普通]"
        ]

        for input in inputs {
            let first = parser.parse(input)
            XCTAssertTrue(first.canCommit, "input should be valid: \(input)")
            let firstText = try serializer.serialize(first.steps)
            let second = parser.parse(firstText)
            XCTAssertTrue(second.canCommit, "canonical text should be valid: \(firstText)")
            let secondText = try serializer.serialize(second.steps)
            XCTAssertEqual(firstText, secondText, "normalization must converge: \(input)")
        }
    }

    private func assertCode(
        _ input: String,
        _ status: ParseStatus,
        _ code: ParseErrorCode,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let parsed = parser.parse(input).lines[0]
        XCTAssertEqual(parsed.status, status, file: file, line: line)
        XCTAssertEqual(parsed.diagnostic?.code, code, file: file, line: line)
    }

    private func intValue(_ value: Any?) -> Int? {
        if value is NSNull { return nil }
        return (value as? NSNumber)?.intValue
    }

    private func int64Value(_ value: Any?) -> Int64? {
        if value is NSNull { return nil }
        return (value as? NSNumber)?.int64Value
    }

    private func count(
        _ name: String,
        _ target: Int,
        _ countMode: CountMode = .increment,
        _ repeatCount: Int = 1
    ) -> StepCandidate {
        StepCandidate(
            name: name,
            type: .count,
            target: target,
            countMode: countMode,
            repeatCount: repeatCount
        )
    }

    private func timer(
        _ name: String,
        _ durationMs: Int64,
        _ category: TimerCategory = .normal,
        _ repeatCount: Int = 1
    ) -> StepCandidate {
        StepCandidate(
            name: name,
            type: .timer,
            durationMs: durationMs,
            category: category,
            repeatCount: repeatCount
        )
    }
}
