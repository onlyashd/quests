import Foundation
import Shared

// Bridging helpers between Swift values and the Kotlin `Shared` framework.

extension KotlinByteArray {
    func toData() -> Data {
        var data = Data(count: Int(size))
        for i in 0..<Int(size) {
            data[i] = UInt8(bitPattern: get(index: Int32(i)))
        }
        return data
    }
}

extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (i, byte) in enumerated() {
            array.set(index: Int32(i), value: Int8(bitPattern: byte))
        }
        return array
    }
}

/// Keeps a Kotlin `Watchable` subscription alive for as long as the owner holds it.
final class Subscription {
    private var handle: Cancellable?

    init<T: AnyObject>(_ watchable: Watchable<T>, onEach: @escaping (T) -> Void) {
        handle = watchable.watch { value in onEach(value) }
    }

    deinit { handle?.cancel() }
}
