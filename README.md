# FL-RPG

> **Status: prototype in development.** FL-RPG is not a finished MMORPG or a release-ready game. This README describes the project's current direction and what the implementation can do today.

FL-RPG is a prototype for a **text-based MMORPG focused on exploration and collection**. Its intended identity is not another game built primarily around combat, power levels, and an ever-higher equipment ladder. The goal is to make discovering the world — its places, objects, resources, secrets, knowledge, and other collectibles — central to the experience.

The long-term direction is a persistent multiplayer world organized around **reality nuclei**: contexts that may represent different places, planets, historical periods, or fictional settings. The planned starting point is Earth in 2026. Travel between nuclei should be something players discover and earn through exploration, conditions, mechanisms, and resources rather than an always-available world-selection menu.

That is the game's direction, not a claim that all of these features are complete. The repository currently contains a functional early vertical slice of the gameplay infrastructure, a small development scenario, and a content pipeline for authoring, compiling, protecting, and loading game data.

## Why FL-RPG exists

FL-RPG did not begin with a complete design document or a carefully planned universe. It began with a personal question: **what kind of MMORPG would I genuinely want to play now?**

Games such as Tibia, RuneScape, and Perfect World shaped my experience with MMORPGs. I spent a lot of time playing them, developing my characters, and enjoying the sense of progression they offered. But over time, I started feeling that the experiences I wanted were becoming harder to find in those games. RuneScape could feel increasingly complicated to keep up with, with too many things to buy or manage; Tibia could feel constrained by dominant guilds and the politics surrounding them; and in Perfect World, cash-driven progression could make it difficult to advance through playing alone. These games still mattered to me, but they no longer fully matched what I was looking for.

That made me ask a more fundamental question: **what actually makes me want to return to an MMORPG?** When I thought about the activities I enjoyed most, I kept coming back to collecting things, building up a character, and gradually discovering more of what a game had to offer. The idea that stood out was not simply becoming stronger than other players. It was having a world full of things worth finding, pursuing, recording, and collecting.

From there, another question followed: what would make me want to keep playing and developing my own game? The answer that gradually emerged was **exploration and collection, supported by a satisfying sense of progression**. The design was not mapped out in advance. It took shape little by little, as one question led to another and the idea became more concrete.

I then had to decide what form the game should take. A two-dimensional game initially seemed likely to lead toward a platformer, a format I did not want to make and which, to me, felt limiting for the kind of exploration I imagined. Three-dimensional development offered other possibilities, but it would also make the project considerably more complex, particularly when trying to define and consistently produce a visual style with the help of AI tools. I started to realize that graphics were not the point of the idea in the first place. If the heart of the game was discovering and collecting things, a world did not need to be rendered in real time to feel worth exploring.

That brought to mind games such as Gladiatus, which showed me a way to play an online RPG without relying on a conventional real-time graphical world. I wanted to pursue a related text-based approach, while keeping the sense of an ongoing world and the gradual character development I enjoyed in my favorite MMORPGs. Text was not meant to be a shortcut or a limitation to work around. It became a deliberate choice that let me concentrate on the activities I cared about most: discovering, collecting, progressing, and finding another reason to return.

The idea of the **reality nuclei** grew from the same desire for freedom. I did not want to commit the entire game to one fixed setting, such as a medieval fantasy world, a futuristic universe, or a particular historical period. I enjoy many different styles, and choosing only one would mean ruling out possibilities I wanted to explore. By connecting distinct realities, FL-RPG can move between those styles without pretending they all belong to the same conventional world. One nucleus might resemble the contemporary Earth; another might contain prehistoric life, science fiction, medieval fantasy, or stranger realities that do not fit familiar categories.

This breadth is not intended to make the world arbitrary. Each nucleus should have its own identity, places, discoveries, resources, and reasons to explore it. The connection between nuclei provides a framework in which very different kinds of content can coexist, while collection and discovery give the player a consistent purpose across them.

The original motivation remains the project's compass: to build the kind of text-based MMORPG I would want to play myself — a game where exploration leads to discoveries, discoveries feed collections, and long-term activities let me farm resources, develop a character, gain levels, and gradually reach goals that once felt out of reach. There should always be something new worth pursuing. Combat, economy, character development, and multiplayer systems can all contribute to that experience, but they should support this central idea rather than replace it.

### Why the content pipeline matters

There is another part of the experience that I wanted to treat carefully from the beginning: **the content itself**. In a game centered on exploration, the world data is not merely configuration. It contains the places, relationships, discoveries, and secrets that make exploration meaningful. Exposing all of that authored material in the main source repository — or distributing it as readable files — would make it unnecessarily easy to inspect future content and spoil discoveries before players encounter them.

For that reason, FL-RPG now has a dedicated content pipeline. The editable TOML sources live separately, in the private `FL-RPG-Content` repository. The public game repository contains the compiler and reader, and can receive only the compiled, encrypted content artifact. The FLCP format is documented and its structure is not treated as a secret; confidentiality comes from the cryptographic key, not from hiding the file format. A copy of the compiled package is not enough to read its protected payload without the corresponding key.

