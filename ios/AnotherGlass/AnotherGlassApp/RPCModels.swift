import CoreLocation
import Foundation

enum ServiceID {
    static let defaultPort: UInt16 = 9090
    static let gpsMinimumInterval: TimeInterval = 30

    static let gps = "MockGPS"
    static let media = "Media"
    static let device = "device"
    static let notifications = "Notifications"
    static let siri = "Siri"
    static let call = "Call"
}

enum JavaClassName {
    static let location = "com.damn.anotherglass.shared.gps.Location"
    static let battery = "com.damn.anotherglass.shared.device.BatteryStatusData"
    static let mediaCommand = "com.damn.anotherglass.shared.media.MediaCommandData"
    static let mediaState = "com.damn.anotherglass.shared.media.MediaStateData"
    static let notification = "com.damn.anotherglass.shared.notifications.NotificationData"
    static let siriRequest = "com.damn.anotherglass.shared.siri.SiriRequestData"
    static let contactsRequest = "com.damn.anotherglass.shared.call.ContactsRequestData"
    static let contactList = "com.damn.anotherglass.shared.call.ContactListData"
    static let callRequest = "com.damn.anotherglass.shared.call.CallRequestData"
}

struct RPCMessage: Codable {
    var service: String?
    var type: String?
    var payload: Payload?

    static let disconnect = RPCMessage(service: nil, type: nil, payload: nil)

    static func location(_ location: GlassLocation) -> RPCMessage {
        RPCMessage(service: ServiceID.gps, type: JavaClassName.location, payload: .location(location))
    }

    static func mediaState(_ state: MediaStateData) -> RPCMessage {
        RPCMessage(service: ServiceID.media, type: JavaClassName.mediaState, payload: .mediaState(state))
    }

    static func notification(_ notification: NotificationData) -> RPCMessage {
        RPCMessage(service: ServiceID.notifications, type: JavaClassName.notification, payload: .notification(notification))
    }

    static func contactList(_ contacts: ContactListData) -> RPCMessage {
        RPCMessage(service: ServiceID.call, type: JavaClassName.contactList, payload: .contactList(contacts))
    }

    init(service: String?, type: String?, payload: Payload?) {
        self.service = service
        self.type = type
        self.payload = payload
    }

    enum CodingKeys: String, CodingKey {
        case service
        case type
        case payload
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        service = try container.decodeIfPresent(String.self, forKey: .service)
        type = try container.decodeIfPresent(String.self, forKey: .type)

        guard let type, container.contains(.payload) else {
            payload = nil
            return
        }

        switch type {
        case JavaClassName.location:
            payload = (try? .location(container.decode(GlassLocation.self, forKey: .payload))) ?? .unsupported
        case JavaClassName.battery:
            payload = (try? .battery(container.decode(BatteryStatus.self, forKey: .payload))) ?? .unsupported
        case JavaClassName.mediaCommand:
            payload = (try? .mediaCommand(container.decode(MediaCommandData.self, forKey: .payload))) ?? .unsupported
        case JavaClassName.mediaState:
            payload = (try? .mediaState(container.decode(MediaStateData.self, forKey: .payload))) ?? .unsupported
        case JavaClassName.notification:
            payload = (try? .notification(container.decode(NotificationData.self, forKey: .payload))) ?? .unsupported
        case JavaClassName.siriRequest:
            payload = (try? .siriRequest(container.decode(SiriRequestData.self, forKey: .payload))) ?? .unsupported
        case JavaClassName.contactsRequest:
            payload = (try? .contactsRequest(container.decode(ContactsRequestData.self, forKey: .payload))) ?? .unsupported
        case JavaClassName.contactList:
            payload = (try? .contactList(container.decode(ContactListData.self, forKey: .payload))) ?? .unsupported
        case JavaClassName.callRequest:
            payload = (try? .callRequest(container.decode(CallRequestData.self, forKey: .payload))) ?? .unsupported
        default:
            payload = .unsupported
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(service, forKey: .service)
        try container.encodeIfPresent(type, forKey: .type)

        switch payload {
        case .location(let location):
            try container.encode(location, forKey: .payload)
        case .battery(let battery):
            try container.encode(battery, forKey: .payload)
        case .mediaCommand(let command):
            try container.encode(command, forKey: .payload)
        case .mediaState(let state):
            try container.encode(state, forKey: .payload)
        case .notification(let notification):
            try container.encode(notification, forKey: .payload)
        case .siriRequest(let request):
            try container.encode(request, forKey: .payload)
        case .contactsRequest(let request):
            try container.encode(request, forKey: .payload)
        case .contactList(let contacts):
            try container.encode(contacts, forKey: .payload)
        case .callRequest(let request):
            try container.encode(request, forKey: .payload)
        case .unsupported, .none:
            break
        }
    }
}

enum Payload {
    case location(GlassLocation)
    case battery(BatteryStatus)
    case mediaCommand(MediaCommandData)
    case mediaState(MediaStateData)
    case notification(NotificationData)
    case siriRequest(SiriRequestData)
    case contactsRequest(ContactsRequestData)
    case contactList(ContactListData)
    case callRequest(CallRequestData)
    case unsupported
}

struct GlassLocation: Codable {
    var latitude: Double
    var longitude: Double
    var altitude: Double
    var speed: Float
    var bearing: Float
    var accuracy: Float

