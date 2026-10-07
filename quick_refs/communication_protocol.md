# Referência Rápida da API do Protocolo (CommunicationProtocol)

---

## 1. Tipos de Dados Básicos e Identificadores

### `ProtocolVersion`

Representa a versão do protocolo para verificação de compatibilidade.

* **Assinatura:** `data class ProtocolVersion(val major: Int = 1, val minor: Int = 0)`
* **Métodos:**
* `isCompatibleWith(other: ProtocolVersion): Boolean`: Devolve `true` se a versão `major` for igual.


* **Constantes:** `ProtocolVersion.V1` (`1.0`)

---

### Identificadores Fortemente Tipados (`value class`)

* **`MessageId(val value: String)`**: Identificador único de uma mensagem. Requer texto não vazio.
* **`SessionId(val value: String)`**: Identificador de uma sessão de jogo. Requer texto não vazio.
* **`CorrelationId(val value: String)`**: Relaciona uma mensagem de resposta a um `MessageId` original.
* **Construtor Secundário:** `CorrelationId(messageId: MessageId)`


* **`BlockId(val value: String)`**: Identificador de um bloco de apresentação. Requer texto não vazio.
* **`Sequence(val value: Long)`**: Número sequencial ordenado ($\ge 0$).
* `next(): Sequence`: Incrementa a sequência. Ao atingir `Long.MAX_VALUE`, regressa a `0L`.
* `distanceTo(other: Sequence): Long`: Calcula a distância relativa considerando *wrap-around*.
* **Constantes:** `Sequence.ZERO`


* **`Revision(val value: Long)`**: Versão incremental do estado de apresentação ($\ge 0$).
* `next(): Revision`: Devolve a revisão seguinte (`value + 1`).
* **Constantes:** `Revision.ZERO`

---

## 2. Enums do Sistema

| Enum                   | Valores Possíveis                                                                          |
|------------------------|--------------------------------------------------------------------------------------------|
| **`DeliveryMode`**     | `RELIABLE_ORDERED`, `RELIABLE_UNORDERED`, `UNRELIABLE`                                     |
| **`MessageDirection`** | `CLIENT_TO_SERVER`, `SERVER_TO_CLIENT`                                                     |
| **`SessionState`**     | `NON_EXISTENT`, `ESTABLISHED`, `REJECTED`, `RESUMED`                                       |
| **`RejectionReason`**  | `INCOMPATIBLE_VERSION`, `SESSION_NOT_FOUND`, `SERVER_FULL`, `GENERIC_ERROR`                |
| **`TextStyle`**        | `NORMAL`, `HEADER`, `SUBHEADER`, `HIGHLIGHT`, `MUTED`, `WARNING`, `ERROR`                  |
| **`FeedOrdering`**     | `CHRONOLOGICAL`, `REVERSE_CHRONOLOGICAL`                                                   |
| **`SequenceStatus`**   | `EXPECTED`, `DUPLICATE`, `GAP`, `STALE`, `OUT_OF_ORDER`                                    |
| **`FailureAction`**    | `REJECT`, `IGNORE`, `DISCARD`, `RESPOND_WITH_ERROR`, `REQUEST_RESYNC`, `TERMINATE_SESSION` |

---

## 3. Envelope e Metadados das Mensagens

### `MessageMetadata`

Guarda os metadados comuns a qualquer mensagem do protocolo.

* **Campos:**
* `messageId: MessageId` *(Obrigatório)*
* `sessionId: SessionId` *(Obrigatório)*
* `sequence: Sequence` *(Obrigatório)*
* `correlationId: CorrelationId?` *(Opcional)*
* `version: ProtocolVersion` *(Padrão: `V1`)*
* `deliveryMode: DeliveryMode` *(Padrão: `RELIABLE_ORDERED`)*

### Hierarquia de Interfaces

* **`sealed interface ProtocolMessage`**: Interface base contendo a propriedade `metadata: MessageMetadata`.
* **`sealed interface ClientMessage : ProtocolMessage`**: Mensagens enviadas exclusivamente do cliente para o servidor.
* **`sealed interface ServerMessage : ProtocolMessage`**: Mensagens enviadas exclusivamente do servidor para o cliente.

---

## 4. Catálogo de Mensagens

### Cliente $\rightarrow$ Servidor (`ClientMessage`)

