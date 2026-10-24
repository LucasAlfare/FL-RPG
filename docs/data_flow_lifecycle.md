# Data Flow and Lifecycle

## 1. Scope and Architectural Principle

This document describes the current end-to-end lifecycle of player interactions in FL-RPG, from client input through the communication protocol and server into the game domain, and back to the client.

The server is authoritative. The client expresses player intent and renders server-provided information. It does not determine game rules, validate discoveries, or decide whether an action succeeds.

The communication protocol defines the contract between the client and server. The server translates between that contract and the game domain's action, result, and fact models.

## 2. Establishing a Session

The client opens a WebSocket connection to the server's `/game` endpoint and sends a `ClientHello` message.

The server receives the message through its connection abstraction, performs structural and session-state validation, and passes the handshake to the session lifecycle handler.

When the handshake succeeds, the server sends `ServerWelcome`, associates the connection with a newly created session ID, creates a temporary character ID, and initializes inbound sequence tracking.

These identities serve different purposes. A connection represents a live network link, a session represents a protocol-level conversation, and a character represents the game-domain actor. They are related, but they are not interchangeable.

When an action processor is available, the server prepares and sends the initial presentation of available interactions. The client can then display the actions offered to that character.

## 3. The Client Expresses an Intention

During play, the server provides choices derived from the domain's interaction provider. The client displays those choices and any assigned shortcuts.

When the player selects a choice, the client sends a `ClientSelection` containing the selected choice ID and message metadata.

This message carries the identity of a previously offered choice. It does not carry a trusted game-domain object, and the client cannot define an arbitrary executable interaction merely by sending a choice ID.

The server resolves the choice against the offers stored for that session. If the choice is unknown or expired, the server returns `ServerRejected` without executing it.

Some interactions represent menus. Selecting a menu causes the server to prepare and send a new presentation containing the menu's child interactions.

Other interactions require additional text arguments. In that case, the server stores a pending interaction, prompts for the next argument, and returns an acceptance response indicating that input is required.

A `ClientTextCommand` currently supplies an argument to an interaction that is already pending. It is not a general free-form gameplay command interpreter. If no interaction is awaiting input, the server rejects the message.

## 4. Protocol Validation and Dispatch

Before dispatching established-session messages, the server checks structural validity, verifies that the message is permitted in the current session state, confirms that the message's session ID matches the connection's session, and processes inbound sequence tracking.

Messages that fail these checks are discarded by the current connection-processing loop rather than passed to game logic.

For a selection, the server resolves the character associated with the session and retrieves the selected interaction from the session's current offer state.

For interactions requiring additional arguments, the server trims the supplied text, rejects blank input, and records the argument against the pending interaction. Once all required arguments have been collected, the server combines them with the interaction's fixed arguments.

At this boundary, the server constructs a domain-level `GameAction` containing the action name, the character ID, and the arguments.

The protocol-level selection has now been translated into a game-domain request.

## 5. Domain Execution

The server passes the action to `GameActionProcessor.processWithAudiences`.

The processor synchronizes execution around the nucleus state lock. This keeps the action's immediate domain changes and the calculation of interested characters for its facts within the same synchronized operation.

Before dispatching the action, the processor checks whether the character has an active blocking activity. Unless the requested action is `cancel_activity`, a character blocked by such an activity is rejected.

The processor then resolves the action name against its registered handlers. Unknown action names are rejected rather than treated as successful operations.

The selected handler applies the relevant game rules and interacts with the nucleus and its domain systems.

For example, `LookAroundHandler` and `ExamineHandler` verify the character's current world location and delegate discovery logic to the nucleus's `ExplorationSystem`. That system validates the requested operation, updates the character's exploration state, and returns a typed operation result.

The handler translates that result into a `GameActionResult`. Successful exploration operations also produce domain `GameFact` values describing events such as an area being observed or an element being discovered.

A game fact is neither a protocol message nor a presentation block. It describes something that happened within the game domain. The server decides how the result and its facts are encoded and delivered.

Actions may complete immediately, be rejected, or start an activity that continues after the initiating request. These outcomes follow different response paths.

## 6. Fact Audiences and Event Distribution

For an action execution, the processor calculates which characters are considered interested in each emitted fact.

The current audience mechanism is deliberately simple and relies on location information available in fact arguments. It is not a complete visibility, permission, or interest-management system.

