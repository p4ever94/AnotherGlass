import Darwin
import Foundation

enum NetworkAddress {
    static func currentIPv4Address() -> String? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0 else { return nil }
        defer { freeifaddrs(interfaces) }

        var candidate: String?
        var cursor = interfaces

        while let current = cursor {
            defer { cursor = current.pointee.ifa_next }

            let interface = current.pointee
            guard let socketAddress = interface.ifa_addr else { continue }
            guard socketAddress.pointee.sa_family == UInt8(AF_INET) else { continue }

            let name = String(cString: interface.ifa_name)
            guard name == "en0" || name == "bridge100" else { continue }

            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                socketAddress,
                socklen_t(socketAddress.pointee.sa_len),
                &hostname,
                socklen_t(hostname.count),
                nil,
                0,
                NI_NUMERICHOST
            )

            guard result == 0 else { continue }
            candidate = String(cString: hostname)
            if name == "en0" {
                break
            }
        }

        return candidate
    }
}
