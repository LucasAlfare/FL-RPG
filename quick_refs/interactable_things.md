### 1. Value Classes (Identificadores e Prioridade)

Tipos imutáveis com validações integradas no bloco `init`:

* **`ThingId(value: String)`**

* **Descrição:** ID para instâncias de `Thing`.


* **Validação:** Lança erro se `value` for vazio/blank.


* **`CapabilityId(value: String)`**

* **Descrição:** ID para capacidades (`Capability`).


* **`ParticipantId(value: String)`**

* **Descrição:** ID para participantes (`Participant`).


* **`InteractionId(value: String)`**

* **Descrição:** ID para interações (`Interaction`).


* **`InformationId(value: String)`**

* **Descrição:** ID para informações (`ObservableInformation`).


* **`PresentationPriority(value: Int = 0)`**

* **Descrição:** Prioridade de exibição com comparação natural (`Comparable`).

---

### 2. Entidades Principais (Things & Participants)

#### Interfaces & Classes:

* **`Thing`**: Representa um objeto interativo no sistema.


* **Propriedades:** `id: ThingId`, `state: ThingState`, `capabilities: Set<Capability>`.


* **Métodos:** `updateState(newState: ThingState)`.


* **`BasicThing(id, currentState, capabilities)`**: Implementação padrão de `Thing`.


* **`PrioritizedThing`**: Interface que estende `Thing` adicionando `presentationPriority: PresentationPriority`.


* **`BasicPrioritizedThing(id, currentState, capabilities, presentationPriority)`**: Implementação padrão de
  `PrioritizedThing`.


* **`Participant`**: Entidade que executa interações.


* **Propriedades:** `id: ParticipantId`.


* **`BasicParticipant(id)`**: Implementação padrão de `Participant`.

---

### 3. Contexto e Resoluções

* **`InteractionContext`** *(Interface)*:


* `findThing(id: ThingId): Thing?`: Busca um objeto pelo ID.


* `findParticipant(id: ParticipantId): Participant?`: Busca um participante pelo ID.


* `getContainmentRegistry(): ContainmentRegistry?`: Retorna o registro de conteiners (padrão: `null`).

---

### 4. Sistema de Interações (Conditions, Effects & Interactions)

#### **`InteractionCondition`** *(Fun Interface)*

:

* `isSatisfied(interaction: Interaction, context: InteractionContext): Boolean`.


* **Agregadores:**
* `AllInteractionConditions(conditions: List<InteractionCondition>)`: Retorna `true` se **todas** as condições passarem.


* `AnyInteractionConditions(conditions: List<InteractionCondition>)`: Retorna `true` se **pelo menos uma** passar.

#### **`InteractionEffect`** *(Fun Interface)*

:

* `apply(interaction: Interaction, context: InteractionContext)`.


* **Implementações:**
* `CompositeInteractionEffect(effects: List<InteractionEffect>)`: Aplica múltiplos efeitos em sequência.


* `ChangeThingStateEffect(targetId: ThingId, newState: ThingState)`: Altera o estado do `Thing` especificado no
  contexto.

#### **`Interaction`** *(Interface / Execução)*

:

* **Propriedades:** `id`, `capabilityId`, `targetId`, `participantId`, `conditions`, `effects`.


* **Métodos Principais:**
* `isAvailable(context): Boolean`: Checa se todas as `conditions` são válidas.


* `execute(context): InteractionResult`: Executa a interação e retorna o resultado.


* **Retornos possíveis (`InteractionResult`):**

* `Success(interactionId, modifiedThingIds)`

* `Refused(interactionId)` (condições indisponíveis)


* `Invalid(interactionId)` (`Thing` ou `Participant` inexistente no contexto)

---

### 5. Registro de Hierarquia e Conteiners (`ContainmentRegistry`)

Gerencia o aninhamento e acoplamento entre elementos (`Thing`).

* `add(containerId: ThingId, contentId: ThingId): Boolean`: Associa um filho a um pai (previne autorreferência e loops
  recursivos).


* `remove(contentId: ThingId): Boolean`: Remove a associação de um filho com seu pai.


* `move(contentId: ThingId, newContainerId: ThingId): Boolean`: Move o item para outro pai.


* `findContainerOf(contentId: ThingId): ThingId?`: Busca o ID do pai/conteiner.


* `findContentsOf(containerId: ThingId): Set<ThingId>`: Lista os IDs dos itens contidos.

#### Utilitário de Construção Árvore:

* **`CompositeThingInitializer(thing, initialContents)`**:


* `build(registry: ContainmentRegistry): Thing`: Registra iterativamente toda a árvore de conteúdos no
  `ContainmentRegistry`.

---

### 6. Sistema de Visibilidade e Observabilidade

* **Exposição (`ExposureRule`)**:


* `evaluateExposure(contentId, context): ExposureStatus` (`EXPOSED` ou `UNEXPOSED`).


* `ContainerStateExposureRule`: Avalia se o item está visível dependendo de uma função predicato no estado do
  conteiner (`isContainerExposing(state)`).


* **Informações Observáveis**:


* `InformationLevel`: Níveis `BASIC`, `DETAILED`, `THOROUGH`.


* `InformationHolder`: Interface para classes que possuem informações, permitindo filtrar por `InformationLevel` via
  `getInformationForLevel()`.


* **Avaliação (`ObservabilityEvaluator`)**:


* `BasicObservabilityEvaluator`: Avalia o `targetId` baseado em regras de exposição e na distância (`range`) presente em
  `ObservationContext`:


* `range <= 1` $\rightarrow$ `InformationLevel.THOROUGH`

* `range <= 5` $\rightarrow$ `InformationLevel.DETAILED`

* `range > 5` $\rightarrow$ `InformationLevel.BASIC`

---

### Exemplo Rápido de Uso

```kotlin
// 1. Definição de Estados e IDs Simples
object ChestClosed : ThingState
object ChestOpen : ThingState

val chestId = ThingId("chest_01")
val keyId = ThingId("key_01")
val playerId = ParticipantId("player_01")

// 2. Instanciando Elementos
val chest = BasicThing(id = chestId, currentState = ChestClosed)
val player = BasicParticipant(id = playerId)

// 3. Montando Registro de Conteiner
val registry = ContainmentRegistry()
registry.add(containerId = chestId, contentId = keyId)

// 4. Implementando Contexto
val context = object : InteractionContext {
  private val things = mapOf(chestId to chest)
  private val participants = mapOf(playerId to player)

  override fun findThing(id: ThingId) = things[id]
  override fun findParticipant(id: ParticipantId) = participants[id]
  override fun getContainmentRegistry() = registry
}

// 5. Criando e Executando uma Interação (Abrir Baú)
val openChestInteraction = BasicInteraction(
  id = InteractionId("interaction_open_chest"),
  capabilityId = CapabilityId("interact"),
  targetId = chestId,
  participantId = playerId,
  effects = setOf(ChangeThingStateEffect(chestId, ChestOpen))
)

val result = openChestInteraction.execute(context)

when (result) {
  is InteractionResult.Success -> println("Estado atual do baú: ${chest.state}") // ChestOpen
  is InteractionResult.Refused -> println("Interação recusada pelas condições.")
  is InteractionResult.Invalid -> println("Target ou Participant inválidos.")
}
```