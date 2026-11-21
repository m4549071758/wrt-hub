package wrthub.core.admin

import kotlinx.serialization.json.JsonObject
import wrthub.core.json.arr
import wrthub.core.json.asObject
import wrthub.core.json.asString
import wrthub.core.json.obj
import wrthub.core.json.str

/** The single `system` section and the `timeserver ntp` section. */
data class SystemConfig(
    val section: String,
    val hostname: String,
    /** `Asia/Tokyo`; null when only a POSIX `timezone` string is set. */
    val zonename: String?,
    val ntpEnabled: Boolean,
    val ntpServers: List<String>,
)

data class SystemInput(val hostname: String, val zonename: String, val ntpEnabled: Boolean, val ntpServers: List<String>)

fun parseSystem(uciGet: JsonObject): SystemConfig? {
    val sections = uciGet.obj("values")?.values.orEmpty().mapNotNull { it.asObject() }
    val system = sections.firstOrNull { it.str(".type") == "system" } ?: return null
    val ntp = sections.firstOrNull { it.str(".type") == "timeserver" && it.str(".name") == "ntp" }
    return SystemConfig(
        section = system.str(".name") ?: return null,
        hostname = system.str("hostname") ?: "OpenWrt",
        zonename = system.str("zonename"),
        ntpEnabled = ntp?.str("enabled") != "0",
        ntpServers = stringList(ntp?.get("server")),
    )
}

/** `luci getTimezones`: zone name to the POSIX string that `system.timezone` needs. */
fun parseTimezones(response: JsonObject): Map<String, String> =
    response.entries.mapNotNull { (zone, value) -> value.asObject()?.str("tzstring")?.let { zone to it } }.toMap()

private val NTP_SERVER = Regex("^[A-Za-z0-9.:-]{1,253}$")

fun SystemInput.validate(timezones: Map<String, String>) {
    requireValidHostname(hostname)
    require(zonename in timezones) { "unknown time zone: $zonename" }
    require(ntpServers.size <= 8) { "at most 8 NTP servers" }
    ntpServers.forEach { require(NTP_SERVER.matches(it) && !it.startsWith("-")) { "invalid NTP server: $it" } }
}

/** An LED as `luci getLEDs` reports it, with the app's or the user's `led` section for it, if any. */
data class Led(
    val name: String,
    val triggers: List<String>,
    val activeTrigger: String?,
    val section: String?,
    /** The `dev` of a netdev trigger. */
    val device: String?,
)

data class LedInput(val trigger: String, val device: String?)

fun parseLeds(getLeds: JsonObject, system: JsonObject): List<Led> {
    val sections = system.obj("values")?.values.orEmpty().mapNotNull { it.asObject() }.filter { it.str(".type") == "led" }
    return getLeds.entries.mapNotNull { (name, value) ->
        val o = value.asObject() ?: return@mapNotNull null
        val section = sections.firstOrNull { it.str("sysfs") == name }
        Led(
            name = name,
            triggers = o.arr("triggers").orEmpty().mapNotNull { it.asString() },
            activeTrigger = o.str("active_trigger"),
            section = section?.str(".name"),
            device = section?.str("dev"),
        )
    }.sortedBy { it.name }
}

/** uci section names allow only letters, digits and underscores; LED names have colons and hyphens. */
fun ledSectionName(led: String): String = APP_SECTION_PREFIX + "led_" + led.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

private val DEVICE = Regex("^[A-Za-z0-9._@-]{1,15}$")

fun Led.validate(input: LedInput) {
    require(input.trigger in triggers) { "$name has no trigger ${input.trigger}" }
    if (input.trigger == "netdev") require(input.device != null && DEVICE.matches(input.device)) { "netdev needs a device" }
}

/** The uci options of an `led` section; netdev blinks on link, transmit and receive like LuCI's default. */
fun LedInput.uciValues(led: String): Map<String, String> = buildMap {
    put("name", led)
    put("sysfs", led)
    put("trigger", trigger)
    put("default", if (trigger == "default-on") "1" else "0")
    if (trigger == "netdev") {
        put("dev", device!!)
        put("mode", "link tx rx")
    }
}

/** Crontab size LuCI's file.write path handles comfortably; larger is surely a mistake on a router. */
const val MAX_CRONTAB_BYTES = 16 * 1024

fun requireValidCrontab(text: String) {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_CRONTAB_BYTES) { "crontab is larger than $MAX_CRONTAB_BYTES bytes" }
    require(text.none { it == '\u0000' }) { "crontab contains NUL" }
}
