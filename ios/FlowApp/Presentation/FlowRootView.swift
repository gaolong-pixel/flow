import SwiftUI

struct FlowRootView: View {
    var body: some View {
        ZStack {
            Color(.systemBackground)
                .ignoresSafeArea()

            Text("Flow")
                .font(.largeTitle.weight(.semibold))
        }
    }
}
