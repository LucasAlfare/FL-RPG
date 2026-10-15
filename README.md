# FL-RPG

> **Development status:** early-stage foundation and prototyping. This repository is not a finished game, a playable MMORPG, or a stable public API. This document describes the current architectural state and the main known gaps; it is not a showcase or a user guide.

## Project context

FL-RPG is being developed as a persistent, multiplayer, text-based MMORPG. Its primary direction is **exploration and collection**, rather than a conventional power ladder. Intended long-term pursuits may include discovering places and secrets, collecting items and resources, earning achievements, progressing through levels, learning information about the world, and finding combinations or builds that support a player's own trajectory. These are design goals, not a claim that those gameplay systems are already implemented.

The game is not tied to one specific genre or setting. Its universe is organized around **nuclei**: distinct contexts that may represent different places, historical periods, alternate realities, or fictional and fantastical settings. A nucleus might correspond to a real-world location at a particular time, or to an entirely invented environment. Each nucleus can have its own exploration opportunities, concepts, collectibles, rules, and progression.

The intended starting point is a nucleus corresponding to **Earth in the year 2026**. As the player explores, their character gradually discovers that travel between nuclei is possible. Crossing between them is intended to be meaningful rather than trivial: access conditions, mechanisms, costs, requirements, and consequences will be defined as the game develops. Those travel rules and the actual nucleus content are not implemented yet.

The experience is designed around text as the game representation. The client should present structured game information, current state, available actions, and events without depending on graphical game-world assets. The present desktop client is only an early text-oriented presentation prototype; it does not represent a finished interface or define the final supported platforms.

## Architectural principles

The repository is being built in layers so gameplay rules can evolve without becoming coupled to the client, network transport, or presentation format.

The central principle is:

> **The game provides meaning; libraries provide structure.**

This means that FL-RPG owns the meaning of a game action, fact, character, nucleus, or rule. Supporting libraries provide reusable mechanisms such as world state, movement, turns, combat, and economy. Those libraries should not decide what a concept means in this specific game.

The server is authoritative. Clients submit requests and render server-provided state; they are not the source of truth for game rules or world changes. The communication protocol is a client-server contract, not merely a thin wrapper around WebSocket messages. Ktor and WebSocket are transport adapters and should remain outside the game domain.

The project is intentionally keeping most production code in one main file per foundational module while the APIs and responsibilities are being prototyped. This is a temporary development choice to make the system easier to inspect and discuss. It is not a requirement that the mature project remain organized into large files, nor is splitting files the current priority by itself.

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

| Module | Responsibility | Current state |
|---|---|---|
| `:communication-protocol` | Defines the typed client-server message contract, metadata, validation, serialization, sequencing concepts, and presentation data structures. | Implemented foundation with tests; still subject to protocol stabilization. |
| `:server` | Manages connections and sessions, validates incoming messages, coordinates the protocol with the domain, and sends results and events to clients. | Functional Ktor/WebSocket skeleton integrated with the current domain action pipeline. |
| `:game-domain` | Owns game concepts and rules, nucleus state, action processing, facts, movement initiation, time advancement hooks, and audience calculation. | Minimal vertical slice only; actual game content and most gameplay systems are not implemented. |
| `:client` | Desktop Compose client that connects to the server and renders protocol presentation data in a text-oriented interface. | Temporary prototype; not a completed gameplay client. |
| `quick_refs/` | Maintains focused reference notes for the protocol, server, action pipeline, and supporting FL libraries. | Development documentation, not a substitute for checking current source code. |

## Current architecture and data flow

The current end-to-end pipeline is:

```text
Desktop client
    │ ClientMessage
    ▼
CommunicationProtocol
    │ typed messages, metadata, validation and serialization
    ▼
Server connection/session handling
    │ maps a connection and session to a temporary character identity
    ▼
GameActionProcessor
    │ executes a domain action under the nucleus state lock
    ▼
Nucleus + FL-World
    │ updates world state and produces game facts
    ▼
Server response and audience distribution
    │ direct result for the requesting client; events for interested characters
    ▼
Client presentation state
```

