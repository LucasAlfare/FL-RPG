# Documentação do Sistema

---

## 1. Value Classes

Tipos imutáveis com validação integrada no bloco `init`.

| Classe                                 | Descrição                                     | Validação / Comportamento             |
|:---------------------------------------|:----------------------------------------------|:--------------------------------------|
| `ThingId(value: String)`               | ID para instâncias de `Thing`                 | Lança erro se `value` for vazio/blank |
| `CapabilityId(value: String)`          | ID para capacidades (`Capability`)            | —                                     |
| `ParticipantId(value: String)`         | ID para participantes (`Participant`)         | —                                     |
| `InteractionId(value: String)`         | ID para interações (`Interaction`)            | —                                     |
| `InformationId(value: String)`         | ID para informações (`ObservableInformation`) | —                                     |
| `PresentationPriority(value: Int = 0)` | Prioridade de exibição                        | Comparação natural (`Comparable`)     |

---

## 2. Entidades Principais

### Interfaces e Implementações

* **`Thing`**: Objeto interativo no sistema.
    * **Propriedades:** `id: ThingId`, `state: ThingState`, `capabilities: Set<Capability>`
    * **Métodos:** `updateState(newState: ThingState)`
    * **Implementação:** `BasicThing(id, currentState, capabilities)`

* **`PrioritizedThing`**: Estende `Thing` com suporte à prioridade.
    * **Propriedades:** `presentationPriority: PresentationPriority`
    * **Implementação:** `BasicPrioritizedThing(id, currentState, capabilities, presentationPriority)`

* **`Participant`**: Entidade que executa interações.
    * **Propriedades:** `id: ParticipantId`
    * **Implementação:** `BasicParticipant(id)`

---

## 3. Contexto e Resoluções

### `InteractionContext` *(Interface)*

* `findThing(id: ThingId): Thing?` — Busca objeto por ID.
* `findParticipant(id: ParticipantId): Participant?` — Busca participante por ID.
* `getContainmentRegistry(): ContainmentRegistry?` — Retorna registro de conteiners (padrão: `null`).

---

## 4. Sistema de Interações

### **`InteractionCondition`** *(Fun Interface)*

`isSatisfied(interaction: Interaction, context: InteractionContext): Boolean`

* **Agregadores:**
    * `AllInteractionConditions(conditions: List<InteractionCondition>)` — `true` se **todas** passarem.
    * `AnyInteractionConditions(conditions: List<InteractionCondition>)` — `true` se **pelo menos uma** passar.

### **`InteractionEffect`** *(Fun Interface)*

`apply(interaction: Interaction, context: InteractionContext)`

* **Implementações:**
    * `CompositeInteractionEffect(effects: List<InteractionEffect>)` — Executa efeitos em sequência.
    * `ChangeThingStateEffect(targetId: ThingId, newState: ThingState)` — Altera o estado do `Thing` no contexto.

### **`Interaction`** *(Interface / Execução)*

* **Propriedades:** `id`, `capabilityId`, `targetId`, `participantId`, `conditions`, `effects`
* **Métodos:**
    * `isAvailable(context): Boolean` — Checa se todas as `conditions` são válidas.
    * `execute(context): InteractionResult` — Executa e retorna o resultado.
* **Retornos (`InteractionResult`):**
    * `Success(interactionId, modifiedThingIds)`
    * `Refused(interactionId)` — Condições não satisfeitas.
    * `Invalid(interactionId)` — `Thing` ou `Participant` não encontrado no contexto.

---

## 5. Registro de Hierarquia e Conteiners

### `ContainmentRegistry`

Gerencia aninhamento e acoplamento de elementos (`Thing`), prevenindo autorreferência e loops.

* `add(containerId: ThingId, contentId: ThingId): Boolean` — Associa filho ao pai.
* `remove(contentId: ThingId): Boolean` — Desassocia filho do pai.
* `move(contentId: ThingId, newContainerId: ThingId): Boolean` — Reagrupa item em outro pai.
* `findContainerOf(contentId: ThingId): ThingId?` — Busca ID do pai.
* `findContentsOf(containerId: ThingId): Set<ThingId>` — Lista IDs contidos.

### Utilitário de Construção Árvore

* **`CompositeThingInitializer(thing, initialContents)`**
    * `build(registry: ContainmentRegistry): Thing` — Registra a árvore de conteúdos no `ContainmentRegistry`.

---

## 6. Visibilidade e Observabilidade

### Exposição (`ExposureRule`)

* `evaluateExposure(contentId, context): ExposureStatus` (`EXPOSED` | `UNEXPOSED`)
* `ContainerStateExposureRule` — Avalia visibilidade via predicado no estado do conteiner (
  `isContainerExposing(state)`).

### Informações Observáveis

* **`InformationLevel`**: `BASIC`, `DETAILED`, `THOROUGH`
* **`InformationHolder`**: Interface para filtrar dados por `InformationLevel` via `getInformationForLevel()`.

### Avaliação (`ObservabilityEvaluator`)

* **`BasicObservabilityEvaluator`**: Avalia `targetId` baseado na distância (`range`) definida no `ObservationContext`:
    * $range \le 1 \rightarrow$ `InformationLevel.THOROUGH`
    * $range \le 5 \rightarrow$ `InformationLevel.DETAILED`
    * $range > 5 \rightarrow$ `InformationLevel.BASIC`

---

## Exemplo de Uso

```kotlin
// 1. Definição de Estados e IDs
object ChestClosed : ThingState
object ChestOpen : ThingState

val chestId = ThingId("chest_01")
val keyId = ThingId("key_01")
val playerId = ParticipantId("player_01")

// 2. Instanciação
val chest = BasicThing(id = chestId, currentState = ChestClosed)
val player = BasicParticipant(id = playerId)

// 3. Registro de Conteiner
val registry = ContainmentRegistry().apply {
    add(containerId = chestId, contentId = keyId)
}

// 4. Contexto
val context = object : InteractionContext {
    private val things = mapOf(chestId to chest)
    private val participants = mapOf(playerId to player)

    override fun findThing(id: ThingId) = things[id]
    override fun findParticipant(id: ParticipantId) = participants[id]
    override fun getContainmentRegistry() = registry
}

// 5. Criação e Execução de Interação
val openChestInteraction = BasicInteraction(
    id = InteractionId("interaction_open_chest"),
    capabilityId = CapabilityId("interact"),
    targetId = chestId,
    participantId = playerId,
    effects = setOf(ChangeThingStateEffect(chestId, ChestOpen))
)

when (val result = openChestInteraction.execute(context)) {
    is InteractionResult.Success -> println("Estado atual do baú: ${chest.state}") // ChestOpen
    is InteractionResult.Refused -> println("Interação recusada pelas condições.")
    is InteractionResult.Invalid -> println("Target ou Participant inválidos.")
}