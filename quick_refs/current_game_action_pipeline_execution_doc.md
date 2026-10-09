# Documentação da pipeline do jogo — estado atual

## 1. Objetivo e arquitetura

O jogo é um RPG multiplayer persistente, baseado principalmente em texto. A lógica do jogo deve funcionar
independentemente da interface, do protocolo de comunicação e do transporte de rede.

A regra central continua sendo: o jogo fornece significado; as bibliotecas fornecem estrutura.

A pipeline atual conecta o cliente ao domínio do jogo e devolve resultados e eventos aos jogadores.

Cliente

Envia uma ação solicitada pelo jogador

CommunicationProtocol

Define mensagens, IDs, sequência e validação

Server

Valida a sessão, identifica o personagem e adapta a mensagem

GameActionProcessor

Executa a ação e produz resultado e fatos

Nucleus + FL-World

Mantém e atualiza o estado do mundo

Server → Clientes interessados

Resposta direta ao solicitante e eventos aos demais jogadores

O servidor é responsável por adaptar mensagens e distribuir eventos. O domínio é responsável pelas regras e pelas
alterações do estado do jogo.

## 2. Estruturas principais do domínio

Todo o código de produção de `:game-domain` permanece em um único arquivo durante a fase atual de prototipagem.

### `CharacterId` e `NucleusId`

São identificadores tipados, definidos como `value class`:

* `CharacterId(val value: String)`: identifica um personagem.

* `NucleusId(val value: String)`: identifica um núcleo.

Apesar de ambos conterem `String`, são tipos distintos, evitando confusões entre identificadores.

### `Character`

Representa os dados básicos de um personagem:

* `id: CharacterId`

* `name: String`

A classe existe, mas ainda não está integrada ao cadastro de personagens de `Nucleus`. Atualmente, o núcleo registra os
IDs dos personagens, não os objetos `Character` completos.

### `Nucleus`

Representa um núcleo do universo do jogo. Cada núcleo possui seu próprio `World` da biblioteca FL-World.

Principais propriedades e operações:

| Operação                  | Entrada                                  | Saída               |
|---------------------------|------------------------------------------|---------------------|
| `registerCharacter`       | `CharacterId`                            | `Unit`              |
| `unregisterCharacter`     | `CharacterId`                            | `Unit`              |
| `containsCharacter`       | `CharacterId`                            | `Boolean`           |
| `getCharactersAtLocation` | `WorldLocationId`                        | `List<CharacterId>` |
| `registerLocation`        | `String`                                 | `Unit`              |
| `connectLocations`        | origem, destino e ID opcional da conexão | `Unit`              |

O conjunto `characters` expõe uma cópia dos IDs registrados, não o conjunto mutável interno.

A posição física de cada personagem continua sendo responsabilidade do `WorldState`, não do próprio personagem.

### `GameAction`

Representa uma intenção enviada para ser processada pelo domínio.

* `name: String`: identificador textual da ação, como `"enter_world"` ou `"move"`.

* `actorId: CharacterId`: personagem que executa a ação.

* `arguments: Map<String, Any?>`: argumentos específicos da ação.

Exemplo conceitual: uma ação `"move"` pode carregar `targetLocationId` com o ID do destino.

Não existe uma classe específica para cada ação. O modelo é genérico de propósito, permitindo adicionar ações sem criar
obrigatoriamente novas classes, enums ou hierarquias.

### `GameFact`

Representa um fato produzido pelo processamento do jogo.

* `name: String`: nome do fato.

* `arguments: Map<String, Any?>`: dados associados.

Exemplos atuais:

* `character_entered`

* `character_moved`

* `character_movement_completed`

* `character_movement_interrupted`

Importante: `character_moved` significa que o deslocamento foi iniciado. A chegada é representada por
`character_movement_completed`.

### `GameActionResult`

Representa o resultado de uma ação. É uma interface selada com duas possibilidades:

* `Success(facts: List<GameFact>)`: ação aceita e fatos produzidos.

* `Rejected(reason: String, facts: List<GameFact>)`: ação recusada, com motivo textual.

O servidor converte o resultado em uma mensagem do protocolo, mas o domínio não conhece `ServerCompleted` ou
`ServerRejected`.

### `GameFactAudience` e `GameActionExecution`

São estruturas utilizadas para preservar a consistência entre a mudança do mundo e a identificação dos destinatários dos
eventos.

`GameFactAudience` contém:

* `fact: GameFact`

* `characterIds: List<CharacterId>`

`GameActionExecution` contém:

* `result: GameActionResult`

* `factAudiences: List<GameFactAudience>`

Assim, a ação e a identificação dos personagens interessados são realizadas dentro da mesma seção sincronizada.

## 3. O processador do jogo

`GameActionProcessor` é a fronteira atual para executar ações e avançar o tempo de um núcleo.

Seu construtor recebe:

- `nucleus: Nucleus`

Principais métodos:

| Método                            | Entrada          | Saída                    |
|-----------------------------------|------------------|--------------------------|
| `process(action)`                 | `GameAction`     | `GameActionResult`       |
| `processWithAudiences(action)`    | `GameAction`     | `GameActionExecution`    |
| `advanceTime(ticks)`              | `Long`           | `List<GameFact>`         |
| `advanceTimeWithAudiences(ticks)` | `Long`           | `List<GameFactAudience>` |
| `findInterestedCharacters(fact)`  | `GameFact`       | `List<CharacterId>`      |
| `findFactAudiences(facts)`        | `List<GameFact>` | `List<GameFactAudience>` |

A diferença mais importante é:

* `process()` retorna somente o resultado da ação.

* `processWithAudiences()` também calcula os destinatários dos fatos, permitindo que o servidor distribua os eventos sem
  precisar consultar novamente o estado mutável do mundo.

O mesmo princípio se aplica ao avanço do tempo.

### Concorrência

As operações do processador utilizam o `stateLock` do `Nucleus`. Isso serializa ações e avanços temporais que passam por
essa fronteira, inclusive quando diferentes instâncias de `GameActionProcessor` compartilham o mesmo núcleo.

A sincronização protege as operações previstas na API do domínio. Alterações diretas em `nucleus.world` por outros
caminhos não ficam automaticamente protegidas; por isso, o código externo não deve modificar o mundo em paralelo com o
processador.

## 4. Como uma ação percorre a pipeline

Considere o jogador solicitando a ação `"move"`.

1. Recepção

   O servidor recebe uma `ClientAction` pela conexão WebSocket e valida sua estrutura, sessão e sequência.

2. Identificação do personagem

   O servidor consulta `ConnectionRegistry` para obter o `CharacterId` associado à sessão.

3. Adaptação

   O servidor transforma a mensagem em `GameAction`, usando `actionId` como `name` e `parameters` como `arguments`.

4. Execução

   O `GameActionProcessor` valida a ação, consulta o núcleo e tenta iniciar o movimento por meio de FL-World.

5. Resposta direta

   O resultado é convertido em `ServerCompleted` ou `ServerRejected`, correlacionado à mensagem que originou a ação.

6. Distribuição de fatos

   Os fatos bem-sucedidos são distribuídos aos personagens interessados, excluindo o próprio ator quando se trata dos
   fatos daquela ação.

A exclusão do ator evita que ele receba um evento duplicado quando já recebeu uma resposta direta. Isso não impede que
ele receba eventos posteriores, como a conclusão do movimento.

## 5. Como os interessados são determinados

Atualmente, o interesse é local e baseado na localização do mundo.

O domínio calcula quais personagens estão na localização indicada pelo fato e retorna uma lista de `CharacterId`. O
servidor usa o `ConnectionRegistry` para encontrar a conexão de cada personagem e enviar um `ServerEvent`.

O fluxo de responsabilidades é:

* Domínio: determina os personagens interessados.

* Servidor: resolve os personagens para conexões e envia mensagens.

* Protocolo: define a estrutura da mensagem enviada.

Ainda não existe um sistema genérico de gerenciamento de interesse. Não é necessário criar um `BroadcastManager`,
`EventBus` ou sistema de assinaturas para sustentar essa funcionalidade atual.

Uma limitação do modelo presente é que os destinatários são calculados a partir da localização atual indicada no fato.
Isso é suficiente para a primeira versão, mas fatos sobre deslocamentos entre locais podem exigir regras adicionais
quando o jogo evoluir.

## 6. Avanço temporal

A função de servidor `advanceTimeAndDistribute(ticks, registry, actionProcessor)` é uma função de orquestração
provisória.

Ela:

1. Solicita ao processador que avance o mundo.

2. Obtém os fatos e os destinatários calculados durante essa operação.

3. Distribui os eventos pelo servidor.

4. Retorna `List<GameFact>`.

O valor `ticks: Long` é convertido em `WorldDuration` pelo processador. Atualmente, a duração do movimento é estimada a
partir da quantidade de conexões do caminho encontrado.

Essa função ainda precisa ser integrada a um mecanismo de progressão temporal do servidor. Sua existência não significa
que o tempo avance automaticamente em produção.

## 7. Como adicionar uma nova funcionalidade

A regra para futuras implementações é estender a pipeline sem misturar responsabilidades.

1. Defina o significado da nova ação no domínio.

2. Identifique quais argumentos ela exige e quais validações devem ocorrer.

3. Implemente seu processamento em `GameActionProcessor`, retornando `GameActionResult` e os fatos pertinentes.

4. Se a ação alterar o mundo, utilize FL-World para representar essa mudança.

5. Se outros personagens precisarem ser informados, defina os fatos e os critérios de interesse.

6. Adapte a resposta e a distribuição no servidor somente se o comportamento existente não for suficiente.

7. Acrescente testes de domínio e, quando a funcionalidade atravessar a comunicação, testes de integração.

Não é necessário criar uma classe específica para cada ação nem uma abstração genérica para cada nova funcionalidade.

Regra absoluta: se a implementação exigir uma API de alguma biblioteca cuja assinatura ou comportamento não esteja
documentado no contexto disponível, solicite o código ou a documentação correspondente antes de implementar. Não invente
métodos, parâmetros ou contratos.

## 8. Testes e CI

O teste `PipelineIntegrationTest` verifica o fluxo entre sessões, ações, resultados e eventos. O teste de concorrência
verifica a execução simultânea de ações e avanços temporais.

O GitHub Actions está configurado para executar `./gradlew build` a cada push e pull request. Isso compila os módulos e
executa os testes associados ao build.

Se futuramente quiser executar apenas os testes, o Gradle normalmente permite usar `./gradlew test`, mas isso depende de
como as tarefas estão configuradas em cada módulo. O CI atual ainda não foi alterado para esse modo.

## Regras que devem acompanhar esta documentação

* Manter um arquivo principal de produção por módulo durante a prototipagem.

* Não antecipar sistemas futuros sem necessidade concreta.

* Não introduzir dependências de protocolo, servidor, cliente ou Ktor em `game-domain`.

* Manter a separação entre intenção (`GameAction`), resultado (`GameActionResult`), fato (`GameFact`) e mensagem de
  comunicação.

* Não mover a posição física do personagem para dentro de `Character`.

* Manter as alterações de estado sincronizadas pela fronteira do núcleo.

* Nunca manter o bloqueio do domínio durante o envio de mensagens de rede.

* Preservar APIs públicas existentes, salvo quando houver uma necessidade justificada de alteração.

* Implementar cada nova funcionalidade incrementalmente e acompanhá-la de testes.

* Não inventar APIs ou comportamentos de bibliotecas existentes: pedir a fonte quando houver dúvida.