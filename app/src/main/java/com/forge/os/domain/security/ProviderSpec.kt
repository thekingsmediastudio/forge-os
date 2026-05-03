package com.forge.os.domain.security

/**
 * A concrete (provider, model) pair the chat layer should use for a single call.
 * - [Builtin] points at one of the [ApiKeyProvider] enum entries.
 * - [Custom] points at a user-defined endpoint stored in [CustomEndpointRepository].
 *
 * `model == null` means "use the provider's default model".
 */
sealed class ProviderSpec {
    abstract val displayLabel: String
    abstract val schema: ProviderSchema
    abstract val effectiveModel: String

    data class Builtin(
        val provider: ApiKeyProvider,
        val model: String? = null
    ) : ProviderSpec() {
        override val displayLabel get() = "${provider.displayName} · ${effectiveModel}"
        override val schema get() = provider.schema
        override val effectiveModel get() = model ?: provider.defaultModel
    }

    data class Custom(
        val endpoint: CustomEndpoint,
        val model: String? = null
    ) : ProviderSpec() {
        override val displayLabel get() = "${endpoint.name} · ${effectiveModel}"
        override val schema get() = endpoint.schema
        override val effectiveModel get() = model ?: endpoint.defaultModel
    }
}
