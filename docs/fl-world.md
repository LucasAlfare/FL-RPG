# FL-World — Referência Rápida da API

**Package:** `com.lucasalfare.flworld`

## 1. Identificadores

### `WorldId`

`value: String`

Identidade de um `World`.

### `WorldEntityId`

`value: String`

Identidade de uma entidade.

### `WorldLocationId`

`value: String`

Identidade de um nó do grafo espacial.

### `WorldConnectionId`

`value: String`

Identidade de uma conexão dirigida.

### `WorldGroupId`

`value: String`

Identidade de um grupo de localizações.

### `WorldScheduledEventId`

`value: String`

Identidade de um evento agendado.

### `WorldRecurrenceId`

`value: String`

Identidade de uma recorrência.

---

# 2. Entidades e espaço

### `WorldEntity`

* `id: WorldEntityId`
* `isEventPropagationEnabled: Boolean = false`

Entidade genérica do mundo. O core não diferencia player, NPC, item, veículo etc.

### `WorldLocation`

* `id: WorldLocationId`

Nó do grafo. Não possui coordenadas ou geometria.

### `WorldConnection`

* `id: WorldConnectionId`
* `from: WorldLocationId`
* `to: WorldLocationId`

Conexão dirigida. `A → B` não implica `B → A`. IDs diferentes permitem múltiplas conexões entre os mesmos nós.

---

# 3. Caminhos

### `WorldPath`

* `origin`
* `destination`
* `connections: List<WorldConnection> = emptyList()`
* `locations: List<WorldLocationId>` — propriedade calculada

Regras:

* sem conexões: `origin == destination`;
* com conexões: elas precisam formar uma cadeia contínua;
* primeira começa em `origin`;
* última termina em `destination`.

`locations` retorna `origin` seguido dos destinos de cada conexão.

---

# 4. Estado espacial

### `WorldGraph`

Mantém a estrutura permanente:

* localizações;
* conexões dirigidas.

Principais operações:

* `registerLocation(location)`
* `removeLocation(id)`
* `registerConnection(connection)`
* `removeConnection(id)`
* `getLocation(id): WorldLocation?`
* `getConnection(id): WorldConnection?`
* `getOutgoingConnections(locationId): List<WorldConnection>`
* `getNeighbors(locationId): List<WorldLocationId>`
* `isReachable(...)`
* `findPath(...)`
* `snapshot(): WorldGraphSnapshot`
* `restore(snapshot)`

Conexões de saída são retornadas ordenadas por `id`.

Ao remover uma localização, suas conexões incidentes também são removidas.

### `WorldGraphSnapshot`

* `locations: List<WorldLocation>`
* `connections: List<WorldConnection>`

Snapshot imutável do grafo.

---

### `WorldState`

Mantém somente a localização atual das entidades:

`WorldEntityId → WorldLocationId`

Uma entidade pode existir sem localização.

Principais operações:

* `setLocation(entityId, locationId)`
* `getLocation(entityId): WorldLocationId?`
* `removeLocation(entityId)`
* `snapshot(): Map<WorldEntityId, WorldLocationId>`
* `restore(snapshot)`

Uma entidade em movimento permanece, no estado estável, na localização de origem.

---

# 5. Grupos

### `WorldGroup`

Grupo lógico de localizações, sem ser parte do grafo.

Principais operações:

* `addLocation(locationId)`
* `removeLocation(locationId)`
* `hasLocation(locationId): Boolean`
* `getLocations(): Set<WorldLocationId>`
* `snapshot(): WorldGroupSnapshot`
* `restore(snapshot)`

Uma localização pode pertencer a vários grupos.

### `WorldGroupSnapshot`

* `id`
* `locations: Set<WorldLocationId>`

---

# 6. Navegação

### `WorldAccessContext`

Contexto recebido pelo avaliador:

* `entityId: WorldEntityId?`
* `locationId: WorldLocationId?`
* `state: WorldState?`
* `payload: Any?`

O core não interpreta `payload`.

### `WorldAccessEvaluator`

`canTraverse(connection, context): Boolean`

Decide se uma conexão pode ser atravessada.

Ex.: chave, nível, facção, quest etc.

---

### `WorldPathFinder`

Interface para encontrar caminho:

`findPath(graph, from, to, accessEvaluator?, accessContext): WorldPath?`

Retorna `null` se não houver caminho.

### `UnweightedPathFinder`

BFS. Encontra caminho com menor número de conexões.

### `WorldNavigationCost`

`getCost(connection, context): Double`

Define o custo de atravessar uma conexão.

O valor precisa ser `>= 0` e não pode ser `NaN`.

### `DijkstraPathFinder`

Busca caminho de menor custo usando `WorldNavigationCost`.

### `WorldHeuristic`

`estimate(from, to): Double`

Heurística não negativa e admissível para A*.

### `AStarPathFinder`

