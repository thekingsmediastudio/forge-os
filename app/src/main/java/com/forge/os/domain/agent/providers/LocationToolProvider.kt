package com.forge.os.domain.agent.providers

import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Location tools — give the Forge OS agent awareness of where the device is.
 *
 * ## Tools
 *
 * | Tool                   | What it does                                              |
 * |------------------------|-----------------------------------------------------------|
 * | location_current       | Current GPS/network coordinates, accuracy, altitude, speed|
 * | location_address       | Reverse-geocode coordinates → human-readable address      |
 * | location_open_maps     | Open a location or search query in Google Maps / Maps app |
 * | location_open_settings | Open Location settings so user can grant permission       |
 *
 * ## Permissions
 * ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION are already declared in
 * AndroidManifest.xml (added for Wi-Fi tools). Location is a runtime permission —
 * the agent receives a clear, actionable error when it has not been granted yet,
 * and location_open_settings can guide the user to grant it.
 *
 * ## Strategy
 * Uses the last-known location from the best available provider (GPS > network > passive).
 * Last-known is instant (no battery drain) and sufficient for most use-cases.
 * Falls back across providers so it always returns the freshest available fix.
 */
@Singleton
class LocationToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    private val lm: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name        = "location_current",
            description = "Get the device's current location: latitude, longitude, accuracy " +
                          "(meters), altitude (meters, if available), speed (m/s, if available), " +
                          "bearing (degrees, if available), the provider used (gps/network/fused), " +
                          "and the age of the fix in seconds. " +
                          "Uses last-known location for instant response with no battery impact. " +
                          "Requires ACCESS_FINE_LOCATION permission at runtime.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "location_address",
            description = "Reverse-geocode the device's current location (or supplied coordinates) " +
                          "into a human-readable postal address: street, city, state, country, " +
                          "postal code, and place name. " +
                          "Requires ACCESS_FINE_LOCATION and a network connection (Geocoder calls " +
                          "the OS geocoding service). Pass lat/lng to geocode a specific point, " +
                          "or omit them to use the current device location.",
            params      = mapOf(
                "lat" to ("number" to "Latitude to reverse-geocode (omit to use current location)"),
                "lng" to ("number" to "Longitude to reverse-geocode (omit to use current location)"),
            ),
            required    = emptyList(),
        ),
        tool(
            name        = "location_open_maps",
            description = "Open Google Maps (or the default maps app) to a specific location or " +
                          "search query. Supply either a lat/lng pair, a search query (e.g. " +
                          "'coffee near me', 'Eiffel Tower'), or both. Starts navigation if " +
                          "navigate=true is set. Useful for showing the user a place or starting " +
                          "turn-by-turn directions.",
            params      = mapOf(
                "lat"       to ("number" to "Latitude of target location"),
                "lng"       to ("number" to "Longitude of target location"),
                "query"     to ("string"  to "Search term, place name, or address (e.g. 'Central Park NYC')"),
                "navigate"  to ("boolean" to "If true, start navigation mode (default false)"),
                "zoom"      to ("integer" to "Map zoom level 1-21 (default 15)"),
            ),
            required    = emptyList(),
        ),
        tool(
            name        = "location_open_settings",
            description = "Open the Android Location Settings screen so the user can enable " +
                          "location services and grant the ACCESS_FINE_LOCATION permission to " +
                          "Forge OS. Call this when location_current returns a permission error.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? = when (toolName) {
        "location_current"       -> locationCurrent()
        "location_address"       -> locationAddress(
            lat = args["lat"]?.toString()?.toDoubleOrNull(),
            lng = args["lng"]?.toString()?.toDoubleOrNull(),
        )
        "location_open_maps"     -> openMaps(
            lat      = args["lat"]?.toString()?.toDoubleOrNull(),
            lng      = args["lng"]?.toString()?.toDoubleOrNull(),
            query    = args["query"]?.toString() ?: "",
            navigate = args["navigate"]?.toString()?.toBooleanStrictOrNull() ?: false,
            zoom     = args["zoom"]?.toString()?.toIntOrNull() ?: 15,
        )
        "location_open_settings" -> openLocationSettings()
        else                     -> null
    }

    // ── location_current ──────────────────────────────────────────────────────

    private fun locationCurrent(): String = runCatching {
        val loc = bestLastKnown()
            ?: return err("No location fix available. Ensure Location is enabled and " +
                          "ACCESS_FINE_LOCATION has been granted to Forge OS. " +
                          "Try location_open_settings to enable it.")

        val ageSeconds = (System.currentTimeMillis() - loc.time) / 1000L

        buildString {
            appendLine("{")
            appendLine("  \"ok\": true,")
            appendLine("  \"lat\": ${loc.latitude},")
            appendLine("  \"lng\": ${loc.longitude},")
            appendLine("  \"accuracy_m\": ${loc.accuracy},")
            if (loc.hasAltitude())  appendLine("  \"altitude_m\": ${loc.altitude},")
            if (loc.hasSpeed())     appendLine("  \"speed_ms\": ${loc.speed},")
            if (loc.hasBearing())   appendLine("  \"bearing_deg\": ${loc.bearing},")
            appendLine("  \"provider\": ${jsonStr(loc.provider ?: "unknown")},")
            appendLine("  \"fix_age_seconds\": $ageSeconds")
            append("}")
        }
    }.getOrElse {
        if ("permission" in (it.message ?: "").lowercase())
            err("ACCESS_FINE_LOCATION permission not granted. Call location_open_settings to enable it.")
        else
            err(it.message ?: "Failed to read location")
    }

    // ── location_address ──────────────────────────────────────────────────────

    private fun locationAddress(lat: Double?, lng: Double?): String = runCatching {
        val finalLat: Double
        val finalLng: Double

        if (lat != null && lng != null) {
            finalLat = lat; finalLng = lng
        } else {
            val loc = bestLastKnown()
                ?: return err("No location fix available and no lat/lng provided. " +
                              "Either supply lat/lng or ensure location permission is granted.")
            finalLat = loc.latitude; finalLng = loc.longitude
        }

        if (!Geocoder.isPresent()) return err("Geocoder not available on this device.")

        val addresses: List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val latch = CountDownLatch(1)
            var result: List<Address>? = null
            Geocoder(context, Locale.getDefault()).getFromLocation(finalLat, finalLng, 1) { addrs ->
                result = addrs; latch.countDown()
            }
            latch.await(5, TimeUnit.SECONDS)
            result
        } else {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocation(finalLat, finalLng, 1)
        }

        val addr = addresses?.firstOrNull()
            ?: return """{"ok":true,"lat":$finalLat,"lng":$finalLng,"address":null,"note":"No address found for these coordinates"}"""

        buildString {
            appendLine("{")
            appendLine("  \"ok\": true,")
            appendLine("  \"lat\": $finalLat,")
            appendLine("  \"lng\": $finalLng,")
            appendLine("  \"place_name\": ${jsonStr(addr.featureName ?: "")},")
            appendLine("  \"street\": ${jsonStr(buildStreet(addr))},")
            appendLine("  \"city\": ${jsonStr(addr.locality ?: addr.subAdminArea ?: "")},")
            appendLine("  \"state\": ${jsonStr(addr.adminArea ?: "")},")
            appendLine("  \"postal_code\": ${jsonStr(addr.postalCode ?: "")},")
            appendLine("  \"country\": ${jsonStr(addr.countryName ?: "")},")
            appendLine("  \"country_code\": ${jsonStr(addr.countryCode ?: "")},")
            appendLine("  \"full_address\": ${jsonStr(buildFullAddress(addr))}")
            append("}")
        }
    }.getOrElse {
        if ("permission" in (it.message ?: "").lowercase())
            err("ACCESS_FINE_LOCATION permission not granted. Call location_open_settings.")
        else
            err(it.message ?: "Geocoding failed")
    }

    // ── location_open_maps ────────────────────────────────────────────────────

    private fun openMaps(lat: Double?, lng: Double?, query: String, navigate: Boolean, zoom: Int): String = runCatching {
        val z = zoom.coerceIn(1, 21)
        val uri: Uri = when {
            navigate && lat != null && lng != null ->
                Uri.parse("google.navigation:q=$lat,$lng")

            lat != null && lng != null && query.isNotBlank() ->
                Uri.parse("geo:$lat,$lng?z=$z&q=${Uri.encode(query)}")

            lat != null && lng != null ->
                Uri.parse("geo:$lat,$lng?z=$z")

            query.isNotBlank() ->
                Uri.parse("geo:0,0?q=${Uri.encode(query)}")

            else ->
                return err("Provide at least one of: lat+lng, query, or both.")
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.apps.maps")   // prefer Google Maps
        }

        // Fall back to any maps app if Google Maps not installed
        val resolved = context.packageManager.resolveActivity(intent, 0)
        val finalIntent = if (resolved != null) intent else intent.apply { setPackage(null) }

        context.startActivity(finalIntent)
        """{"ok":true,"output":"Opened Maps","uri":"$uri"}"""
    }.getOrElse { err("Could not open Maps: ${it.message}") }

    // ── location_open_settings ────────────────────────────────────────────────

    private fun openLocationSettings(): String = runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        """{"ok":true,"output":"Opened Location Settings"}"""
    }.getOrElse { err("Could not open Location Settings: ${it.message}") }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun bestLastKnown(): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }   // freshest fix wins
    }

    private fun buildStreet(addr: Address): String = buildString {
        val num  = addr.subThoroughfare
        val road = addr.thoroughfare
        if (num  != null) append("$num ")
        if (road != null) append(road)
    }.trim()

    private fun buildFullAddress(addr: Address): String =
        (0..addr.maxAddressLineIndex).joinToString(", ") { addr.getAddressLine(it) }

    private fun jsonStr(s: String) =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun err(msg: String) = """{"ok":false,"error":"${msg.replace("\"", "'")}"}"""

    private fun tool(
        name: String,
        description: String,
        params: Map<String, Pair<String, String>>,
        required: List<String>,
    ) = ToolDefinition(
        function = FunctionDefinition(
            name        = name,
            description = description,
            parameters  = FunctionParameters(
                properties = params.mapValues { (_, v) ->
                    ParameterProperty(type = v.first, description = v.second)
                },
                required   = required,
            ),
        ),
    )
}
