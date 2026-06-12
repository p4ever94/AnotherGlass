import Charts
import SwiftUI

struct TelemetryChartsView: View {
    let telemetry: DailyTelemetry
    let onPreviousDay: () -> Void
    let onNextDay: () -> Void
    let onToday: () -> Void

    @State private var selectedBatteryHour: Int?
    @State private var selectedMessageHour: Int?

    private var canMoveToNextDay: Bool {
        telemetry.date < Calendar.current.startOfDay(for: Date())
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            dayControls
            batteryChart
            messageChart
        }
        .padding(.vertical, 4)
        .onChange(of: telemetry.date) { _, _ in
            selectedBatteryHour = nil
            selectedMessageHour = nil
        }
    }

    private var dayControls: some View {
        HStack(spacing: 12) {
            Button(action: onPreviousDay) {
                Image(systemName: "chevron.left")
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Previous day")

            VStack(alignment: .leading, spacing: 2) {
                Text(telemetry.date.formatted(date: .abbreviated, time: .omitted))
                    .font(.headline)
                Text("\(telemetry.totalMessagesToGlasses) to Glass, \(telemetry.totalMessagesFromGlasses) from Glass")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button(action: onToday) {
                Image(systemName: "calendar")
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Today")

            Button(action: onNextDay) {
                Image(systemName: "chevron.right")
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.borderless)
            .disabled(!canMoveToNextDay)
            .accessibilityLabel("Next day")
        }
    }

    private var batteryChart: some View {
        VStack(alignment: .leading, spacing: 6) {
            chartHeader("Battery", symbolName: "battery.100")
            if let hour = selectedBatteryTelemetryHour,
               let level = hour.batteryLevel {
                chartValueSummary(
                    "\(formattedHour(hour)): \(formattedBatteryLevel(level))",
                    systemName: hour.chargingSampleCount > 0 ? "bolt.fill" : "circle.fill",
                    color: hour.chargingSampleCount > 0 ? .orange : .green
                )
            }

            if telemetry.hasBatteryData {
                Chart(telemetry.batteryHours) { hour in
                    if let level = hour.batteryLevel {
                        LineMark(
                            x: .value("Hour", hour.startDate),
                            y: .value("Battery", level)
                        )
                        .interpolationMethod(.catmullRom)
                        .foregroundStyle(.green)

                        PointMark(
                            x: .value("Hour", hour.startDate),
                            y: .value("Battery", level)
                        )
                        .foregroundStyle(hour.chargingSampleCount > 0 ? .orange : .green)
                    }
                }
                .chartOverlay { proxy in
                    selectionOverlay(proxy: proxy) { date in
                        selectedBatteryHour = nearestBatteryHour(to: date)?.hour
                    }
                }
                .chartBackground { proxy in
                    selectedHourRule(proxy: proxy, hour: selectedBatteryTelemetryHour)
                }
                .chartYScale(domain: 0...100)
                .chartXAxis {
                    AxisMarks(values: .stride(by: .hour, count: 6)) {
                        AxisGridLine()
                        AxisTick()
                        AxisValueLabel(format: .dateTime.hour())
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .leading, values: [0, 25, 50, 75, 100]) {
                        AxisGridLine()
                        AxisTick()
                        AxisValueLabel()
                    }
                }
                .frame(height: 170)
            } else {
                emptyChartState("No battery samples")
            }
        }
    }

    private var messageChart: some View {
        VStack(alignment: .leading, spacing: 6) {
            chartHeader("Messages", symbolName: "arrow.left.arrow.right")
            if let hour = selectedMessageTelemetryHour {
                chartValueSummary(
                    "\(formattedHour(hour)): \(hour.messagesToGlasses) to Glass, \(hour.messagesFromGlasses) from Glass",
                    systemName: "number",
                    color: .blue
                )
            }

            if telemetry.hasMessageData {
                Chart(telemetry.messagePoints) { point in
                    BarMark(
                        x: .value("Hour", point.hour.startDate),
                        y: .value("Messages", point.count)
                    )
                    .position(by: .value("Direction", point.direction))
                    .foregroundStyle(by: .value("Direction", point.direction))
                }
                .chartOverlay { proxy in
                    selectionOverlay(proxy: proxy) { date in
                        selectedMessageHour = nearestHour(to: date)?.hour
                    }
                }
                .chartBackground { proxy in
                    selectedHourRule(proxy: proxy, hour: selectedMessageTelemetryHour)
                }
                .chartForegroundStyleScale([
                    "To Glass": .blue,
                    "From Glass": .purple
                ])
                .chartXAxis {
                    AxisMarks(values: .stride(by: .hour, count: 6)) {
                        AxisGridLine()
                        AxisTick()
                        AxisValueLabel(format: .dateTime.hour())
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .leading) {
                        AxisGridLine()
                        AxisTick()
                        AxisValueLabel()
                    }
                }
                .frame(height: 170)
            } else {
                emptyChartState("No messages")
            }
        }
    }

    private func chartHeader(_ title: String, symbolName: String) -> some View {
        Label(title, systemImage: symbolName)
            .font(.subheadline.weight(.semibold))
    }

    private func chartValueSummary(_ title: String, systemName: String, color: Color) -> some View {
        Label(title, systemImage: systemName)
            .font(.caption.weight(.semibold))
            .foregroundStyle(color)
            .monospacedDigit()
    }

    private func emptyChartState(_ title: String) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color.secondary.opacity(0.08))
            Text(title)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(height: 110)
    }

    private var selectedBatteryTelemetryHour: TelemetryHour? {
        guard let selectedBatteryHour else { return nil }
        return telemetry.hours.first { $0.hour == selectedBatteryHour && $0.batteryLevel != nil }
    }

    private var selectedMessageTelemetryHour: TelemetryHour? {
        guard let selectedMessageHour else { return nil }
        return telemetry.hours.first { $0.hour == selectedMessageHour }
    }

    private func selectionOverlay(proxy: ChartProxy, onSelect: @escaping (Date) -> Void) -> some View {
        GeometryReader { geometry in
            Rectangle()
                .fill(.clear)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let plotArea = geometry[proxy.plotAreaFrame]
                            let xPosition = value.location.x - plotArea.origin.x
                            guard xPosition >= 0, xPosition <= plotArea.width,
                                  let date: Date = proxy.value(atX: xPosition) else { return }
                            onSelect(date)
                        }
                )
        }
    }

    private func selectedHourRule(proxy: ChartProxy, hour: TelemetryHour?) -> some View {
        GeometryReader { geometry in
            if let hour,
               let xPosition = proxy.position(forX: hour.startDate) {
                let plotArea = geometry[proxy.plotAreaFrame]
                Rectangle()
                    .fill(Color.secondary.opacity(0.35))
                    .frame(width: 1, height: plotArea.height)
                    .position(x: plotArea.origin.x + xPosition, y: plotArea.midY)
            }
        }
    }

    private func nearestBatteryHour(to date: Date) -> TelemetryHour? {
        telemetry.batteryHours.min { lhs, rhs in
            abs(lhs.startDate.timeIntervalSince(date)) < abs(rhs.startDate.timeIntervalSince(date))
        }
    }

    private func nearestHour(to date: Date) -> TelemetryHour? {
        telemetry.hours.min { lhs, rhs in
            abs(lhs.startDate.timeIntervalSince(date)) < abs(rhs.startDate.timeIntervalSince(date))
        }
    }

    private func formattedHour(_ hour: TelemetryHour) -> String {
        hour.startDate.formatted(.dateTime.hour())
    }

    private func formattedBatteryLevel(_ level: Double) -> String {
        "\(level.formatted(.number.precision(.fractionLength(0...1))))%"
    }
}