| Mensagem          | Parâmetros                      | Descrição |
|-------------------|---------------------------------|-----------|
| **`ClientHello`** | `metadata: MessageMetadata`<br> |           |

<br>`resumeSessionId: SessionId? = null` | Inicia uma nova sessão ou solicita o restabelecimento de uma sessão
existente. |
| **`ClientAction`** | `metadata: MessageMetadata`<br>

<br>`actionId: String`<br>

<br>`parameters: Map<String, String> = emptyMap()` | Executa uma ação de jogo com parâmetros estruturados. `actionId`
não pode ser vazio. |
| **`ClientSelection`** | `metadata: MessageMetadata`<br>

<br>`choiceId: String` | Submete a escolha de uma opção de menu/diálogo. `choiceId` não pode ser vazio. |
| **`ClientTextCommand`** | `metadata: MessageMetadata`<br>

<br>`rawText: String` | Envia um comando de texto livre. `rawText` não pode ser vazio. |
| **`ClientCancel`** | `metadata: MessageMetadata`<br>

<br>`targetMessageId: MessageId` | Solicita o cancelamento de uma operação em curso identificada por
`targetMessageId`. |
| **`ClientResyncRequest`** | `metadata: MessageMetadata`<br>

<br>`lastKnownSequence: Sequence? = null`<br>

<br>`lastKnownRevision: Revision? = null` | Solicita a ressincronização total de estado ao detetar desfasamento. |

---

### Servidor $\rightarrow$ Cliente (`ServerMessage`)

| Mensagem            | Parâmetros                      | Descrição |
|---------------------|---------------------------------|-----------|
| **`ServerWelcome`** | `metadata: MessageMetadata`<br> |           |

<br>`isResumed: Boolean = false` | Confirma o estabelecimento ou a retomada de uma sessão. |
| **`ServerReject`** | `metadata: MessageMetadata`<br>

<br>`reason: RejectionReason` | Rejeita o pedido de conexão ou retomada de sessão. |
| **`ServerAccepted`** | `metadata: MessageMetadata`<br>

<br>`detail: String? = null` | Notifica que o comando (referenciado pelo `correlationId`) foi aceite para
processamento. |
| **`ServerRejected`** | `metadata: MessageMetadata`<br>

<br>`reason: String` | Rejeita a execução do comando (referenciado pelo `correlationId`). |
| **`ServerCompleted`** | `metadata: MessageMetadata`<br>

<br>`result: Map<String, String> = emptyMap()` | Notifica a conclusão bem-sucedida da operação (referenciada pelo
`correlationId`). |
| **`ServerFailed`** | `metadata: MessageMetadata`<br>

<br>`error: String` | Indica uma falha durante a execução da operação (referenciada pelo `correlationId`). |
| **`ServerProtocolError`** | `metadata: MessageMetadata`<br>

<br>`code: String`<br>

<br>`description: String` | Informa a ocorrência de um erro no cumprimento do protocolo. |
| **`ServerEvent`** | `metadata: MessageMetadata`<br>

<br>`type: String`<br>

<br>`payload: Map<String, String> = emptyMap()` | Notificação assíncrona/espontânea emitida pelo servidor (não requer
`correlationId`). |
| **`PresentationSnapshot`** | `metadata: MessageMetadata`<br>

<br>`document: PresentationDocument` | Envia o estado completo da interface do cliente. |
| **`PresentationPatch`** | `metadata: MessageMetadata`<br>

<br>`baseRevision: Revision`<br>

<br>`newRevision: Revision`<br>

<br>`operations: List<PatchOperation>` | Envia atualizações incrementais para alterar o documento de apresentação. |

---

## 5. Modelo de Apresentação e Operações de Patch

### Estruturas de Conteúdo (`BlockContent`)

* `BlockContent.Text(val text: String, val style: TextStyle = TextStyle.NORMAL)`
* `BlockContent.ItemsList(val items: List<String>, val isOrdered: Boolean = false)`
* `BlockContent.Choices(val choices: List<Choice>)`
* `Choice(val id: String, val text: String, val enabled: Boolean = true)`


*

`BlockContent.Feed(val feedId: String, val items: List<FeedItem>, val ordering: FeedOrdering = FeedOrdering.CHRONOLOGICAL)`

* `FeedItem(val id: String, val text: String, val style: TextStyle = TextStyle.NORMAL)`

### Documento e Blocos

