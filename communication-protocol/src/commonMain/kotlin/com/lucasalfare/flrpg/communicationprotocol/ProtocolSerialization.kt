package com.lucasalfare.flrpg.communicationprotocol

import kotlinx.serialization.json.Json

val ProtocolJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  prettyPrint = false
  classDiscriminator = "messageType"
}

fun ProtocolMessage.toJson(): String = ProtocolJson.encodeToString(this)
inline fun <reified T : ProtocolMessage> String.toProtocolMessage(): T = ProtocolJson.decodeFromString(this)