import Foundation

/// 历史记录存储（Documents/history.json）
final class HistoryStore: ObservableObject {
    @Published private(set) var records: [SessionRecord] = []

    private static let fileURL: URL = {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("history.json")
    }()

    init() { load() }

    func load() {
        guard let data = try? Data(contentsOf: Self.fileURL),
              let list = try? JSONDecoder().decode([SessionRecord].self, from: data) else { return }
        records = list.sorted { $0.date > $1.date }
    }

    func add(_ record: SessionRecord) {
        records.insert(record, at: 0)
        persist()
    }

    func delete(_ record: SessionRecord) {
        records.removeAll { $0.id == record.id }
        persist()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(records) else { return }
        try? data.write(to: Self.fileURL, options: .atomic)
    }
}
