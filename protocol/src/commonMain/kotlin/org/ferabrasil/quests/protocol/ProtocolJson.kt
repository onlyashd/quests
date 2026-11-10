package org.ferabrasil.quests.protocol

import kotlinx.serialization.json.Json

/** The one JSON configuration used by client and server (backend.md §5: `explicitNulls = false`). */
val ProtocolJson: Json = Json {
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}