The domain does not depend on Ktor, WebSocket, or Compose. The server translates between protocol-level messages and domain-level actions and results. The protocol describes how the client and server communicate, including presentation structures, without defining the game rules themselves.

### `:communication-protocol`

The protocol currently lives in `communication-protocol/src/main/kotlin/com/lucasalfare/flrpg/communicationprotocol/CommunicationProtocol.kt`.

It defines typed identifiers and message metadata, protocol version compatibility, delivery-mode and sequencing concepts, client/server message hierarchies, validation helpers, JSON serialization, and structured presentation data. The message model includes, among other types:

- Client requests such as `ClientHello`, `ClientAction`, `ClientSelection`, `ClientTextCommand`, `ClientCancel`, and `ClientResyncRequest`.
- Server responses such as `ServerWelcome`, `ServerReject`, `ServerAccepted`, `ServerRejected`, `ServerCompleted`, `ServerFailed`, and `ServerProtocolError`.
- Server-side presentation messages such as `PresentationSnapshot` and `PresentationPatch`.
- Presentation blocks for text, lists, choices, and feed-like content.

The metadata includes typed message, session, and correlation identifiers, sequence values, protocol version, and delivery mode. The implementation also contains structural and protocol validation, sequence tracking concepts, and configurable failure evaluation.

The protocol is already used by both the server and the desktop client. It should continue to be treated as a contract: changes need to account for both endpoints and their tests. Some message types describe capabilities that are not yet fully implemented end to end; the existence of a type alone does not mean the corresponding gameplay or recovery behavior is complete.

### `:server`

The server uses Ktor with Netty and WebSockets. Its current implementation is centered in `server/src/main/kotlin/com/lucasalfare/flrpg/server/Main.kt`.

The server currently provides:

- `GET /health`, which responds with `OK`.
- `WS /game`, used for the persistent client-server connection.
- A transport-neutral `Connection` interface and a `KtorConnection` WebSocket adapter.
- A concurrent `ConnectionRegistry` for active connections and lookup by connection, session, or character identity.
- Session establishment through `ClientHello` and `ServerWelcome`/`ServerReject`.
- Structural validation, protocol-state validation, and per-session incoming sequence tracking.
- Server-side interaction offers, selection validation against the offers issued to that session, pending input for interactions with required arguments, and cancellation of pending input.
- Dispatch from selected interactions into `GameActionProcessor`.
- Direct completion or rejection responses to the requesting connection, plus event distribution to other connected characters identified as interested in a fact.
- Connection-finalization cleanup, including removal of session-scoped pending/offer state, removal from the active connection registry, and removal of the temporary character and its spatial world entity from the nucleus when the connection ends.

Connections, sessions, and characters are distinct concepts. A connection represents a live network link; a session is a protocol-level identity; a character is a game-domain identity. They must not be treated as interchangeable.

The server currently constructs an in-memory nucleus when no processor is supplied. This is infrastructure for the prototype, not a populated game world. There is no account system, durable character identity, database, or saved progression. Session-related protocol structures exist, but they do not amount to a complete persistent account or character-resumption system.

The server can advance world time through explicit calls in the current pipeline, but there is no production scheduler or autonomous game-time loop. Consequently, movement can be started and processed by the domain without the game automatically advancing it continuously.

### `:game-domain`

The current domain implementation is in `game-domain/src/main/kotlin/com/lucasalfare/flrpg/gamedomain/Main.kt`.

Its principal structures are:

