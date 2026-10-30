package com.appmuse.app.model

import kotlinx.serialization.Serializable

/** A provider connection plus its chosen model — layer 1 of the split archive. */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

/** A reusable behaviour preset — layer 2 of the split archive. Behaviours are
 *  provider-independent: any behaviour can pair with any [ProviderConfig]. */
@Serializable
data class BehaviorProfile(
    val id: String,
    val name: String,
    val reasoningEffort: String = "default",
    val systemPrompt: String = "",
)

@Serializable
data class ProviderArchive(
    val providers: List<ProviderConfig> = emptyList(),
    val behaviors: List<BehaviorProfile> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedBehaviorId: String? = null,
)

/** Runtime merge of a provider and the active behaviour — the shape the chat
 *  layer consumes. Not stored; [id] is the provider's id, so a conversation's
 *  stored profileId keeps meaning "the provider this conversation belongs to". */
data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reasoningEffort: String = "default",
    val systemPrompt: String = "",
)

fun ProviderConfig.resolvedWith(behavior: BehaviorProfile?): ProviderProfile = ProviderProfile(
    id = id,
    name = name,
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    reasoningEffort = behavior?.reasoningEffort ?: "default",
    systemPrompt = behavior?.systemPrompt ?: "",
)

/** Pre-split stored shapes, kept only so the repository can migrate old files. */
@Serializable
data class LegacyProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reasoningEffort: String = "default",
    val systemPrompt: String = "",
)

@Serializable
data class LegacyProviderArchive(
    val profiles: List<LegacyProviderProfile> = emptyList(),
    val selectedProfileId: String? = null,
)

/** 1:1 migration: each legacy profile becomes one provider AND one behaviour
 *  sharing the legacy id, so conversation profileIds and the legacy selection
 *  keep resolving on both layers. */
fun LegacyProviderArchive.migrated(): ProviderArchive = ProviderArchive(
    providers = profiles.map { ProviderConfig(it.id, it.name, it.baseUrl, it.apiKey, it.model) },
    behaviors = profiles.map { BehaviorProfile(it.id, it.name, it.reasoningEffort, it.systemPrompt) },
    selectedProviderId = selectedProfileId,
    selectedBehaviorId = selectedProfileId,
)
