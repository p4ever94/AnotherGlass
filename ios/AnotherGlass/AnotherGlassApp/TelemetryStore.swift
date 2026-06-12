import Foundation
import SQLite3

enum GlassMessageDirection: String {
    case toGlasses = "to_glasses"
    case fromGlasses = "from_glasses"
}

struct TelemetryHour: Identifiable {
    let hour: Int
    let startDate: Date
    var batteryLevel: Double?
    var batterySampleCount: Int
    var chargingSampleCount: Int
    var messagesToGlasses: Int
    var messagesFromGlasses: Int

    var id: Int { hour }
}

struct TelemetryMessagePoint: Identifiable {
    let hour: TelemetryHour
    let direction: String
    let count: Int

    var id: String { "\(hour.hour)-\(direction)" }
}

struct DailyTelemetry {
    let date: Date
    var hours: [TelemetryHour]

    static func empty(for date: Date, calendar: Calendar = .current) -> DailyTelemetry {
        let startOfDay = calendar.startOfDay(for: date)
        let hours = (0..<24).map { hour in
            TelemetryHour(
                hour: hour,
                startDate: calendar.date(byAdding: .hour, value: hour, to: startOfDay) ?? startOfDay,
                batteryLevel: nil,
                batterySampleCount: 0,
                chargingSampleCount: 0,
                messagesToGlasses: 0,
                messagesFromGlasses: 0
            )
        }
        return DailyTelemetry(date: startOfDay, hours: hours)
    }

    var batteryHours: [TelemetryHour] {
        hours.filter { $0.batteryLevel != nil }
    }

    var messagePoints: [TelemetryMessagePoint] {
        hours.flatMap { hour in
            [
                TelemetryMessagePoint(hour: hour, direction: "To Glass", count: hour.messagesToGlasses),
                TelemetryMessagePoint(hour: hour, direction: "From Glass", count: hour.messagesFromGlasses)
            ]
        }
    }

    var hasBatteryData: Bool {
        hours.contains { $0.batteryLevel != nil }
    }

    var hasMessageData: Bool {
        totalMessagesToGlasses > 0 || totalMessagesFromGlasses > 0
    }

    var totalMessagesToGlasses: Int {
        hours.reduce(0) { $0 + $1.messagesToGlasses }
    }

    var totalMessagesFromGlasses: Int {
        hours.reduce(0) { $0 + $1.messagesFromGlasses }
    }
}

final class TelemetryStore {
    private let queue = DispatchQueue(label: "AnotherGlass.TelemetryStore")
    private var database: OpaquePointer?
    private let calendar: Calendar
    private let transientDestructor = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init(calendar: Calendar = .current) {
        self.calendar = calendar
        queue.sync {
            do {
                try openDatabase()
                try migrate()
            } catch {
                print("Telemetry database unavailable: \(error.localizedDescription)")
            }
        }
    }

    deinit {
        queue.sync {
            if let database {
                sqlite3_close(database)
            }
        }
    }