- `CharacterId` and `NucleusId`: distinct typed identifiers.
- `Character`: currently a minimal identity/name data class; it is not yet the complete persistent player-character model.
- `Nucleus`: wraps one `FL-World` `World`, tracks registered character IDs, and provides basic location and connection registration.
- `GameAction`: a named action, its actor, and action arguments.
- `Interaction` and `InteractionProvider`: describe actions currently offered to a character, including fixed arguments and required user input.
- `GameFact`: a named fact with associated arguments, emitted by domain processing.
- `GameActionResult`: a successful result or a rejection with a reason, optionally carrying facts.
- `GameFactAudience` and `GameActionExecution`: pair facts and action results with the characters considered interested in those facts.
- `GameActionHandler` and `GameActionProcessor`: register and execute handlers, serialize domain state changes, advance world time explicitly, and calculate fact audiences.

The processor synchronizes action processing and time advancement around the nucleus state lock. This keeps a world mutation and its immediate audience calculation within the same synchronized operation. The current audience calculation is intentionally simple: it uses the location information carried by a fact to find characters at that location. It is not a complete interest-management or visibility system.

Two handlers currently demonstrate the vertical slice:

**Entering the world.** `EnterWorldHandler` verifies that the character is not already registered, ensures a location exists, registers a spatial `WorldEntity`, assigns its location, and registers the character in the nucleus. It produces a `character_entered` fact.

**Starting movement.** `MoveHandler` checks that the character is registered, validates the destination, finds a path through the configured FL-World location graph, and starts a `WorldMovement`. It does not instantly teleport the character. The start of the movement is represented by `character_moved`; later explicit time advancement can produce `character_movement_completed` or `character_movement_interrupted`.

The default interaction provider currently offers a minimal path into these handlers: a character outside the nucleus can select **Enter the world**, and a registered character can select **Move to a location**. Required movement arguments are collected through the current pending-interaction flow. This is scaffolding for the action pipeline, not the final player command system or the actual gameplay interaction design.

Disconnect cleanup removes both the character registration and the corresponding spatial entity from the `World`. This is important because deleting only the character ID would leave world state behind, such as a location assignment or active movement.

### Relationship with the FL libraries

The domain declares dependencies on the following libraries:

| Library | Intended responsibility in FL-RPG | Current integration status |
|---|---|---|
| `FL-World` | World, locations, connections, entities, world state, clock, paths, and movement. | Directly used by the current nucleus and movement pipeline. |
| `FL-Turns` | Reusable turn-flow and turn-order mechanics when game rules need them. | Dependency is present; a complete game turn system is not integrated. |
| `FL-Combat` | Reusable combat concepts and resolution mechanisms. | Dependency is present; the game's combat rules are not implemented. |
| `FL-Economy` | Reusable economy and accounting structures. | Dependency is present; the game's item economy, trading rules, and persistence are not implemented. |

These libraries provide mechanisms, not the finished FL-RPG rule set. Their presence in Gradle does not imply that every subsystem is already wired into the game.

### `:client`

The client is a Kotlin/JVM Compose for Desktop application, currently implemented in `client/src/main/kotlin/com/lucasalfare/flrpg/client/Main.kt`.

It opens a WebSocket connection to the server, performs the protocol handshake, sends selections and text input, receives server messages, and keeps a local presentation state. It understands presentation snapshots and patches, displays connection/result status, and presents server-issued choices in a text-oriented layout. Its current design has a monospaced, terminal-like feel and includes basic local conveniences such as command history and a light/dark theme.

The client is deliberately not the authority for game state. Its current input and display behavior is provisional, and it should not be interpreted as the final client architecture or as evidence that the game has a complete free-form command language. In particular, the server's current `ClientTextCommand` path is used to supply pending interaction arguments; a general natural-language or free-form gameplay command interpreter is not implemented.

## Gameplay concepts: intent versus implementation

The intended gameplay direction is useful context for understanding why the foundation is shaped this way, but most of it is not present in executable form yet.

