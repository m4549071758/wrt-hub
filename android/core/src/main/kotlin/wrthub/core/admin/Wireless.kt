package wrthub.core.admin

import kotlinx.serialization.json.JsonObject
import wrthub.core.json.arr
import wrthub.core.json.asObject
import wrthub.core.json.asString
import wrthub.core.json.bool
import wrthub.core.json.int
import wrthub.core.json.obj
import wrthub.core.json.str

/** A `wifi-device`. [carriesMesh]: a `mode mesh` interface rides on it, so its channel and width are site-wide. */
data class Radio(
    val name: String,
    val band: String?,
    /** `auto` or a channel number. */
    val channel: String,
    val htmode: String?,
    /** dBm; null means the driver default. */
    val txpower: Int?,
    val carriesMesh: Boolean,
)

/** An AP `wifi-iface`; mesh interfaces are never listed. */
data class Ssid(val section: String, val ssid: String?, val radio: String, val enabled: Boolean)

data class WirelessConfig(val radios: List<Radio>, val ssids: List<Ssid>)

fun parseWireless(uciGet: JsonObject): WirelessConfig {
    val sections = uciGet.obj("values")?.values.orEmpty().mapNotNull { it.asObject() }.sortedBy { it.int(".index") ?: Int.MAX_VALUE }
    val ifaces = sections.filter { it.str(".type") == "wifi-iface" }
    val meshRadios = ifaces.filter { it.str("mode") == "mesh" && it.str("disabled") != "1" }.mapNotNull { it.str("device") }.toSet()
    return WirelessConfig(
        radios = sections.filter { it.str(".type") == "wifi-device" }.mapNotNull { o ->
            val name = o.str(".name") ?: return@mapNotNull null
            Radio(name, o.str("band"), o.str("channel") ?: "auto", o.str("htmode"), o.str("txpower")?.toIntOrNull(), name in meshRadios)
        },
        ssids = ifaces.filter { it.str("mode") == "ap" }.mapNotNull { o ->
            Ssid(o.str(".name") ?: return@mapNotNull null, o.str("ssid"), o.str("device") ?: return@mapNotNull null, o.str("disabled") != "1")
        },
    )
}

/** A channel the radio may use, with the iwinfo `flags` that rule out some widths (`no_ht40+`, `no_80mhz`, …). */
data class ChannelOption(val channel: Int, val mhz: Int, val flags: Set<String>)

/** What iwinfo says a radio can do; empty lists when the radio is down and iwinfo cannot answer. */
data class RadioCapabilities(val channels: List<ChannelOption>, val htmodes: List<String>, val txpowers: List<Int>)

fun parseCapabilities(info: JsonObject?, freqlist: JsonObject?, txpowerlist: JsonObject?): RadioCapabilities =
    RadioCapabilities(
        channels = freqlist?.arr("results").orEmpty().mapNotNull { it.asObject() }
            .filter { it.bool("restricted") != true }
            .mapNotNull { o ->
                ChannelOption(
                    o.int("channel") ?: return@mapNotNull null,
                    o.int("mhz") ?: 0,
                    o.arr("flags").orEmpty().mapNotNull { it.asString() }.toSet(),
                )
            },
        htmodes = info?.arr("htmodes").orEmpty().mapNotNull { it.asString() },
        txpowers = txpowerlist?.arr("results").orEmpty().mapNotNull { it.asObject()?.int("dbm") }.distinct().sorted(),
    )

/** The widths [capabilities] offer on [channel] (`auto`: all of them), dropping those its flags rule out. */
fun RadioCapabilities.htmodesFor(channel: String): List<String> {
    val flags = channels.firstOrNull { it.channel.toString() == channel }?.flags ?: return htmodes
    return htmodes.filter { mode ->
        val width = mode.dropWhile { it.isLetter() }
        when {
            mode.startsWith("HE") && "no_he" in flags -> false
            width == "40" && "no_ht40+" in flags && "no_ht40-" in flags -> false
            width == "80" && "no_80mhz" in flags -> false
            width == "160" && "no_160mhz" in flags -> false
            else -> true
        }
    }
}

/** [htmode] and [txpower] null: leave the option unset (driver default). */
data class RadioInput(val channel: String, val htmode: String?, val txpower: Int?)

/**
 * Checks [input] against what the radio can do. A radio that carries the mesh keeps its channel and width: changing
 * them on one node only would drop that node out of the mesh.
 */
fun Radio.validate(input: RadioInput, capabilities: RadioCapabilities) {
    if (carriesMesh) {
        require(input.channel == channel && input.htmode == htmode) { "$name carries the mesh; its channel and width stay as they are" }
    } else {
        require(input.channel == "auto" || capabilities.channels.any { it.channel.toString() == input.channel }) {
            "channel ${input.channel} is not available on $name"
        }
        require(input.htmode == htmode || input.htmode in capabilities.htmodesFor(input.channel)) {
            "width ${input.htmode} is not available on channel ${input.channel}"
        }
    }
    input.txpower?.let { require(it in capabilities.txpowers) { "transmit power $it dBm is not available on $name" } }
}
