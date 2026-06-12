import Charts
import SwiftUI

struct TelemetryChartsView: View {
    let telemetry: DailyTelemetry
    let onPreviousDay: () -> Void
    let onNextDay: () -> Void
    let onToday: () -> Void

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

            if telemetry.hasMessageData {
                Chart(telemetry.messagePoints) { point in
                    BarMark(
                        x: .value("Hour", point.hour.startDate),
                        y: .value("Messages", point.count)
                    )
                    .position(by: .value("Direction", point.direction))
                    .foregroundStyle(by: .value("Direction", point.direction))
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
}
