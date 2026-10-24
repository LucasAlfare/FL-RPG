# Exploration System

## 1. Purpose

The exploration system allows characters to gradually discover and understand their surroundings. It uses a content-defined discovery graph to control which elements become known as the player interacts with the environment.

This graph is distinct from the spatial world graph. The spatial graph determines how characters travel between world locations, while the exploration graph determines how characters discover elements within an exploration area.

The current implementation supports the following gameplay loop:

1. A character reaches a world location associated with an exploration area.
2. The character looks around and discovers the area's initial elements.
3. The player examines an element that is already known.
4. The examination reveals previously undiscovered elements connected to that element.
5. The newly discovered elements become available for examination.
6. The player continues exploring the resulting branches of the discovery graph.

## 2. Content Model

### Exploration Areas

An exploration area associates a collection of discoverable elements with a world location.

Each area defines:

- A unique area identifier.
- A display name.
- A reality or nucleus identifier.
- The associated world location identifier.
- A general description.
- Its exploration elements.
- The identifiers of its initial elements.
- Its directed discovery links.

The `ExplorationCatalog` provides lookup by area ID and world location ID. Each location can be associated with at most one area within a catalog.

The current exploration handlers resolve an area through the character's current world location. The reality or nucleus identifier stored in the area is content metadata; it does not, by itself, implement a complete multi-nucleus routing system.

### Exploration Elements

Each exploration element defines:

- A stable element identifier.
- A display name.
- An observation description.
- An examination description.

The name identifies the element in available interactions and discovery lists. The examination description is displayed when the character examines that element.

The observation description is part of the content model but is not currently used by the exploration presentation path. Looking around displays the area's general description and the names of known elements instead.

### Initial Elements

Every non-empty exploration area must define at least one initial element.

These elements become discoverable when the character successfully looks around the area. Repeating the operation does not rediscover elements already known to that character.

All initial element IDs must refer to elements belonging to the same area.

### Discovery Links

A discovery link represents a directed relationship between two elements.

For example, a link from `old_tree` to `hidden_opening` means that examining `old_tree` may reveal `hidden_opening`.

The reverse relationship does not exist automatically. To make the discovery bidirectional, both directed links must be defined.

The graph supports branches, converging paths, and cycles. Each element can be discovered only once per character, even when several examined elements point to it.

Every element in a non-empty area must be reachable from at least one initial element. Area construction rejects unreachable elements, unknown link endpoints, self-links, and duplicate directed links.

Element identifiers must also be unique across the entire catalog.

## 3. Runtime Behavior

### Looking Around

The domain handler verifies that the character is registered in the nucleus and has a world location. It then asks the exploration system to find the area associated with that location.

If no area exists at the current location, the action is rejected.

Otherwise, the system marks the area as observed and adds its initial elements to the character's discovered-element set. It returns the known elements and the subset newly discovered by that operation.

The handler produces an `exploration_area_observed` fact and an `exploration_element_discovered` fact for each newly discovered element.

The server subsequently refreshes the interaction presentation.

### Examining an Element

The examine action identifies the requested element through its `elementId` argument.

The domain verifies that the character is registered, has a current world location, and is examining an element belonging to the exploration area at that location. The element must already have been discovered.

If these conditions are satisfied, the exploration system records the element as examined and follows its outgoing discovery links. Previously unknown targets become discovered.

The operation returns the examined element and the elements newly revealed by the examination.

The handler produces an `exploration_element_examined` fact and an `exploration_element_discovered` fact for every newly discovered target.

The action is rejected when no exploration area exists, the element is unknown, the element belongs to another area, or the element has not been discovered.

Re-examination is permitted. If all outgoing targets are already known, examining the same element again normally produces no new discovery facts.

### Available Interactions

The default interaction provider examines the character's current location.

When an exploration area exists there, it offers:

- `Look around`, which executes `look_around`.
- `Examine: [element name]` for every element already discovered by that character in the area.

Examining an element can therefore change the available action list. Once the domain records a newly discovered element, the server can offer an examination interaction for it during the next presentation refresh.

The client does not decide which elements have been discovered and cannot grant discoveries locally.

## 4. Presentation

The server prepares a presentation snapshot containing available actions and relevant current context.

After a successful look-around operation, the exploration presentation includes the area's general description and a list of known element names.

After a successful examination, it includes the examined element's examination description and, when applicable, a list of newly discovered elements.

The presentation uses the latest transient exploration result associated with the character's current location. It is not a complete exploration journal.

When the character moves to another location, the previous result is not used as the exploration result for that new location.

## 5. Runtime State and Lifetime

The exploration system maintains the following per-character state in memory:

- Observed area IDs.
- Discovered element IDs.
- Examined element IDs.
- The most recent transient exploration result.

Access to this state is synchronized within the exploration system. The nucleus and action processor also coordinate domain operations through the nucleus state lock.

This state is not persisted to a database. The current server creates temporary character identities for sessions, and character cleanup removes the associated exploration state.

Consequently, discoveries are runtime prototype state rather than durable character progression.

The catalog is supplied when the nucleus is constructed. Defining an area does not automatically make it playable: the area must be included in the catalog, and its location ID must match a location present in the configured world.

## 6. Adding Exploration Content

When creating a new area:

1. Choose a stable area ID and an existing world location ID.
2. Define the area's name and general description.
3. Create stable, unique IDs for all elements.
4. Provide each element's name, observation description, and examination description.
5. Select one or more initial elements.
6. Define directed discovery links describing what each element can reveal.
7. Verify that all elements are reachable from the initial elements and that every link references valid elements.
8. Include the area in the `ExplorationCatalog` supplied to the nucleus.
9. Test the complete loop in a running client: look around, examine an initial element, confirm that connected elements become available, and continue through the newly discovered content.

Element IDs are content identifiers, not display labels. Keep them stable when revising prose so that references remain consistent.

## 7. Current Boundaries

The implementation provides a functional foundation for gradual local discovery, but it is not yet a complete exploration framework.

- It discovers elements within an area; it does not automatically discover world locations or create movement connections.
- `observationDescription` is defined but is not currently displayed by the exploration presentation.
- There is no durable exploration journal, collection reward system, achievement integration, or prerequisite-rule framework.
- Discoveries are not persisted across server restarts or temporary-character removal.
- The nucleus/reality association does not implement a complete multi-nucleus universe.
- The latest exploration result is transient presentation context, separate from the character's accumulated discovered and examined sets.

These boundaries describe the current implementation and should not be interpreted as commitments about future gameplay rules.