* `PresentationBlock(val id: BlockId, val content: BlockContent)`
* `PresentationDocument(val revision: Revision = Revision.ZERO, val blocks: List<PresentationBlock> = emptyList())`

### Operações de Patch (`PatchOperation`)

* **`PatchOperation.Upsert(val block: PresentationBlock)`**: Atualiza o bloco existente ou insere-o se não existir.
* **`PatchOperation.Remove(val blockId: BlockId)`**: Remove o bloco com o identificador especificado.
* **`PatchOperation.Append(val block: PresentationBlock)`**: Adiciona um novo bloco no final (falha se o ID já existir).
* **`PatchOperation.Clear`**: Limpa todos os blocos do documento.

### Funções de Extensão para Aplicação de Atualizações

* **`PresentationDocument.applySnapshot(snapshot: PresentationSnapshot): PresentationDocument`**
* Devolve o novo `PresentationDocument` contido no snapshot.


* **`PresentationDocument.applyPatch(patch: PresentationPatch): PresentationDocument`**
* Valida se `this.revision == patch.baseRevision`. Se for válido, aplica atomicamente as operações e devolve um novo
  `PresentationDocument` com a revisão `patch.newRevision`. Lança `IllegalArgumentException` em caso de erro.

* **
`PresentationDocument.applyResync(snapshot: PresentationSnapshot, sequenceTracker: SequenceTracker): PresentationDocument`
**

* Ajusta a sequência esperada no `SequenceTracker` para `snapshot.sequence.next()` e devolve o novo documento.

---

## 6. Gestão de Estado e Processadores

### `SessionLifecycleHandler`

* **Assinatura:** `SessionLifecycleHandler(val serverVersion: ProtocolVersion = ProtocolVersion.V1)`
* **Métodos:**
* `processHello(hello: ClientHello, replyMessageId: MessageId): Pair<SessionState, ServerMessage>`
* Processa a mensagem `ClientHello`.
* Devolve um par com o novo `SessionState` e a mensagem de resposta correspondente (`ServerWelcome` ou `ServerReject`).


* `isSessionActive(sessionId: SessionId): Boolean`: Verifica se a sessão está registada como ativa.

### `SequenceTracker`

* **Assinatura:** `SequenceTracker(initialExpectedSequence: Sequence = Sequence.ZERO)`
* **Métodos:**
* `process(message: ProtocolMessage): SequenceStatus`
* `process(messageId: MessageId, sequence: Sequence): SequenceStatus`
* Verifica se a mensagem é válida, duplicada, desordenada ou em desfasamento (*gap*). Atualiza a sequência esperada se
  for o número previsto.


* `isProcessed(messageId: MessageId): Boolean`: Devolve `true` se a mensagem já tiver sido processada.
* `reset(newExpectedSequence: Sequence = Sequence.ZERO)`: Reinicia a contagem de sequências e limpa mensagens
  processadas.

### `SessionSequenceTracker`

Gere rastreadores independentes para ambas as direções da transmissão.

* **Assinatura:**
  `SessionSequenceTracker(val sessionId: SessionId, initialClientSequence: Sequence = Sequence.ZERO, initialServerSequence: Sequence = Sequence.ZERO)`
* **Métodos:**
* `processInbound(message: ClientMessage): SequenceStatus`
* `processInbound(message: ServerMessage): SequenceStatus`
* `reset(clientSequence: Sequence = Sequence.ZERO, serverSequence: Sequence = Sequence.ZERO)`

### `ResyncHandler`

* **Métodos:**
*

`createResyncRequest(sessionId: SessionId, messageId: MessageId, sequence: Sequence, lastKnownSequence: Sequence? = null, lastKnownRevision: Revision? = null): ClientResyncRequest`

*

`createSnapshotResponse(request: ClientResyncRequest, replyMessageId: MessageId, serverSequence: Sequence, currentDocument: PresentationDocument): PresentationSnapshot`

* `applyResync(snapshot: PresentationSnapshot, sequenceTracker: SequenceTracker): PresentationDocument`

---

## 7. Validação, Limites e Segurança

### Configuração de Limites (`ProtocolLimits`)

* `maxTextLength: Int = 4096`
* `maxPatchOperations: Int = 100`
* `maxBlocksPerDocument: Int = 50`
* `maxChoicesPerBlock: Int = 20`
* `maxItemsPerList: Int = 100`
* `maxParametersPerAction: Int = 50`

### Validações