Usa:

* `WorldNavigationCost`
* `WorldHeuristic`

para encontrar caminho de menor custo.

---

# 7. Tempo

### `WorldDuration`

`value: Long`

Duração de simulação.

Não valida positividade por si só; as APIs que exigem duração positiva fazem essa validação.

É `Comparable`.

### `WorldInstant`

`value: Long`

Instante lógico da simulação.

É `Comparable` e suporta:

* `instant + duration`
* `instant - duration`
* `instant - otherInstant`

### `CalendarDate`

* `year`
* `month`
* `day`
* `hour`
* `minute`
* `second`

Representação de data interpretada por `WorldCalendar`.

### `WorldCalendar`

`toDate(instant): CalendarDate`

Converte instante lógico em data.

A biblioteca não impõe calendário terrestre.

---

# 8. Relógio

### `WorldClock`

Mantém:

`currentInstant: WorldInstant`

Principais operações:

* `advance(duration)`
* `restore(instant)`

`advance`:

* não permite duração negativa;
* não volta no tempo;
* duração zero não faz nada;
* emite `ClockAdvanced`.

Não usa o relógio do sistema.

---

# 9. Agendamento

### `WorldRecurrence`

* `id`
* `interval: WorldDuration`

`interval > 0`.

Define uma recorrência temporal.

### `WorldScheduledEvent`

* `id`
* `instant`
* `type`
* `payload`
* `recurrenceId`

Evento futuro.

Ordenação: primeiro `instant`, depois `id`.

### `WorldSchedulerSnapshot`

* `events`
* `recurrences`

---

### `WorldScheduler`

Principais operações:

* `schedule(event)`
* `cancel(eventId)`
* `defineRecurrence(recurrence)`
* `cancelRecurrence(recurrenceId)`
* `getRecurrence(recurrenceId): WorldRecurrence?`
* `getFutureEvents(afterInstant)`
* `processEventsUpTo(currentInstant)`
* `snapshot()`
* `restore(snapshot)`

Regras importantes:

* eventos no passado não podem ser agendados;
* IDs de eventos são únicos;
* IDs de recorrências são únicos;
* uma recorrência precisa existir antes de ser referenciada;
* processar um evento recorrente cria automaticamente a próxima ocorrência;
* cancelar uma recorrência **não cancela** eventos já agendados que a referenciem.

---

# 10. Movimentação

### `WorldMovementState`

* `IN_PROGRESS`
* `COMPLETED`
* `INTERRUPTED`

### `WorldDurationEstimator`

`estimateDuration(path, cost, profile?): WorldDuration`

Calcula a duração de uma movimentação. `profile` é arbitrário e pertence à aplicação.

---

### `WorldMovement`

Propriedades:

* `entityId`
* `origin`
* `destination`
* `path`
* `startInstant`
* `duration`
* `progress: Double = 0.0`
* `state: WorldMovementState = IN_PROGRESS`

Propriedades calculadas:

* `completionInstant`
* `isInProgress`
* `isCompleted`
* `isInterrupted`

Métodos:

* `progressAt(currentInstant): Double`
* `updateAt(currentInstant): WorldMovement`

`progress` fica entre `0.0` e `1.0`.

Enquanto está em andamento, a localização estável permanece em `origin`.

---

# 11. Núcleo: `World`

### `World`

Construtor:

`World(id: WorldId, calendar: WorldCalendar? = null)`

Principais propriedades públicas:

* `id`
* `calendar`
* `graph: WorldGraph`
* `state: WorldState`
* `clock: WorldClock`
* `scheduler: WorldScheduler`

---

## Entidades

* `addObserver(observer)`

* `removeObserver(observer)`

* `removeObservers(predicate)`

* `clearObservers()`

* `registerEntity(entity)`

* `getEntity(id): WorldEntity?`

* `hasEntity(id): Boolean`

* `removeEntity(id)`

`registerEntity` rejeita ID duplicado.

`removeEntity`:

* interrompe movimento ativo;
* remove localização;
* remove a entidade.

---

## Eventos originados por entidades

* `enableEntityEventPropagation(entityId)`
* `disableEntityEventPropagation(entityId)`
* `isEntityEventPropagationEnabled(entityId): Boolean`
* `publishEntityEvent(entityId, type, data?)`

Eventos de domínio só são propagados quando:

* a entidade existe;
* propagation está habilitada;
* o mundo não está sendo restaurado;
* existem observers.

---

## Grupos

* `registerGroup(group)`
* `getGroup(id): WorldGroup?`
* `removeGroup(id)`

Todas as localizações do grupo precisam existir no grafo.

Remover um grupo não remove localizações.

---

## Movimentação

* `startMovement(movement)`
* `getMovement(entityId): WorldMovement?`
* `stopMovement(entityId)`
* `getActiveMovements(): List<WorldMovement>`

`startMovement` valida:

* entidade existente;
* ausência de outro movimento;
* estado `IN_PROGRESS`;
* duração positiva;
* início não posterior ao instante atual;
* conclusão futura;
* origem/destino existentes;
* path compatível com origem/destino;
* conexões do path existentes e idênticas às do grafo;
* localização estável atual nula ou igual à origem.

`stopMovement` transforma o movimento em `INTERRUPTED`.

A entidade não é teleportada para o destino.

`getActiveMovements` retorna ordenado por `entityId`.

---

# 12. Avanço da simulação

### `advance(duration): List<WorldScheduledEvent>`

Avança o mundo por uma duração não negativa.

Processa, em ordem determinística:

* eventos agendados;
* progresso/conclusão de movimentos;
* demais transições temporais suportadas pelo mundo.

Duração zero retorna lista vazia.

A implementação avança o relógio diretamente para cada instante relevante intermediário, em vez de iterar unidade por
unidade.

---

### `step(): WorldStepResult`

Executa exatamente um passo discreto.

Procura o próximo instante relevante entre:

* evento agendado;
* conclusão de movimento.

Se existir algo já devido no instante atual, processa imediatamente sem avançar o relógio.

Se não houver nenhuma transição futura:

* `advanced = false`;
* instante permanece igual;
* nenhuma transição é processada.

### `WorldStepResult`

* `advanced: Boolean`
* `previousInstant`
* `currentInstant`
* `processedEvents: List<WorldScheduledEvent>`
* `completedMovements: List<WorldMovement>`

---

# 13. Observação

### `WorldEvent`

Evento já ocorrido.

Propriedades:

* `instant`
* `type: String`
* `data: Any?`
* `sourceId: WorldEntityId?`
* `snapshot: WorldSnapshot`

`WorldEvent` contém um snapshot imutável do mundo **logo após a transição** que gerou o evento.

### `WorldObserver`

`onEvent(event)`

Recebe todos os eventos publicados pelo mundo.

### Registro

`addObserver` ignora duplicatas.

A restauração silencia eventos intermediários e publica somente `WorldRestored` ao final.

---

# 14. Snapshots

### `WorldSnapshot`

Snapshot completo do estado estrutural do mundo:

* `id`
* `entities`
* `entityLocations`
* `graph`
* `groups`
* `calendar`
* `currentInstant`
* `scheduler`
* `activeMovements`

Não contém dados específicos da aplicação, como:

* atributos;
* inventário;
* quests;
* progressão;
* estado de gameplay.

Esses dados são responsabilidade do consumidor.

### `world.snapshot()`

Produz snapshot independente do estado mutável atual.

### `World.restore(...)`

Reconstrói um `World` funcional a partir do snapshot.

Parâmetros:

* `snapshot`
* `calendar` opcional; padrão = calendário do snapshot
* `observers` opcionais

Durante a restauração os eventos são suprimidos.

Após a reconstrução é publicado:

`WorldRestored`

---

# 15. Eventos internos do mundo

Os principais `type` usados pela implementação são:

* `EntityRegistered`
* `EntityRemoved`
* `EntityEventPropagationChanged`
* `GroupRegistered`
* `GroupRemoved`
* `GroupLocationAdded`
* `GroupLocationRemoved`
* `LocationRegistered`
* `LocationRemoved`
* `ConnectionRegistered`
* `ConnectionRemoved`
* `LocationChanged`
* `MovementStarted`
* `MovementProgressed`
* `MovementCompleted`
* `MovementInterrupted`
* `ClockAdvanced`
* `EventScheduled`
* `EventCanceled`
* `EventProcessed`
* `RecurrenceDefined`
* `RecurrenceCanceled`
* `WorldRestored`

Os valores de `type` são strings; a aplicação pode também publicar seus próprios tipos com `publishEntityEvent`.

---

# 16. Separações importantes

### `WorldGraph`

Estrutura espacial permanente.

### `WorldState`

Posição atual das entidades.

### `WorldClock`

Tempo atual.

### `WorldScheduler`

Eventos futuros e recorrências.

### `World`

Coordena todas essas partes.

### Dados de domínio

Ficam fora do core.

---

# 17. APIs internas / não destinadas ao consumidor

Estas partes existem para integração interna entre os componentes e não fazem parte da superfície pública de uso normal:

* `World.eventPublisher` etc.
* `World.isRestoring`
* `WorldScheduler.hasEventsAtOrBefore(...)`
* `WorldGraph` callbacks internos
* `WorldState` validators internos
* `WorldGroup` callbacks internos
* `QueueEntry`
* `reconstructPath(...)`
* `World.AdvanceResult`

A API pública de uso da biblioteca é composta essencialmente pelas abstrações acima e pelos métodos públicos de `World`,
`WorldGraph`, `WorldState`, `WorldScheduler`, `WorldGroup`, pathfinders e value objects.
