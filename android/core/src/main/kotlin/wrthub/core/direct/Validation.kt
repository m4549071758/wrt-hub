package wrthub.core.direct

private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")

fun normalizeMac(mac: String): String {
    val normalized = mac.trim().lowercase()
    require(MAC.matches(normalized)) { "invalid MAC address: $mac" }
    return normalized
}

fun blockSectionName(mac: String): String = BLOCK_SECTION_PREFIX + normalizeMac(mac).replace(":", "")

fun requireValidHost(host: String): String {
    require(HOST.matches(host) && !host.startsWith("-")) { "invalid host: $host" }
    return host
}

fun requireValidSsid(ssid: String) {
    val bytes = ssid.toByteArray(Charsets.UTF_8).size
    require(bytes in 1..32) { "SSID must be 1-32 bytes, was $bytes" }
}

/** The key itself never appears in the message. */
fun requireValidPsk(key: String) {
    require(key.length in 8..63 && key.all { it.code in 0x20..0x7e }) {
        "Wi-Fi password must be 8-63 printable ASCII characters"
    }
}