The server converts domain facts into protocol-level `ProtocolFact` values. Fact argument values are converted to strings because the current protocol representation uses string-valued maps.

The requesting character receives the direct response to the action. Other connected characters identified as interested in a fact may receive separate `ServerEvent` messages.

The server can exclude the actor from this event distribution when that character is already receiving the direct action result.

These messages have different purposes. A response answers the request that was just made, while an event informs another session about a fact it is considered interested in. Not every fact is distributed to every player.

## 7. Returning the Result

For a successful immediate action, the server builds a `ServerCompleted` message containing protocol facts and correlation metadata linking the response to the initiating request.

For a rejected action, it builds `ServerRejected` with the rejection reason. Other outcomes, including activity initiation and failures, follow their corresponding response paths.

The server then distributes applicable fact audiences and prepares the requesting character's available interactions again.

The refreshed presentation can include the character's current location, movement destination, active activities, cancellation options, and the latest exploration result.

`InteractionPresentationPreparer` converts this information into a protocol presentation document containing blocks and choices. It assigns or preserves shortcuts, advances the presentation revision, and supplies the choices that the server stores as the current offers for that session.

The server sends the resulting presentation snapshot to the client.

The client receives protocol messages and updates its local presentation state. It displays the updated information and the available actions.

For exploration, this refresh is essential: when an element becomes discovered in the domain, its examination interaction can appear in the next offered action list.

The client does not infer success simply because the player selected an action. It reflects the server's responses and presentation data.

## 8. Correlation, Sequencing, and Presentation Revisions

Protocol messages carry metadata such as message ID, session ID, sequence, and, where applicable, correlation ID.

Message IDs identify individual requests. Responses use correlation IDs to associate the response with the initiating request.

Inbound sequence tracking helps the server reject out-of-order or otherwise unacceptable messages for an established session.

Presentation documents also carry revisions. The server advances the offer state's revision when preparing a new presentation and stores the associated choices. Subsequent selections are resolved against the server's current offers rather than trusted solely because a choice appears in the client's local interface.

The protocol also defines support for resynchronization and presentation patches. However, the existence of these message types does not mean every update uses a patch. The action and interaction refresh paths described here send presentation snapshots.

## 9. Long-Running Activities and Time

An action may start an activity rather than finish immediately.

In that case, the server records a correlation between the initiating message and the activity, sends `ServerAccepted`, and refreshes the presentation to include active activity details and available cancellation choices.

As time advances through the domain's time-processing path, the domain updates world and activity state and may produce lifecycle facts. The server can distribute those facts and refresh the presentation with updated activity progress.

Cancellation remains a request rather than a client-side decision. The server resolves the cancellation interaction, and the domain validates ownership, activity status, and cancellability before changing state.

This lifecycle differs from the current exploration operations, which complete during a single domain action execution.

## 10. Errors and Cleanup

Errors can occur at several boundaries.

Protocol messages may be discarded before reaching the domain. Unknown or expired choices, missing pending input, blank arguments, unknown action names, invalid exploration requests, and actions blocked by an active blocking activity can result in rejection responses.

When a connection ends, the server finalizes the connection and session lifecycle and removes the temporary character and its corresponding spatial world entity from the nucleus.

Session-scoped state, including pending input and interaction offers, is cleaned up through the relevant lifecycle paths. The exploration system also removes the temporary character's in-memory exploration state.

Current discoveries are therefore not durable across removal of the temporary character.

## 11. Responsibility Boundaries

**Client:** Collects player input and renders server-provided state and choices. It does not own authoritative game state.

**Communication protocol:** Defines serializable messages, metadata, validation contracts, protocol facts, and presentation structures shared by the endpoints.

**Server:** Manages connections and sessions, validates and resolves offered choices, collects pending arguments, dispatches domain actions, maps results and facts into protocol messages, distributes events, and prepares presentation documents.

**Game domain:** Owns game rules and runtime game state. It validates actions through handlers, changes world and exploration state, and produces domain results and facts.

**World library:** Provides the spatial world structures and movement mechanics used by the domain.

The domain does not need to know whether an action arrived through WebSockets or how its result will be displayed by a particular client. The server translates between the protocol's interaction and presentation contract and the domain's game-action and fact model.

This separation allows new gameplay systems and content to be introduced without making the domain dependent on networking or client presentation details.