import Foundation

extension String {
    var trimmingFlowWhitespace: String {
        trimmingCharacters(in: String.flowWhitespaceCharacterSet)
    }

    var trimmingTrailingFlowWhitespace: String {
        var result = self
        while let last = result.unicodeScalars.last,
              String.flowWhitespaceCharacterSet.contains(last) {
            result.removeLast()
        }
        return result
    }
}
