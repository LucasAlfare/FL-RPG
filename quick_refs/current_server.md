# FL-RPG Server — Referência Rápida da API

**Package:** `com.lucasalfare.flrpg.server`

Servidor responsável pela infraestrutura de comunicação do jogo, utilizando Ktor como transporte e o
`CommunicationProtocol` como contrato de mensagens.

O servidor atualmente fornece:

* servidor HTTP funcional;
* transporte WebSocket;
* múltiplas conexões simultâneas;
* abstração independente de transporte para conexões;
* registro e consulta de conexões ativas;
* estabelecimento de sessões através de `ClientHello`;
* validação estrutural e de estado das mensagens;
* rastreamento de sequência por sessão;
* comunicação bidirecional através de `ClientMessage` e `ServerMessage`;
* encerramento seguro de conexões;
* isolamento entre conexões e sessões.

O servidor ainda **não implementa regras do RPG**.

---

## 1. Connection

Abstração de uma conexão ativa entre o servidor e um cliente.

### Propriedades

`id: String`

Identificador único da conexão física.

`sessionId: SessionId?`

Sessão lógica atualmente associada à conexão, quando existente.

### Operações

`send(message: ServerMessage)`

Envia uma mensagem do servidor para o cliente.

`receive(): ClientMessage?`

Aguarda e retorna uma mensagem válida do cliente.

`close()`

Encerra a conexão.

### Responsabilidade

Representar aquilo que o núcleo do servidor precisa para se comunicar com um cliente sem depender de Ktor, WebSocket ou
outro transporte específico.

---

## 2. KtorConnection

Implementação de `Connection` baseada em WebSocket do Ktor.

### Responsabilidade

Adaptar uma conexão WebSocket do Ktor para a abstração `Connection`.

Realiza:

* recebimento de frames de texto;
* conversão do texto para mensagens do protocolo;
* envio de `ServerMessage` como JSON;
* serialização através do `CommunicationProtocol`;
* sincronização de envios concorrentes;
* encerramento da conexão.

O núcleo do servidor não precisa conhecer `DefaultWebSocketServerSession`.

---

## 3. ConnectionRegistry

Registro das conexões atualmente ativas no servidor.

### Operações

`register(connection: Connection)`

Registra uma nova conexão.

`unregister(id: String)`

Remove uma conexão pelo seu identificador.

`findById(id: String): Connection?`

Localiza uma conexão pelo `id`.

`findBySessionId(sessionId: SessionId): Connection?`

Localiza a conexão associada a uma determinada sessão.

`activeConnections(): List<Connection>`

Retorna as conexões atualmente registradas.

### Características

O registro suporta acesso concorrente e permite que várias conexões sejam administradas simultaneamente.

`ConnectionRegistry` trabalha com identidade de conexão. `SessionId` continua representando a identidade lógica da
sessão no protocolo.

---

## 4. handleConnection

Orquestrador do ciclo de comunicação de uma conexão.

### Assinatura

`handleConnection(connection, registry, sessionLifecycleHandler, structuralValidator, protocolValidator)`

### Responsabilidades

* registrar a conexão;
* receber mensagens continuamente;
* validar a estrutura das mensagens;
* verificar o estado protocolar da conexão;
* estabelecer sessões;
* associar `SessionId` à conexão;
* criar o `SessionSequenceTracker`;
* validar a sequência de mensagens;
* enviar respostas do protocolo;
* encerrar a conexão corretamente;
* remover a conexão do registro ao finalizar.

### Ciclo de sessão

Antes do estabelecimento:

`ClientHello`

→ `SessionLifecycleHandler`

→ `ServerWelcome` ou `ServerReject`

Após o estabelecimento:

`ClientMessage`

→ validação de estado

→ validação de sequência

→ processamento atual da comunicação

O significado das ações do jogo ainda não é implementado pelo servidor.

---

## 5. Integração com CommunicationProtocol

O servidor utiliza diretamente as estruturas existentes do módulo de comunicação.

### Mensagens utilizadas

`ClientMessage`

Mensagem recebida do cliente.

`ServerMessage`

Mensagem enviada pelo servidor.

`ClientHello`

Mensagem utilizada para solicitar o estabelecimento de uma sessão.

`ServerWelcome`

Resposta utilizada quando uma sessão é estabelecida.

`ServerReject`

