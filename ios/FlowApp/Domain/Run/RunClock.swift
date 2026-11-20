import Darwin
import Foundation

/// Monotonic milliseconds used by a plan run. Never affected by wall-clock
/// changes.
protocol RunMonotonicClock {
    var nowMs: Int64 { get }
}

struct SystemRunMonotonicClock: RunMonotonicClock {
    private static let timebase: mach_timebase_info_data_t = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return info
    }()

    var nowMs: Int64 {
        let ticks = mach_continuous_time()
        let (scaled, overflowed) = ticks.multipliedReportingOverflow(
            by: UInt64(Self.timebase.numer)
        )
        if overflowed {
            return Int64.max
        }
        let nanos = scaled / UInt64(Self.timebase.denom)
        let millis = nanos / 1_000_000
        if millis > UInt64(Int64.max) {
            return Int64.max
        }
        return Int64(millis)
    }
}

/// Wall-clock milliseconds used only for display and elapsed summaries.
protocol RunWallClock {
    var nowEpochMs: Int64 { get }
}

struct SystemRunWallClock: RunWallClock {
    var nowEpochMs: Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }
}
