# FL-RPG

> **Development status:** early-stage foundation and prototyping. This repository is not a finished game or a playable MMORPG. This document describes the current architectural state, implemented foundations, and main known gaps.

## Project context

FL-RPG is being developed as a persistent, multiplayer, text-based MMORPG. Its primary direction is **exploration and collection**, rather than a conventional power ladder.

The intended experience may include discovering places and secrets, collecting items and resources, earning achievements, learning about the world, and finding combinations or builds that support each player's individual trajectory. These are design goals, not a claim that all these gameplay systems are already implemented.

The universe is organized around **nuclei**: distinct contexts that may represent different places, historical periods, alternate realities, or fictional and fantastical settings. Each nucleus can have its own exploration opportunities, concepts, collectibles, rules, and progression.

The intended starting point is a nucleus corresponding to **Earth in the year 2026**. As the player explores, their character gradually discovers that travel between nuclei is possible. Crossing between them is intended to require meaningful conditions, mechanisms, resources, and potentially consequences. This universe and its travel rules are not implemented yet.

The game is designed around text as its primary representation. The client should present structured game information, current state, available actions, and events without depending on graphical game-world assets. The current desktop client is an early text-oriented prototype, not a finished interface or a commitment to a final platform.

## Architectural principles

The repository is divided into layers so gameplay rules can evolve without becoming coupled to the client, network transport, or presentation format.

The central principle is:

> **The game provides meaning; libraries provide structure.**

FL-RPG owns the meaning of a game action, fact, character, nucleus, or rule. Supporting libraries provide reusable mechanisms such as world state, movement, turns, combat, and economy. Those libraries should not decide what a concept means in this specific game.

The server is authoritative. Clients submit requests and render server-provided state; they are not the source of truth for game rules or world changes.

The communication protocol is a client-server contract, not merely a thin wrapper around WebSocket messages. Ktor, Netty, WebSocket, and the client presentation framework are infrastructure and adapters, not dependencies of the game domain.

Most production code is currently concentrated in one main file per foundational module. This is a temporary development choice that makes the implementation easier to inspect and discuss while its APIs and responsibilities are being prototyped.

## Repository structure

The Gradle project currently contains four modules:

```text
FL-RPG/
├── client/
├── communication-protocol/
├── game-domain/
├── server/
├── quick_refs/
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

| Module                    | Responsibility                                                                                                                                    | Current state                                                                   |
|---------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------|
| `:communication-protocol` | Defines the typed client-server message contract, metadata, validation, serialization, sequencing, and presentation structures.                   | Implemented foundation with tests; still subject to stabilization.              |
| `:server`                 | Manages connections and sessions, validates incoming messages, coordinates the protocol with the domain, and sends results and events to clients. | Functional Ktor/WebSocket server integrated with the current domain pipeline.   |
| `:game-domain`            | Owns game concepts and rules, nucleus state, action processing, facts, activities, movement, and time advancement.                                | Minimal vertical slice; most gameplay systems and content remain unimplemented. |
| `:client`                 | Desktop Compose client that connects to the server and renders protocol presentation data in a text-oriented interface.                           | Working prototype, not a completed gameplay client.                             |
| `quick_refs/`             | Contains focused reference notes for the protocol, server, action pipeline, and supporting FL libraries.                                          | Development documentation; actual source code remains authoritative.            |

## Current architecture and data flow

The current end-to-end pipeline is:

```text
Desktop client
    │ ClientMessage
    ▼
CommunicationProtocol
    │ Typed messages, metadata, validation and serialization
    ▼
Server connection/session handling
    │ Associates the connection and session with a temporary character identity
    ▼
GameActionProcessor
    │ Executes domain actions and advances world time
    ▼
Nucleus + FL-World
    │ Maintains world state, movement and spatial entities
    ▼
Game facts and activity lifecycle
    │ Results, events and activity updates
    ▼
Server response and event distribution
    │ Sends responses and events to relevant connected characters
    ▼