Resposta utilizada quando o estabelecimento da sessão é recusado.

### Estruturas utilizadas

`SessionId`

Identidade lógica da sessão.

`MessageId`

Identidade das mensagens utilizadas pelo protocolo.

`SessionLifecycleHandler`

Responsável pelo comportamento de estabelecimento da sessão.

`SessionSequenceTracker`

Responsável pelo controle da sequência das mensagens de uma sessão.

`StructuralValidator`

Responsável pela validação estrutural das mensagens.

`ProtocolValidator`

Responsável pela validação do estado protocolar das mensagens.

O servidor utiliza essas estruturas existentes em vez de duplicar suas responsabilidades.

---

## 6. Ciclo de comunicação

O fluxo atual pode ser resumido como:

`WebSocket`

→ `KtorConnection`

→ `Connection`

→ `handleConnection`

→ `CommunicationProtocol`

e no sentido inverso:

`CommunicationProtocol`

→ `ServerMessage`

→ `Connection`

→ `KtorConnection`

→ `WebSocket`

A infraestrutura de transporte permanece separada do núcleo.

---

## 7. Endpoints HTTP

### `GET /health`

Endpoint simples de verificação de disponibilidade.

Resposta atual:

`OK`

Sua finalidade é exclusivamente verificar se o processo do servidor está ativo.

---

## 8. Endpoint WebSocket

### `WS /game`

Endpoint utilizado para a comunicação persistente do jogo.

Cada conexão recebida cria uma nova `KtorConnection` e inicia seu ciclo independente através de `handleConnection`.

O servidor suporta múltiplas conexões simultâneas.

---

## 9. Entry Point

### `main()`

Inicia o servidor utilizando:

* Ktor;
* Netty;
* porta `8080`.

O servidor permanece executando até seu encerramento.

---

## 10. Application Module

### `Application.module(...)`

Inicializa a aplicação Ktor.

Responsabilidades atuais:

* instalar WebSockets;
* configurar `GET /health`;
* configurar `WS /game`;
* criar conexões;
* iniciar o ciclo de comunicação.

O `ConnectionRegistry` e o `SessionLifecycleHandler` podem ser fornecidos ao módulo, permitindo que a infraestrutura
compartilhe essas instâncias durante a execução do servidor.

---

# Capacidades atuais

O servidor já é capaz de:

* iniciar como processo independente;
* permanecer executando;
* responder por HTTP;
* aceitar conexões WebSocket;
* manter múltiplas conexões simultâneas;
* representar cada conexão independentemente;
* registrar e remover conexões;
* localizar conexões por identidade;
* localizar conexões por `SessionId`;
* receber mensagens do protocolo;
* validar mensagens estruturalmente;
* validar mensagens de acordo com o estado da sessão;
* estabelecer sessões através de `ClientHello`;
* responder com `ServerWelcome` ou `ServerReject`;
* associar sessões às conexões;
* controlar sequência de mensagens por sessão;
* enviar mensagens do servidor;
* serializar mensagens através do protocolo existente;
* lidar com desconexões;
* isolar conexões umas das outras;
* operar de forma concorrente e assíncrona.

# Ainda não implementado

O servidor deliberadamente ainda não possui:

* jogadores;
* contas;
* autenticação;
* mundo;
* entidades do RPG;
* NPCs;
* ações de jogo;
* combate;
* turnos;
* economia;
* persistência;
* apresentação do mundo;
* estado global de simulação;
* reconexão;
* recuperação persistente de sessões;
* regras específicas do RPG.

---

# Arquitetura atual

A estrutura conceitual do servidor é:

`HTTP / WebSocket`

↕

`Ktor Infrastructure`

↕

`Connection`

↕

`Server Core`

↕

`CommunicationProtocol`

O transporte é responsabilidade da infraestrutura.

A comunicação é definida pelo `CommunicationProtocol`.

O núcleo trabalha com abstrações próprias e não depende diretamente de Ktor.

O servidor fornece a infraestrutura necessária para o jogo, mas ainda não define o significado das ações do jogo.

---

# Estado da API

Esta API representa a primeira fundação funcional do servidor.

Ela já fornece o mecanismo necessário para que o futuro domínio do RPG possa utilizar conexões e sessões sem precisar
conhecer ou depender diretamente da infraestrutura Ktor.