* **`StructuralValidator(limits: ProtocolLimits = ProtocolLimits())`**
* `validate(message: ProtocolMessage): ValidationResult`
* `validateDocument(document: PresentationDocument): ValidationResult`
* `validatePatch(patch: PresentationPatch): ValidationResult`


* **`ProtocolValidator()`**
* `validateDirection(expectedDirection: MessageDirection, message: ProtocolMessage): ValidationResult`
* `validateSessionState(sessionState: SessionState, message: ProtocolMessage): ValidationResult`
* `validateCorrelation(message: ProtocolMessage, activePendingRequestIds: Set<MessageId>? = null): ValidationResult`
* `validateRevision(currentDocRevision: Revision, patchBaseRevision: Revision): ValidationResult`
* `validateSequence(tracker: SequenceTracker, message: ProtocolMessage): ValidationResult`

### `FailureEvaluator`

* `evaluateValidationFailure(result: ValidationResult.Invalid): FailureAction`
* `evaluateSequenceStatus(status: SequenceStatus): FailureAction`
* `evaluateSessionResult(sessionState: SessionState): FailureAction`

### `RequestRateLimiter`

* `allowRequest(sessionId: SessionId): Boolean`: Verifica se o cliente excedeu `maxRequestsPerWindow` (padrão: 100).
* `reset(sessionId: SessionId)`
* `evaluateViolation(excessiveViolationsCount: Int): FailureAction`

---

## 8. Serialização JSON

O protocolo utiliza uma instância centralizada do `kotlinx.serialization.json.Json` designada por `ProtocolJson`.

```kotlin
// Serializar uma mensagem para JSON String
val jsonString: String = message.toJson()

// Desserializar JSON String para um tipo concreto de ProtocolMessage
val clientAction: ClientAction = jsonString.toProtocolMessage<ClientAction>()
val genericMessage: ProtocolMessage = jsonString.toProtocolMessage<ProtocolMessage>()

```

---

## 9. Exemplo Prático de Utilização

### Lado do Cliente: Enviar Comando e Processar Resposta

```kotlin
val sessionId = SessionId("sess-001")
val clientTracker = SequenceTracker(Sequence.ZERO)

// 1. Criar e enviar uma ação do cliente
val actionMessage = ClientAction(
  metadata = MessageMetadata(
    messageId = MessageId("msg-101"),
    sessionId = sessionId,
    sequence = clientTracker.expectedSequence
  ),
  actionId = "ATTACK",
  parameters = mapOf("target" to "goblin_01")
)

val rawJsonToSend = actionMessage.toJson()

// 2. Receber e aplicar um patch enviado pelo servidor
val incomingJson = """{
  "type": "PresentationPatch",
  "metadata": {
    "messageId": "msg-501",
    "sessionId": "sess-001",
    "sequence": 0,
    "correlationId": "msg-101"
  },
  "baseRevision": 0,
  "newRevision": 1,
  "operations": [
    {
      "type": "Upsert",
      "block": {
        "id": "combat-log",
        "content": {
          "type": "Text",
          "text": "Atacaste o goblin causando 15 de dano!",
          "style": "HIGHLIGHT"
        }
      }
    }
  ]
}"""

val receivedMessage = incomingJson.toProtocolMessage<ServerMessage>()

if (receivedMessage is PresentationPatch) {
  val currentDoc = PresentationDocument(revision = Revision.ZERO)
  val updatedDoc = currentDoc.applyPatch(receivedMessage)
  // updatedDoc.revision passa a ser Revision(1) com o novo bloco em updatedDoc.blocks
}

```

### Lado do Servidor: Aceitar Sessão e Validar Mensagens

```kotlin
val lifecycleHandler = SessionLifecycleHandler()
val validator = StructuralValidator()

// Receber ClientHello do cliente
val helloJson = """{
  "type": "ClientHello",
  "metadata": {
    "messageId": "msg-000",
    "sessionId": "sess-001",
    "sequence": 0
  }
}"""

val helloMessage = helloJson.toProtocolMessage<ClientHello>()

// Validar estrutura
val validationResult = validator.validate(helloMessage)

if (validationResult.isValid) {
  val (newState, responseMessage) = lifecycleHandler.processHello(
    hello = helloMessage,
    replyMessageId = MessageId("srv-msg-000")
  )

  // Responder ao cliente
  val responseJson = responseMessage.toJson()
}
```