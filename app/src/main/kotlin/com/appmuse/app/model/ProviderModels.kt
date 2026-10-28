package com.appmuse.app.model

import kotlinx.serialization.Serializable

@Serializable
data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reasoningEffort: String = "default",
    val systemPrompt: String = "",
)

@Serializable
data class ProviderArchive(
    val profiles: List<ProviderProfile> = emptyList(),
    val selectedProfileId: String? = null,
)
