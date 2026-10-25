package com.lucasalfare.flrpg.communicationprotocol

data class ProtocolLimits(
  val maxTextLength: Int = 4096,
  val maxPatchOperations: Int = 100,
  val maxBlocksPerDocument: Int = 50,
  val maxChoicesPerBlock: Int = 20,
  val maxItemsPerList: Int = 100,
  val maxParametersPerAction: Int = 50
)

sealed interface ValidationError {
  val message: String

  data class Structural(override val message: String) : ValidationError
  data class Protocol(override val message: String) : ValidationError
}

sealed interface ValidationResult {
  data object Valid : ValidationResult
  data class Invalid(val error: ValidationError) : ValidationResult

  val isValid: Boolean get() = this is Valid
}