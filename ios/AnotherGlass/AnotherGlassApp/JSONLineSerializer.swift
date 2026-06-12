import Foundation

enum JSONLineSerializer {
    static func encode(_ message: RPCMessage) throws -> Data {
        let encoder = JSONEncoder()
        let payload = try encoder.encode(message)
        var line = Data()
        line.append(payload)
        line.append(UInt8(ascii: "\n"))
        return line
    }

    static func decode(_ data: Data) throws -> RPCMessage {
        let decoder = JSONDecoder()
        return try decoder.decode(RPCMessage.self, from: data)
    }

    static func logReceived(_ data: Data, transport: String) {
        let rawMessage = String(data: data, encoding: .utf8) ?? data.map { String(format: "%02x", $0) }.joined()
        print("[AnotherGlass][\(transport)][received] \(rawMessage)")
    }
}
