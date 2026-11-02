## Why

The JPA entities carry hand-written getters only: `Appointment` alone has 14 (about 45 lines), and the
eight entities together repeat the same pattern. That boilerplate adds nothing a reader needs and
grows with every column. Lombok's `@Getter` removes it without the risks that make Lombok a poor fit
for JPA elsewhere. Serves maintainability, a "build for the future" concern of the brief.

## What Changes

- Add Lombok (version managed by Spring Boot 4.1.2: 1.18.48) as an optional, compile-time-only
  dependency, run through an explicit annotation processor path. JDK 23 and later no longer run
  annotation processors found on the class path by default, and this machine builds with JDK 23.
- Replace the hand-written getters of the JPA entities with `@Getter`: `Dealership`, `ServiceType`,
  `ServiceBay`, `Technician`, `Customer`, `Vehicle`, `Appointment`, and the `active` field of
  `TechnicianSkill` (whose other two getters read the embedded id and stay hand-written).
- Record the rule in `AGENTS.md` and `openspec/config.yaml`: on entities, Lombok is limited to
  `@Getter`. DTOs stay Java records, and there is no MapStruct.
- Keep Lombok out of the runnable jar.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. No externally observable behaviour changes: the same getters exist with the same names
(`isActive()` for the `boolean` flags), so the change has no spec delta. It is archived with
`openspec archive --skip-specs`, the option OpenSpec provides for tooling changes; inventing a
requirement about Lombok would break the rule that specs describe externally observable behaviour.

## Non-goals

- `@Setter`, `@Data`, `@Value`, `@EqualsAndHashCode`, `@ToString` and `@Builder`. Entities have no
  setters, because state changes go through factories and conditional updates. Generated
  `equals`/`hashCode` over every field breaks Hibernate's identity. A builder would bypass the invariants
  of `Appointment.confirmed(...)` (always `CONFIRMED`, random id, timestamps).
- Lombok on DTOs and records, which already have their accessors.
- MapStruct: mappings convert time zones and combine several sources, so the static `of()` methods
  are shorter and type-checked.
- `@RequiredArgsConstructor` on services. Explicit constructors stay, which keeps this change to
  entities.

## Assumptions

Also recorded in `docs/SYSTEM_DESIGN.md`:

- The Lombok rule (entities: `@Getter` only) applies to every later change; widening it needs a new
  proposal.

## Impact

- `pom.xml`: the `lombok` dependency (optional), a `maven-compiler-plugin` annotation processor
  path, and an exclusion in `spring-boot-maven-plugin`.
- New `lombok.config` at the project root (`config.stopBubbling`,
  `lombok.addLombokGeneratedAnnotation`).
- Eight entity classes lose their getter bodies; `Appointment` additionally gains `getVersion()`.
  The public API of every entity is otherwise unchanged.
- `AGENTS.md`, `openspec/config.yaml`, `docs/SYSTEM_DESIGN.md`, `docs/AI_LOG.md`.
- No schema, API, metric or behaviour change.
- IDE: VS Code's Java extension and IntelliJ support Lombok without extra setup.
