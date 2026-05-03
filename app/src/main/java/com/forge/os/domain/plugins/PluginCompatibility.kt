package com.forge.os.domain.plugins

/**
 * Phase Q — host plugin API version. Bump [HOST_API_VERSION] whenever the
 * built-in tool surface or the plugin entrypoint contract changes in a way
 * that could break existing plugins.
 *
 * Plugins declare a [PluginManifest.minApiVersion] and (optionally)
 * [PluginManifest.maxApiVersion]. The PluginManager refuses to load any
 * plugin whose declared range does not include [HOST_API_VERSION].
 */
object PluginCompatibility {
    const val HOST_API_VERSION: Int = 2

    fun isCompatible(manifest: PluginManifest): Boolean {
        val min = manifest.minApiVersion ?: 1
        val max = manifest.maxApiVersion ?: Int.MAX_VALUE
        return HOST_API_VERSION in min..max
    }

    fun reasonIfIncompatible(manifest: PluginManifest): String? {
        if (isCompatible(manifest)) return null
        val min = manifest.minApiVersion ?: 1
        val max = manifest.maxApiVersion ?: Int.MAX_VALUE
        return "Plugin '${manifest.id}' requires API $min..$max but host is $HOST_API_VERSION"
    }
}
