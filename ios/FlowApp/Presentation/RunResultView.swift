import SwiftUI

struct RunResultView: View {
    let summary: RunSummary
    let onBackToPlans: () -> Void

    var body: some View {
        ResultScaffold(
            accent: FlowPalette.synced,
            soft: FlowPalette.successSoft,
            symbol: "checkmark",
            title: "流程已完成",
            titleIdentifier: "result-title",
            planName: summary.planName,
            progress: "\(summary.completedNodes) / \(summary.totalNodes) 个节点",
            note: nil,
            buttonTitle: "返回首页",
            buttonIdentifier: "result-back",
            onBack: onBackToPlans
        )
    }
}

struct RunExitResultView: View {
    let summary: RunSummary
    let onBackToPlans: () -> Void

    var body: some View {
        ResultScaffold(
            accent: FlowPalette.secondaryText,
            soft: FlowPalette.segmentBackground,
            symbol: "minus",
            title: "已退出流程",
            titleIdentifier: "exit-result-title",
            planName: summary.planName,
            progress: "已完成 \(summary.completedNodes) / \(summary.totalNodes) 个节点",
            note: "当前节点未计入已完成数量。",
            buttonTitle: "返回首页",
            buttonIdentifier: "exit-result-home",
            onBack: onBackToPlans
        )
    }
}

private struct ResultScaffold: View {
    let accent: Color
    let soft: Color
    let symbol: String
    let title: String
    let titleIdentifier: String
    let planName: String
    let progress: String
    let note: String?
    let buttonTitle: String
    let buttonIdentifier: String
    let onBack: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            Image(systemName: symbol)
                .flowFont(34, weight: .semibold)
                .foregroundColor(accent)
                .frame(width: 76, height: 76)
                .background(soft)
                .clipShape(Circle())
                .padding(.bottom, 24)

            Text(title)
                .flowFont(24, weight: .semibold)
                .foregroundColor(FlowPalette.text)
                .accessibilityIdentifier(titleIdentifier)

            Text(planName)
                .flowFont(15)
                .foregroundColor(FlowPalette.secondaryText)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .padding(.top, 8)

            Text(progress)
                .flowFont(24, weight: .semibold)
                .foregroundColor(FlowPalette.text)
                .multilineTextAlignment(.center)
                .padding(.top, 24)

            if let note {
                Text(note)
                    .flowFont(13)
                    .foregroundColor(FlowPalette.secondaryText)
                    .multilineTextAlignment(.center)
                    .padding(.top, 6)
            }

            Spacer()

            Button(buttonTitle, action: onBack)
                .buttonStyle(FlowPrimaryButtonStyle())
                .accessibilityIdentifier(buttonIdentifier)
        }
        .padding(.horizontal, FlowMetrics.pagePadding)
        .padding(.vertical, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(FlowPalette.background.ignoresSafeArea())
    }
}