| Concept | Current design direction | Implementation status |
|---|---|---|
| Exploration | The first major player activity is discovering the environment and learning what exists. | No populated starting area or exploration rules yet. |
| Collection | Items, resources, achievements, knowledge, secrets, places, and other discoveries should give players long-term goals. | No inventory, collection catalog, achievements, or discovery records yet. |
| Character progression | Levels and other progression dimensions may support an individual trajectory. | No character progression system yet. |
| Nuclei | Distinct places and/or temporal or fictional contexts have their own content and rules. | Only a minimal `Nucleus` wrapper exists; there is no multi-nucleus universe model or actual nucleus catalogue. |
| Travel between nuclei | Travel should require discovery, conditions, resources, mechanisms, and possibly consequences. | Not implemented; requirements and travel rules remain to be designed. |
| Local movement | Travel across a nucleus should respect its world graph and can take time. | Movement initiation and explicit time-based completion exist, but there is no populated production map or automatic time loop. |
| Actions and interactions | A character should be able to discover available actions and request them through a text-first client. | A minimal server-offered choice and required-argument flow exists; the final interaction model is not settled. |
| Multiplayer events | Facts caused by one character may need to be delivered to other relevant characters. | A basic location-based audience calculation and event distribution path exists; visibility and interest management remain minimal. |
| Persistence | The game is intended to persist over time and support long-term character progression. | No database, durable accounts, saved characters, inventories, achievements, or progression. |
| Real-time world behavior | Some activities may take time while compatible interactions remain possible. | Explicit world-time advancement exists, but automatic scheduling, activity concurrency rules, and the full gameplay lifecycle are not implemented. |

The universe is not intended to be defined by one combat loop, one item tier ladder, or one genre. Different nuclei may offer different forms of exploration and collection. A nucleus set in contemporary Earth may feel different from a nucleus set in prehistory, on another planet, in a historical period, or in a fictional fantasy context. Those examples describe the scope the model is meant to support, not content currently present in the repository.

## Current validation and tests

The repository uses Gradle Kotlin DSL, Kotlin/JVM, and Java toolchain 25. The GitHub Actions workflow runs `bash ./gradlew build` on pushes, pull requests, and manual workflow dispatches.

Current test coverage spans several foundations:

- Protocol-level types, validation, serialization, sequencing, and presentation behavior.
- Domain action processing and state behavior.
- Server health endpoint and WebSocket session establishment.
- Multiple concurrent client connections and connection cleanup.
- Integration of the action pipeline, including concurrent actions and explicit time advancement.
- Disconnect cleanup for a temporary character and its world entity.

Tests can verify infrastructure and the currently implemented vertical slice; they do not mean the game is feature-complete. Content-specific gameplay tests cannot be comprehensive until the corresponding rules and data exist.

## What still needs to be stabilized before substantive game-content development

The next goal is not to invent a large amount of game content on top of uncertain infrastructure. The foundations need to be sufficiently predictable that content can be added without repeatedly redesigning the networking and state model.

### 1. Protocol and interaction contract

- Finalize the responsibilities of `ClientAction`, `ClientSelection`, and `ClientTextCommand`, including what is generic protocol behavior versus game-specific input.
- Specify the expected behavior for invalid, duplicate, stale, expired, or out-of-order selections and requests.
- Define the complete lifecycle of interactions requiring input: pending arguments, repeated prompts, cancellation, failures, and cleanup.
- Stabilize when the server sends presentation snapshots versus patches, and define recovery behavior when the client's presentation revision becomes stale.
- Clarify the meaning and lifecycle of accepted, completed, rejected, failed, and event messages so client and server behavior stays consistent.

### 2. Connection, session, and character lifecycle

- Keep the distinction between connection, session, and character identities explicit.
- Verify cleanup on normal disconnects, malformed input, transport errors, cancellation, and other termination paths.
- Define session resumption and identity behavior separately from temporary connection setup; current protocol fields do not provide durable accounts or persistence by themselves.
- Keep connection registries, session-scoped pending input, offered choices, character membership, world entities, locations, and active movements consistent when a connection ends.

### 3. Domain state consistency and event delivery

