# FL-RPG

> **Status: prototype in development.** FL-RPG is not a finished MMORPG or a release-ready game. This README describes the project's current direction and what the implementation can do today.

FL-RPG is a prototype for a **text-based MMORPG focused on exploration and collection**. Its intended identity is not another game built primarily around combat, power levels, and an ever-higher equipment ladder. The goal is to make discovering the world — its places, objects, resources, secrets, knowledge, and other collectibles — central to the experience.

The long-term direction is a persistent multiplayer world organized around **reality nuclei**: contexts that may represent different places, planets, historical periods, or fictional settings. The planned starting point is Earth in 2026. Travel between nuclei should be something players discover and earn through exploration, conditions, mechanisms, and resources rather than an always-available world-selection menu.

That is the game's direction, not a claim that all of these features are complete. The repository currently contains a functional early vertical slice of the supporting infrastructure and a small development scenario used to validate the basic interaction loop.

## What works today

### Text-based exploration

The domain includes an exploration system built around areas, elements, and discovery links. An area has a description, initially discoverable elements, and links that can reveal further elements when examined.

The development scenario includes an explorable area called **Starting Point**. Looking around reveals initial elements such as **Tree** and **Stones**. Examining an element can uncover more: examining the tree can reveal **Exposed Roots**, which can in turn reveal **Hidden Opening**. Results appear in the client, and available interactions refresh as the character discovers more elements.

Area content and discovery links are authored as game data. The domain validates identifiers, references, and graph reachability so an area cannot contain invalid links or unreachable elements.

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

The Gradle project is divided into four modules:

| Module                     | Responsibility                                                                                                                      |
|----------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| **client**                 | Text-based desktop client, server connection, player input, and presentation of received state.                                     |
| **communication-protocol** | Typed client-server contract, messages and metadata, validation, JSON serialization, and presentation structures.                   |
| **server**                 | WebSocket connections, sessions, message validation, action dispatch, event distribution, and periodic simulation-time advancement. |
| **game-domain**            | Game state and rules: temporary characters, nuclei, actions, facts, interactions, exploration, movement, and activity lifecycle.    |

The architecture follows two important principles:

- **The server is authoritative.** The client requests actions and displays their results; it does not independently decide game rules or mutate the world state.
- **The game provides meaning; libraries provide structure.** FL-RPG defines the meaning of its actions and rules. Supporting libraries can provide reusable mechanisms, but having a library as a dependency does not mean its corresponding game system is implemented.

The domain does not directly depend on Ktor, WebSockets, or the Compose interface. The protocol is the contract between client and server, while the server adapts incoming messages into domain actions and turns their results into responses and presentation updates.

## What is not implemented yet

The difference between the game's direction and the current prototype is important. The following are still missing, among other things:

- **Long-term persistence:** there is no database, complete account system, durable character identity, or saved progression. Current character identity and state are temporary; exploration discoveries are not a persistent record.
- **Collection gameplay systems:** there is no complete inventory, collectible-item catalogue, persistent collection, achievement system, or collection progression. Collection is a core design goal, but not yet a completed gameplay system.
- **Character progression:** there is no complete system for levels, character development, or long-term progression.
- **A multi-nucleus universe:** initial nucleus-related structures and exploration metadata exist, but there is no navigable universe of nuclei or travel between realities.
- **A substantial game world:** the current scenario is small and exists to validate the technical foundations. The implemented exploration system does not imply that a large world with many regions and content already exists.
- **Other gameplay systems:** combat, resource gathering, crafting, trading, quests, and equivalent systems are not implemented as complete FL-RPG gameplay. Dependencies on related libraries do not change that.
- **Additional clients and production operation:** the current client is desktop. There is no finished web or mobile client, and the project is not a production deployment.

Disconnecting removes the temporary character and its associated spatial state, including exploration state kept in memory. Therefore, **the project does not yet deliver the persistent world promised by its MMORPG direction**.

## Development direction

The priority is to stabilize and expand the core gameplay loop without losing the intended identity: **explore, discover, and collect**. Session handling, actions, events, simulation time, and presentation exist to support that loop; they are not the end goal by themselves.

Over time, different nuclei may offer distinct settings and content — for example, other places, planets, historical periods, or fictional worlds. These examples describe the desired scope, not places currently available in the game. Travel between nuclei is also intended to be part of discovery and progression rather than a simple map-selection feature.

## Technology baseline

- **Language and runtime:** Kotlin/JVM, with Java toolchain 25.
- **Build system:** Gradle with Kotlin DSL.
- **Server:** Ktor, Netty, and WebSockets.
- **Client:** Compose for Desktop.
- **Protocol serialization:** Kotlin Serialization and JSON.
- **World and supporting libraries:** current integration with FL-World for locations, connections, spatial state, time, and movement. Other FL libraries are available as dependencies, but not all their systems are integrated into gameplay.

The source code and automated tests are the reference for implemented behavior. As the project is still a prototype, APIs, rules, and the interface may change.
