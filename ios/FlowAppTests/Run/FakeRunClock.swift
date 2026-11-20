import XCTest
@testable import Flow

import Foundation

final class FakeRunClock: RunMonotonicClock {
    var now: Int64

    init(_ now: Int64 = 1_000) {
        self.now = now
    }

    var nowMs: Int64 { now }

    func advance(_ ms: Int64) {
        now += ms
    }
}

final class FakeRunWallClock: RunWallClock {
    var now: Int64

    init(_ now: Int64 = 100_000) {
        self.now = now
    }

    var nowEpochMs: Int64 { now }
}