    init(_ location: CLLocation) {
        latitude = location.coordinate.latitude
        longitude = location.coordinate.longitude
        altitude = location.altitude
        speed = Float(max(location.speed, 0))
        bearing = Float(max(location.course, 0))
        accuracy = Float(location.horizontalAccuracy)
    }

    var displayText: String {
        String(format: "%.5f, %.5f", latitude, longitude)
    }
}

struct BatteryStatus: Codable {
    var level: Int
    var isCharging: Bool

    enum CodingKeys: String, CodingKey {
        case level
        case isCharging
        case charging
    }

    init(level: Int, isCharging: Bool) {
        self.level = level
        self.isCharging = isCharging
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        level = try container.decode(Int.self, forKey: .level)
        isCharging = (try? container.decode(Bool.self, forKey: .isCharging))
            ?? (try? container.decode(Bool.self, forKey: .charging))
            ?? false
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(level, forKey: .level)
        try container.encode(isCharging, forKey: .isCharging)
    }
}

struct MediaCommandData: Codable {
    var command: MediaCommand
    var seekToMs: Int64
}

enum MediaCommand: String, Codable {
    case play = "Play"
    case pause = "Pause"
    case togglePlayPause = "TogglePlayPause"
    case next = "Next"
    case previous = "Previous"
    case seekTo = "SeekTo"
}

struct MediaStateData: Codable {
    var playbackState: PlaybackStateValue
    var title: String?
    var artist: String?
    var album: String?
    var artwork: BinaryData?
    var sourceApp: String?
    var sourcePackage: String?
    var positionMs: Int64
    var durationMs: Int64
    var actionsMask: Int64
    var lastUpdatedMs: Int64

    static var empty: MediaStateData {
        MediaStateData(
            playbackState: .none,
            title: nil,
            artist: nil,
            album: nil,
            artwork: nil,
            sourceApp: nil,
            sourcePackage: nil,
            positionMs: 0,
            durationMs: 0,
            actionsMask: 0,
            lastUpdatedMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
    }
}

enum PlaybackStateValue: String, Codable {
    case none = "None"
    case playing = "Playing"
    case paused = "Paused"
    case stopped = "Stopped"
    case buffering = "Buffering"
}

struct BinaryData: Codable {
    var bytes: Data
}

struct NotificationData: Codable {
    var action: NotificationAction
    var id: Int
    var packageName: String
    var appName: String?
    var postedTime: Int64
    var isOngoing: Bool
    var title: String?
    var text: String?
    var tickerText: String?
    var icon: BinaryData?
    var image: BinaryData?
    var deliveryMode: NotificationDeliveryMode?
    var conversationTitle: String?
    var isGroupConversation: Bool
    var messages: [NotificationMessage]

    static var fake: NotificationData {
        NotificationData(
            action: .posted,
            id: Int(Date().timeIntervalSince1970),
            packageName: "ios.test",
            appName: "iPhone Test",
            postedTime: Int64(Date().timeIntervalSince1970 * 1000),
            isOngoing: false,
            title: "AnotherGlass test",
            text: "This fake notification came from the iOS companion.",
            tickerText: nil,
            icon: nil,
            image: nil,
            deliveryMode: .sound,
            conversationTitle: nil,
            isGroupConversation: false,
            messages: []
        )
    }
}

enum NotificationAction: String, Codable {
    case posted = "Posted"
    case removed = "Removed"
}

enum NotificationDeliveryMode: String, Codable {
    case silent = "Silent"
    case sound = "Sound"
}

struct NotificationMessage: Codable {
    var sender: String?
    var text: String?
    var time: Int64
    var senderIcon: BinaryData?
}

struct SiriRequestData: Codable {
    var requestedAtMs: Int64
}

struct ContactsRequestData: Codable {
    var requestedAtMs: Int64
}

struct ContactListData: Codable {
    var contacts: [ContactData]
}

struct ContactData: Codable {
    var id: String?
    var displayName: String?
    var phoneNumber: String?
    var label: String?
}

struct CallRequestData: Codable {
    var displayName: String?
    var phoneNumber: String
}