- Preserve the server-authoritative boundary and keep transport types out of the domain.
- Add tests for movement completion and interruption, invalid world state, character removal during movement, and relevant concurrent state changes.
- Decide which facts are visible to which characters and when audiences are calculated. The current location-based rule is only a placeholder for a more deliberate rule.
- Keep action results and event delivery semantically distinct: the requesting client gets a direct result while other interested clients may receive events.
- Define failure handling when processing succeeds but delivery to a client fails; network delivery must not become the source of truth for whether a domain mutation happened.

### 4. World-time and movement boundaries

- Define the meaning and unit of a world tick and how it relates to movement duration and other future activities.
- Decide when and where time advances. There is no automatic production clock or scheduler today, by design.
- Stabilize movement edge cases and concurrency expectations before many content systems begin relying on movement completion events.
- Provide actual world locations and connections only when the world-content stage begins; a valid movement pipeline alone does not constitute a playable map.

### 5. Minimal client/server contract for content authoring

- Confirm how the client renders structured text, choices, lists, and feed/event history without taking on game rules.
- Decide what data belongs in a presentation snapshot, what can be updated incrementally, and how a client recovers from missed or incompatible presentation state.
- Keep display wording and layout separate from domain meaning so one game action can be represented by different clients later.
- Treat the current desktop client as a prototyping tool and avoid letting its provisional behavior dictate domain APIs.

These are stabilization tasks, not a requirement to design every future feature in advance. They should be resolved enough to support safe, incremental implementation. New abstractions should be introduced only when current requirements need them; a full game loop, broad event bus, advanced interest-management system, or large content framework should not be added merely because it might eventually be useful.

## Not implemented yet

The following should be considered future work rather than existing features:

- A populated Earth-2026 starting nucleus, including locations, connections, points of interest, and discoverable content.
- Multiple nuclei and the actual mechanism for discovering, unlocking, and travelling between them.
- Persistent accounts, character storage, inventory storage, and saved player progression.
- A complete player/character model and character creation or selection flow.
- Items, equipment, resources, collection records, achievements, levels, knowledge systems, secrets, missions, and other progression systems.
- Game-specific combat and turn rules built on the relevant libraries where appropriate.
- Economy, trading, crafting, and other item/resource rules.
- NPCs and a finalized interactable-object model. The earlier `InteractableThings` prototype is not part of the active production pipeline and remains intentionally inactive.
- A general gameplay command parser and a stable user-facing command vocabulary.
- A complete event history/current-situation presentation contract and all corresponding domain events.
- Automatic real-time advancement, scheduling for timed activities, and finalized rules for overlapping or compatible activities.
- A production deployment configuration, operational monitoring beyond the basic health endpoint, and mature account/security controls.
- Browser, mobile, or other client targets beyond the current desktop prototype.

## Technology baseline

The current build configuration uses:

- Kotlin/JVM with Java toolchain 25.
- Gradle Kotlin DSL.
- Ktor 3.6.0 for the server and WebSocket transport.
- Kotlin serialization JSON and coroutines in the communication protocol.
- Compose for Desktop for the temporary client.
- `FL-Turns`, `FL-Combat`, `FL-World`, and `FL-Economy` as supporting FL libraries.

Dependency versions and interfaces can change while the project is under active development. The actual Gradle files and source code are authoritative when checking what is available today.

## Development status summary

FL-RPG has a working **architectural skeleton and a tested vertical slice** across protocol, server, and domain: a client can establish a WebSocket session, receive available interactions, submit a selection and any required input, trigger a small set of domain actions, and receive a result. The current domain can register a character in a nucleus, represent that character as a spatial world entity, begin movement along a configured path, advance world time explicitly, and produce facts for basic event distribution.

That is the present scope. The universe, content, persistent player model, collection/progression systems, cross-nucleus travel, automatic real-time behavior, and final player experience remain future work. The immediate engineering focus is to stabilize the existing protocol, lifecycle, state consistency, and action pipeline before building out the actual game content in `:game-domain`.