This was a deliberate foundation, not an attempt to implement every future system prematurely. Establishing the boundary before the content library becomes large avoids having to reorganize and protect a growing body of authored material later. It also gives the project a consistent way to validate and deliver content as the game expands.

The current implementation supports world and exploration data. The same packaging structure can be extended gradually with additional content sections — for example, items, collections, and achievements — when those gameplay systems are designed and implemented. That extensibility is a direction for the content format, not a claim that those systems already exist.

Encryption protects the package while it is stored or distributed. It does not make content unknowable after the server has loaded it, nor does it hide information that the game intentionally presents to a player during gameplay. The goal is to keep private authored sources and unrevealed content out of casual inspection, while maintaining a clean boundary between content delivery and game rules.

## What works today

### Content authoring, compilation, and loading

Game content is authored as separate TOML files rather than being embedded directly into application code. The current development pack contains a manifest (`pack.toml`), world data (`world.toml`), and one TOML file per exploration area under `exploration/areas/`.

The `content-compiler` module validates those sources and produces a binary **FLCP (FL Content Pack)** artifact. The current FLCP v1 pipeline is:

`TOML → intermediate content models → CBOR → Zstandard → AES-256-GCM → .flcp`

The FLCP file is a binary container with a documented header identifying its container version, serialization, compression, encryption, and key ID. The encrypted payload contains the compressed CBOR representation. The reader performs the reverse operations, authenticates and decrypts the payload, decompresses and decodes it, and validates compatibility and content structure.

At server startup, the default application configuration reads the package identified by `FL_RPG_CONTENT_PACK_PATH` and uses `FL_RPG_CONTENT_KEY_ID` and `FL_RPG_CONTENT_KEY` to identify and decrypt it. The loaded content is converted into game-domain models through the server-side `ContentNucleusFactory`. The domain does not need to know how the content was authored, serialized, compressed, encrypted, or stored.

The current server supports container version 1, schema version 1, and the `world` and `exploration` sections at version 1. It does not yet support hot reload, dynamic selection of multiple packs, a content editor, or key management.

The intended workflow is to keep editable sources in the private content repository, compile them into an FLCP artifact, and distribute the compiled package with the server. The key must remain outside source control and must be available to the server process at runtime. The detailed source layout, TOML schema, validation rules, binary header, environment configuration, and Gradle commands are documented in [Content Pack System](docs/content_pack_system.md).

### Text-based exploration

The domain includes an exploration system built around areas, elements, and discovery links. An area has a description, initially discoverable elements, and links that can reveal further elements when examined.

The development scenario includes an explorable area called **Starting Point**. Looking around reveals initial elements such as **Tree** and **Stones**. Examining an element can uncover more: examining the tree can reveal **Exposed Roots**, which can in turn reveal **Hidden Opening**. Results appear in the client, and available interactions refresh as the character discovers more elements.

The exploration data is now authored in the content sources and loaded into the domain as regular game models. The domain validates identifiers, references, and graph reachability so an area cannot contain invalid links or unreachable elements.

### Movement through a test world

The development world is a graph of connected locations; it does not depend on a graphical map or coordinates. The current test map contains **Starting Point**, **Winding Path**, **Central Plaza**, and **Distant City**.

The character can select destinations reachable from the current location. Movement takes simulation time rather than teleporting instantly. The server tracks the activity, presents its destination and progress information when available, and allows cancellation when the activity is cancellable.

### Server-offered actions and interactions

The server calculates the interactions available to a character in the current context. Implemented examples include entering the world, moving, looking around, examining discovered elements, and cancelling an eligible activity.

The client receives the available choices, presents shortcuts, and sends the selected option. The server validates that the option was actually offered to that session before dispatching it to the game domain. Interactions that require additional arguments are also supported. **A general free-form gameplay command interpreter does not exist yet**: text input participates in the current interaction flow, but arbitrary sentences are not interpreted as game commands.

### Activities and simulation time

The domain has generic structures for ongoing activities, including lifecycle states, execution modes, and cancellation support. Movement is the activity type concretely integrated today.

The server advances simulation time periodically and processes activity progress and completion. This supports an end-to-end timed activity loop, but it is not yet a complete scheduler or a finished ruleset for all future activity types.

### Multiplayer infrastructure

The server accepts concurrent connections and keeps connection, session, and character identities separate. It returns action results to the initiating connection and distributes game facts to characters considered interested in them. The current interest rule is simple and location-based; it is not a complete visibility or multiplayer interest-management system.

This provides a foundation for running the prototype with more than one connected client. **It does not mean that every system expected from a multiplayer MMORPG is implemented.**

### Text client

The current client is a Kotlin/JVM application built with Compose for Desktop. It connects to the server over WebSocket and presents server-provided state in a terminal-like text interface.

The interface can display available choices, location and exploration information, action results, recent events, and active activity details. It also has text input, shortcuts for server-provided choices, input history, and light/dark theme selection. The client renders state supplied by the server; it is not authoritative for game rules or world state.

## Project structure

The Gradle project is divided into five modules:

| Module                     | Responsibility                                                                                                                                            |
|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| **client**                 | Text-based desktop client, server connection, player input, and presentation of received state.                                                           |
| **communication-protocol** | Kotlin Multiplatform client-server contract, serializable messages and metadata, and presentation structures.                                             |
| **server**                 | WebSocket connections, sessions, message validation, action dispatch, event distribution, content-pack loading, and periodic simulation-time advancement. |
| **game-domain**            | Game state and rules: temporary characters, nuclei, actions, facts, interactions, exploration, movement, and activity lifecycle.                          |
| **content-compiler**       | TOML source parsing and validation, content models, FLCP serialization and encryption, and package reading.                                               |

The architecture follows a few important principles:

- **The server is authoritative.** The client requests actions and displays their results; it does not independently decide game rules or mutate the world state.
- **The game provides meaning; libraries provide structure.** FL-RPG defines the meaning of its actions and rules. Supporting libraries can provide reusable mechanisms, but having a library as a dependency does not mean its corresponding game system is implemented.
- **Content delivery is separate from gameplay rules.** The compiler and reader handle the content format and its protection. The server adapts the loaded content into domain models; the game domain does not depend on TOML, FLCP, CBOR, compression, encryption, Ktor, WebSockets, or the client interface.

The protocol is the contract between client and server, while the server adapts incoming messages into domain actions and turns their results into responses and presentation updates. The communication protocol currently declares JVM and JavaScript targets; the desktop client remains JVM-based.

## What is not implemented yet

The difference between the game's direction and the current prototype is important. The following are still missing, among other things:

- **Long-term persistence:** there is no database, complete account system, durable character identity, or saved progression. Current character identity and state are temporary; exploration discoveries are not a persistent record.
- **Collection gameplay systems:** there is no complete inventory, collectible-item catalogue, persistent collection, achievement system, or collection progression. Collection is a core design goal, but not yet a completed gameplay system. The current content pack format only supports world and exploration sections.
- **Character progression:** there is no complete system for levels, character development, or long-term progression.
- **A multi-nucleus universe:** initial nucleus-related structures and exploration metadata exist, but there is no navigable universe of nuclei or travel between realities.
- **A substantial game world:** the current scenario is small and exists to validate the technical foundations. The implemented exploration system does not imply that a large world with many regions and content already exists.
- **Other gameplay systems:** combat, resource gathering, crafting, trading, quests, and equivalent systems are not implemented as complete FL-RPG gameplay. Dependencies on related libraries do not change that.
- **Additional clients and production operation:** the current client is desktop. There is no finished web or mobile client, and the project is not a production deployment.
- **Advanced content operations:** content is loaded during the server's default startup flow. There is no hot reload, multiple-pack orchestration, content editor, or automated key lifecycle management.

Disconnecting removes the temporary character and its associated spatial state, including exploration state kept in memory. Therefore, **the project does not yet deliver the persistent world promised by its MMORPG direction**.

## Development direction

The priority is to stabilize and expand the core gameplay loop without losing the intended identity: **explore, discover, and collect**. Session handling, actions, events, simulation time, content loading, and presentation exist to support that loop; they are not the end goal by themselves.

The content pipeline provides a foundation for growing the amount of authored game data without coupling it to the source code or exposing the editable content repository. New content sections can be introduced as their corresponding gameplay systems become real requirements. Items, collections, and achievements are possible future additions, not implemented sections today.

Over time, different nuclei may offer distinct settings and content — for example, other places, planets, historical periods, or fictional worlds. These examples describe the desired scope, not places currently available in the game. Travel between nuclei is also intended to be part of discovery and progression rather than a simple map-selection feature.

## Documentation

The `docs/` directory contains deeper technical references for the current implementation:

- [Content Pack System](docs/content_pack_system.md) — private TOML sources, pack schema, validation, FLCP v1 container, cryptography, environment variables, compilation, and server loading.
- [Exploration System](docs/exploration_system.md) — exploration areas, elements, discovery links, and domain behavior.
- [Data Flow Lifecycle](docs/data_flow_lifecycle.md) — how game actions and facts move through the domain, server, protocol, and client.
- [Communication Protocol](docs/communication_protocol.md) — client-server messages and protocol structures.
- [FL-World](docs/fl-world.md) — world locations, connections, spatial state, time, and movement.

The source code and automated tests remain the reference for implemented behavior. As the project is still a prototype, APIs, rules, formats, and the interface may change.

## Technology baseline

- **Language:** Kotlin.
- **Runtime:** JVM for the client, server, game domain, and content compiler. The communication protocol is Kotlin Multiplatform and currently declares JVM and JavaScript targets.
- **Build system:** Gradle with Kotlin DSL.
- **Server:** Ktor, Netty, and WebSockets.
- **Client:** Compose for Desktop.
- **Communication protocol serialization:** Kotlin Serialization and JSON.
- **Game content:** TOML authoring sources, CBOR serialization, Zstandard compression, AES-256-GCM encryption, and the binary FLCP v1 container.
- **World and supporting libraries:** current integration with FL-World for locations, connections, spatial state, time, and movement. Other FL libraries are available as dependencies, but not all their systems are integrated into gameplay.