    func recordBatteryStatus(_ status: BatteryStatus, at date: Date = Date()) {
        queue.async { [weak self] in
            guard let self, let database = self.database else { return }
            let sql = "INSERT INTO battery_samples(timestamp, level, is_charging) VALUES (?, ?, ?);"
            var statement: OpaquePointer?
            guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(statement) }

            sqlite3_bind_double(statement, 1, date.timeIntervalSince1970)
            sqlite3_bind_int(statement, 2, Int32(status.level))
            sqlite3_bind_int(statement, 3, status.isCharging ? 1 : 0)
            sqlite3_step(statement)
        }
    }

    func recordMessage(_ direction: GlassMessageDirection, message: RPCMessage, transport: CompanionTransport, at date: Date = Date()) {
        queue.async { [weak self] in
            guard let self, let database = self.database else { return }
            let sql = """
                INSERT INTO message_events(timestamp, direction, service, type, transport)
                VALUES (?, ?, ?, ?, ?);
                """
            var statement: OpaquePointer?
            guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(statement) }

            sqlite3_bind_double(statement, 1, date.timeIntervalSince1970)
            bind(direction.rawValue, to: statement, at: 2)
            bind(message.service, to: statement, at: 3)
            bind(message.type, to: statement, at: 4)
            bind(transport.rawValue, to: statement, at: 5)
            sqlite3_step(statement)
        }
    }

    func dailyTelemetry(for date: Date) -> DailyTelemetry {
        queue.sync {
            var telemetry = DailyTelemetry.empty(for: date, calendar: calendar)
            guard database != nil else { return telemetry }
            loadBatteryHours(into: &telemetry)
            loadMessageHours(into: &telemetry)
            return telemetry
        }
    }

    private func openDatabase() throws {
        let fileManager = FileManager.default
        guard let supportDirectory = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw TelemetryError.applicationSupportUnavailable
        }

        let directory = supportDirectory.appendingPathComponent("AnotherGlass", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let databaseURL = directory.appendingPathComponent("telemetry.sqlite")

        if sqlite3_open(databaseURL.path, &database) != SQLITE_OK {
            throw TelemetryError.openFailed(message: lastErrorMessage)
        }
    }

    private func migrate() throws {
        try execute("""
            CREATE TABLE IF NOT EXISTS battery_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp REAL NOT NULL,
                level INTEGER NOT NULL,
                is_charging INTEGER NOT NULL
            );
            """)
        try execute("""
            CREATE INDEX IF NOT EXISTS idx_battery_samples_timestamp
            ON battery_samples(timestamp);
            """)
        try execute("""
            CREATE TABLE IF NOT EXISTS message_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp REAL NOT NULL,
                direction TEXT NOT NULL,
                service TEXT,
                type TEXT,
                transport TEXT
            );
            """)
        try execute("""
            CREATE INDEX IF NOT EXISTS idx_message_events_timestamp_direction
            ON message_events(timestamp, direction);
            """)
    }

    private func loadBatteryHours(into telemetry: inout DailyTelemetry) {
        let interval = dayInterval(for: telemetry.date)
        let sql = """
            SELECT CAST(strftime('%H', datetime(timestamp, 'unixepoch', 'localtime')) AS INTEGER),
                   AVG(level),
                   COUNT(*),
                   SUM(is_charging)
            FROM battery_samples
            WHERE timestamp >= ? AND timestamp < ?
            GROUP BY 1;
            """
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(statement) }

        sqlite3_bind_double(statement, 1, interval.start.timeIntervalSince1970)
        sqlite3_bind_double(statement, 2, interval.end.timeIntervalSince1970)

        while sqlite3_step(statement) == SQLITE_ROW {
            let hour = Int(sqlite3_column_int(statement, 0))
            guard telemetry.hours.indices.contains(hour) else { continue }
            telemetry.hours[hour].batteryLevel = sqlite3_column_double(statement, 1)
            telemetry.hours[hour].batterySampleCount = Int(sqlite3_column_int(statement, 2))
            telemetry.hours[hour].chargingSampleCount = Int(sqlite3_column_int(statement, 3))
        }
    }

    private func loadMessageHours(into telemetry: inout DailyTelemetry) {
        let interval = dayInterval(for: telemetry.date)
        let sql = """
            SELECT CAST(strftime('%H', datetime(timestamp, 'unixepoch', 'localtime')) AS INTEGER),
                   direction,
                   COUNT(*)
            FROM message_events
            WHERE timestamp >= ? AND timestamp < ?
            GROUP BY 1, direction;
            """
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(statement) }

        sqlite3_bind_double(statement, 1, interval.start.timeIntervalSince1970)
        sqlite3_bind_double(statement, 2, interval.end.timeIntervalSince1970)

        while sqlite3_step(statement) == SQLITE_ROW {
            let hour = Int(sqlite3_column_int(statement, 0))
            guard telemetry.hours.indices.contains(hour),
                  let directionPointer = sqlite3_column_text(statement, 1) else { continue }

            let direction = String(cString: UnsafeRawPointer(directionPointer).assumingMemoryBound(to: CChar.self))
            let count = Int(sqlite3_column_int(statement, 2))
            switch GlassMessageDirection(rawValue: direction) {
            case .toGlasses:
                telemetry.hours[hour].messagesToGlasses = count
            case .fromGlasses:
                telemetry.hours[hour].messagesFromGlasses = count
            case .none:
                break
            }
        }
    }

    private func dayInterval(for date: Date) -> (start: Date, end: Date) {
        let start = calendar.startOfDay(for: date)
        let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86_400)
        return (start, end)
    }

    private func execute(_ sql: String) throws {
        var errorMessage: UnsafeMutablePointer<Int8>?
        if sqlite3_exec(database, sql, nil, nil, &errorMessage) != SQLITE_OK {
            let message = errorMessage.map { String(cString: $0) } ?? lastErrorMessage
            sqlite3_free(errorMessage)
            throw TelemetryError.queryFailed(message: message)
        }
    }

    private func bind(_ string: String?, to statement: OpaquePointer?, at index: Int32) {
        guard let string else {
            sqlite3_bind_null(statement, index)
            return
        }
        string.withCString {
            sqlite3_bind_text(statement, index, $0, -1, transientDestructor)
        }
    }

    private var lastErrorMessage: String {
        guard let database, let message = sqlite3_errmsg(database) else { return "Unknown SQLite error" }
        return String(cString: message)
    }
}

enum TelemetryError: LocalizedError {
    case applicationSupportUnavailable
    case openFailed(message: String)
    case queryFailed(message: String)

    var errorDescription: String? {
        switch self {
        case .applicationSupportUnavailable:
            return "Application Support directory is unavailable"
        case .openFailed(let message), .queryFailed(let message):
            return message
        }
    }
}
