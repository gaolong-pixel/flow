import Foundation

struct RoutineDraft {
    var name: String = ""
    var repeatCount: Int = 1
    var steps: [RoutineStep] = []

    mutating func add(_ step: RoutineStep) { steps.append(step); normalizeOrder() }
    mutating func update(_ step: RoutineStep) {
        guard let index = steps.firstIndex(where: { $0.id == step.id }) else { return }
        steps[index] = step
        normalizeOrder()
    }
    mutating func delete(id: String) { steps.removeAll { $0.id == id }; normalizeOrder() }
    mutating func move(from: Int, to: Int) {
        guard steps.indices.contains(from), steps.indices.contains(to), from != to else { return }
        let item = steps.remove(at: from)
        steps.insert(item, at: to)
        normalizeOrder()
    }
    private mutating func normalizeOrder() {
        for index in steps.indices { steps[index].order = index }
    }
}