Client presentation state
```

The domain does not depend on Ktor, WebSocket, or Compose. The server translates between protocol-level messages and domain-level actions and results.

The protocol describes how the client and server communicate, including presentation structures, without defining the game's rules themselves.

## `:communication-protocol`

The protocol currently lives in:

`communication-protocol/src/main/kotlin/com/lucasalfare/flrpg/communicationprotocol/CommunicationProtocol.kt`

It defines typed identifiers and message metadata, protocol version compatibility, delivery modes, sequencing concepts, client/server message hierarchies, validation helpers, JSON serialization, and structured presentation data.

The message model includes:

- Client requests such as `ClientHello`, `ClientAction`, `ClientSelection`, `ClientTextCommand`, `ClientCancel`, and `ClientResyncRequest`.
- Server responses such as `ServerWelcome`, `ServerReject`, `ServerAccepted`, `ServerRejected`, `ServerCompleted`, `ServerFailed`, and `ServerProtocolError`.
- Presentation messages such as `PresentationSnapshot` and `PresentationPatch`.
- Presentation blocks for text, lists, choices, and feed-like content.

The metadata includes typed message, session, and correlation identifiers, sequence values, protocol version, and delivery mode.

The protocol also contains structural and protocol validation, sequence-tracking mechanisms, failure evaluation, rate-limiting support, and presentation data structures.

The protocol is used by both the server and the desktop client. It must be treated as a contract: changes need to account for both endpoints and their tests. The existence of a message type does not, by itself, mean that its entire behavior is implemented end to end.

## `:server`

The server uses Ktor with Netty and WebSockets. Its current implementation is centered in:

`server/src/main/kotlin/com/lucasalfare/flrpg/server/Main.kt`

The server currently provides:

- `GET /health`, which responds with `OK`.
- `WS /game`, used for persistent client-server communication.
- A transport-neutral `Connection` interface and a `KtorConnection` WebSocket adapter.
- A concurrent `ConnectionRegistry` for active connections and lookup by connection, session, or character identity.
- Session establishment through `ClientHello` and `ServerWelcome` or `ServerReject`.
- Structural validation, protocol-state validation, and incoming sequence tracking.
- Server-issued interaction offers and validation of selections against the offers issued to a session.
- Pending input for interactions requiring arguments, including cancellation and cleanup of pending input.
- Dispatch from selected interactions into the domain's `GameActionProcessor`.
- Direct results for the requesting connection and event distribution to other connected characters identified as interested in a fact.
- Correlation between initiating messages and operations that may remain active.
- Activity progress updates, including descriptions, percentages, and remaining time.
- Connection-finalization cleanup, including removal of session-scoped state and removal of the temporary character and its spatial world entity from the nucleus.

Connections, sessions, and characters are distinct concepts. A connection represents a live network link; a session is a protocol-level identity; a character is a game-domain identity. They must not be treated as interchangeable.

The server currently constructs an in-memory nucleus when no processor is supplied. This is infrastructure for the prototype, not a populated game world.

There is no account system, durable character identity, database, or saved progression. Session-related protocol structures do not amount to a complete persistent account or character-resumption system.

The server and domain support explicit time advancement and processing of time-dependent activities. This is still a prototype rather than a complete production scheduling system.

## `:game-domain`

The current domain implementation is in:

`game-domain/src/main/kotlin/com/lucasalfare/flrpg/gamedomain/Main.kt`

Its principal structures include:

- `CharacterId` and `NucleusId`: distinct typed identifiers.
- `Character`: a minimal identity/name data class, not yet a complete persistent character model.
- `CharacterAttributes`: character-owned attributes, including base and effective movement speed.
- Typed movement and time units, including `DistanceCm`, `SpeedCmPerSecond`, and `SimulationSeconds`.
- `Nucleus`: wraps an `FL-World` `World`, tracks registered character IDs, and provides basic location and connection registration.
- `GameAction`: a named action, its actor, and action arguments.
- `Interaction` and `InteractionProvider`: describe actions currently offered to a character, including fixed arguments and required user input.
- `GameFact`: a named fact with associated arguments, emitted by domain processing.
- `GameActionResult`: a successful result or a rejection with a reason, optionally carrying facts.
- `GameFactAudience` and `GameActionExecution`: associate facts and action results with the characters considered interested in those facts.
- `GameActionHandler` and `GameActionProcessor`: register and execute handlers, synchronize domain state changes, advance world time, and calculate fact audiences.
- Activity structures and handlers for tracking operations that can remain active over time.

The processor synchronizes action processing and time advancement around the nucleus state lock. This keeps a world mutation and its immediate audience calculation within the same synchronized operation.

The current audience calculation is intentionally simple. It uses location information carried by a fact to find characters at that location. It is not a complete visibility or interest-management system.

### Entering the world

`EnterWorldHandler` verifies that a character is not already registered, ensures a location exists, registers a spatial `WorldEntity`, assigns its location, and registers the character in the nucleus.

It produces a `character_entered` fact.

### Movement

`MoveHandler` checks that the character is registered, validates the destination, finds a path through the configured FL-World location graph, calculates movement duration using typed distance and speed values, and starts a `WorldMovement`.

Movement is not an instantaneous teleport. Its progress and completion depend on time advancement.

The domain can produce facts representing movement initiation, completion, and interruption. The current map is prototype infrastructure rather than a populated production environment.

### Activities

The domain includes a generic `Activity` model for operations that may remain active over time.

An activity records its identity, owning character, type, description, execution mode, start time, duration, and lifecycle status.

The current lifecycle statuses are:

- `RUNNING`
- `COMPLETED`
- `CANCELLED`
- `INTERRUPTED`
- `FAILED`

The current execution modes are `BLOCKING` and `NON_BLOCKING`. These provide a foundation for distinguishing activities that restrict other actions from activities that may coexist with compatible interactions. The complete gameplay rules for activity concurrency have not yet been established.

An active-activity registry tracks activities associated with each character, and `ActivityHandler` supports lifecycle handling.

Movement is currently the implemented activity type. The generic activity model should not be interpreted as evidence that crafting, gathering, combat, or other timed activities already exist.

### Activity cancellation and progress

The domain supports cancellation of an active activity after checking that the activity belongs to the requesting character and is still active.

For movement, cancellation stops the associated FL-World movement and records the appropriate domain facts and terminal activity state.

The server correlates an operation with its initiating message so a client can request cancellation through `ClientCancel`, targeting the original message ID. The server remains responsible for deciding whether cancellation is valid.

Activity progress can be represented through the current activity description, percentage, and estimated remaining time. The server sends updated presentation snapshots using a stable `current_activity` block.

These mechanisms are a foundation for timed gameplay, not a complete activity scheduler or a finished set of concurrency rules.

### Disconnect cleanup

Disconnect cleanup removes both the character registration and the corresponding spatial entity from the world. This avoids leaving behind world state such as a location assignment or active movement.

Active activity cleanup is also part of the disconnect path so that operations associated with a disconnected temporary character do not continue unintentionally.

## Relationship with the FL libraries

The domain declares dependencies on the following libraries:

| Library      | Intended responsibility in FL-RPG                                                 | Current integration status                                                                          |
|--------------|-----------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `FL-World`   | World, locations, connections, entities, world state, clock, paths, and movement. | Directly used by the nucleus and movement pipeline.                                                 |
| `FL-Turns`   | Reusable turn-flow and turn-order mechanics when game rules require them.         | Dependency is present; a complete game turn system is not integrated.                               |
| `FL-Combat`  | Reusable combat concepts and resolution mechanisms.                               | Dependency is present; the game's combat rules are not implemented.                                 |
| `FL-Economy` | Reusable economy and accounting structures.                                       | Dependency is present; the game's item economy, trading rules, and persistence are not implemented. |

These libraries provide mechanisms, not the finished FL-RPG rule set. Their presence in Gradle does not imply that every subsystem is already wired into the game.

## `:client`

The client is a Kotlin/JVM Compose for Desktop application, currently implemented in:

`client/src/main/kotlin/com/lucasalfare/flrpg/client/Main.kt`

It opens a WebSocket connection to the server, performs the protocol handshake, sends selections and text input, receives server messages, and maintains local presentation state.

The client understands presentation snapshots and patches, displays connection and result status, and presents server-issued choices in a text-oriented layout. Its current interface has a monospaced, terminal-like feel and includes basic conveniences such as command history and a light/dark theme.

The client recognizes the textual commands `cancel` and `cancelar` for requesting cancellation of an active operation when the corresponding initiating message ID is available. It can display activity progress received from the server.

The client is not authoritative for game state. Cancellation requests do not locally decide whether an activity has stopped; the server and domain validate and process them.

The input and display behavior remain provisional. The server's current `ClientTextCommand` path supplies pending interaction arguments; a general free-form gameplay command interpreter is not implemented.

## Gameplay concepts: intent versus implementation

The intended gameplay direction is important context, but most gameplay systems are not yet present in executable form.

| Concept                  | Current design direction                                                                                          | Implementation status                                                                                                                                                          |
|--------------------------|-------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Exploration              | Discovering the environment and learning what exists should be the first major player activity.                   | No populated starting area or exploration rules yet.                                                                                                                           |
| Collection               | Items, resources, achievements, knowledge, secrets, places, and other discoveries should provide long-term goals. | No inventory, collection catalog, achievements, or discovery records yet.                                                                                                      |
| Character progression    | Levels and other progression dimensions may support an individual trajectory.                                     | No character progression system yet.                                                                                                                                           |
| Nuclei                   | Distinct places, periods, or fictional contexts have their own content and rules.                                 | Only a minimal `Nucleus` wrapper exists; there is no multi-nucleus universe model or nucleus catalog.                                                                          |
| Travel between nuclei    | Travel should require discovery, conditions, resources, mechanisms, and potentially consequences.                 | Not implemented.                                                                                                                                                               |
| Local movement           | Movement across a nucleus should respect its world graph and take time.                                           | Movement initiation, activity tracking, cancellation, and explicit time-based processing exist; there is no populated production map or complete autonomous world-time system. |
| Actions and interactions | Characters should discover available actions and request them through a text-first client.                        | A minimal server-offered choice and required-argument flow exists; the final interaction and command model is not settled.                                                     |
| Timed activities         | Some operations should take time while compatible interactions may remain possible.                               | Generic activity lifecycle foundations and movement integration exist; other activity types and complete concurrency rules do not.                                             |
| Multiplayer events       | Facts caused by one character may need to reach other relevant characters.                                        | Basic location-based audience calculation and event distribution exist; visibility and interest management remain minimal.                                                     |
| Persistence              | The game should persist over time and support long-term character progression.                                    | No database, durable accounts, saved characters, inventories, achievements, or progression.                                                                                    |

The universe is not intended to be defined by a single combat loop, item-tier ladder, or genre. Different nuclei may offer different forms of exploration and collection. Examples such as contemporary Earth, prehistory, another planet, a historical period, or a fictional fantasy context describe the intended scope, not content currently present in the repository.

## Current validation and tests

The repository uses Gradle Kotlin DSL, Kotlin/JVM, and Java toolchain 25. The GitHub Actions workflow runs `bash ./gradlew build` on pushes, pull requests, and manual workflow dispatches.

Existing test coverage spans several foundations:

- Protocol-level types, validation, serialization, sequencing, and presentation behavior.
- Domain action processing and state behavior.
- Server health endpoint and WebSocket session establishment.
- Multiple concurrent client connections and connection cleanup.
- Integration of the action pipeline, including concurrent actions and explicit time advancement.
- Disconnect cleanup for a temporary character and its world entity.
- Protocol and domain behavior covered by the existing automated and stress tests.

Tests can verify infrastructure and the currently implemented vertical slice. They do not mean the game is feature-complete. Content-specific gameplay tests cannot be comprehensive until the corresponding rules and data exist.

## What still needs to be stabilized

The next goal is to make the foundations predictable enough to support substantive game-content development without repeatedly redesigning networking, state management, and action execution.

### 1. Protocol and interaction contract

- Finalize the responsibilities of `ClientAction`, `ClientSelection`, and `ClientTextCommand`, including what belongs to generic protocol behavior versus game-specific input.
- Specify expected behavior for invalid, duplicate, stale, expired, or out-of-order selections and requests.
- Define the complete lifecycle of interactions requiring input: pending arguments, repeated prompts, cancellation, failures, and cleanup.
- Stabilize when the server sends presentation snapshots versus patches and define recovery behavior when a client's presentation revision becomes stale.
- Clarify the meaning and lifecycle of accepted, completed, rejected, failed, and event messages so client and server behavior remains consistent.

### 2. Connection, session, and character lifecycle

- Keep connection, session, and character identities distinct.
- Define how temporary characters will eventually relate to accounts and persistent character identities.
- Ensure disconnect cleanup consistently handles spatial entities, active activities, pending interactions, and other session-scoped state.
- Define reconnection and session-resumption behavior before adding durable player state.

### 3. Activities and world time

- Define which activities block other actions and which can coexist with compatible interactions.
- Specify how activity cancellation, interruption, failure, completion, and disconnect cleanup interact.
- Establish how world time advances during normal server operation and how time-dependent activities progress without relying exclusively on explicit test or action-triggered advancement.
- Define how activity progress and terminal outcomes are represented consistently in the protocol and client presentation.

### 4. Domain and world model

- Stabilize the relationship between characters, nuclei, worlds, locations, entities, and world time.
- Define the intended meaning of game facts and the conditions under which other characters should receive them.
- Keep the domain independent of transport and presentation details.
- Add actual starting-world content only after the required domain and interaction contracts are sufficiently stable.

### 5. Client presentation

- Continue validating the current text-first presentation against real protocol data.
- Clarify how current state, event history, available actions, pending input, and active activities should be represented.
- Keep the client responsive to server-provided state without making it responsible for game rules.
- Avoid prematurely committing to a final client architecture or platform set.

### 6. Persistence and gameplay systems

- Define persistence requirements before introducing database-specific behavior into the domain.
- Add character progression, inventory, collection, achievements, combat, economy, and other gameplay systems incrementally, based on explicit game rules.
- Integrate supporting libraries where their mechanisms fit the game's requirements rather than assuming their presence completes the corresponding subsystem.

## Technology baseline

- **Language:** Kotlin
- **Build system:** Gradle with Kotlin DSL
- **Runtime:** JVM, Java toolchain 25
- **Server:** Ktor, Netty, WebSockets
- **Client:** Compose for Desktop
- **Protocol serialization:** Kotlin serialization and JSON
- **Testing:** Gradle test tasks and automated GitHub Actions builds

The repository is still evolving. The current source code and automated tests are the primary references for implementation behavior; this README summarizes the overall architecture and development status rather than defining a stable public API.
