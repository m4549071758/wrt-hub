package wrthub.core.ubus

/** [code] is a ubus status (positive) or a uhttpd JSON-RPC error code (negative). */
class UbusException(val code: Int, message: String) : Exception(message) {
    companion object {
        const val INVALID_ARGUMENT = 2
        const val METHOD_NOT_FOUND = 3
        const val NOT_FOUND = 4
        const val NO_DATA = 5
        const val PERMISSION_DENIED = 6
        const val TIMEOUT = 7
        const val RPC_OBJECT_NOT_FOUND = -32000
        const val RPC_ACCESS_DENIED = -32002
        const val RPC_METHOD_NOT_FOUND = -32601
    }
}
