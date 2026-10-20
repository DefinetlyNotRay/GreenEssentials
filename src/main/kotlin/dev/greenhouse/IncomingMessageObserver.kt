package dev.greenhouse

/** ALLOW can be bypassed by an earlier canceling mod; later cancellation must not count it twice. */
internal class IncomingMessageObserver<T: Any>(private val receive: (T) -> Unit) {
    private var observed: T? = null
    fun allow(message: T) { observed=message; receive(message) }
    fun canceled(message: T) {
        if (observed !== message) receive(message)
        observed=null
    }
    fun delivered() { observed=null }
}
