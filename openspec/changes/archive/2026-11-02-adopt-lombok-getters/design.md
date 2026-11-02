## Context

- Eight JPA entities (`catalog.entity`: `Dealership`, `ServiceType`, `ServiceBay`, `Technician`,
  `TechnicianSkill`; `customer.entity`: `Customer`, `Vehicle`; `appointment.entity`: `Appointment`)
  expose state through hand-written getters only: no setters, a protected no-argument constructor
  for JPA, and factories where the application creates rows (`Appointment.confirmed(...)`).
- DTOs are Java records with static `of()` mappers; services use explicit constructors.
- `pom.xml` has no annotation processor. The build runs on JDK 23 (`--release 21`). Since JDK 23,
  `javac` runs annotation processors only when they are configured explicitly (processor path or
  `-proc:full`), not merely found on the class path.
- Spring Boot 4.1.2 manages `org.projectlombok:lombok` 1.18.48, which supports JDK 23.

## Goals / Non-Goals

**Goals:** remove getter boilerplate from entities with no change in their public API or behaviour;
make the build run Lombok on every JDK the project uses; write down the boundary so later changes
do not drift into `@Data` or builders.

**Non-Goals:** setters, `@Data`/`@Value`/`@EqualsAndHashCode`/`@ToString`/`@Builder`, Lombok on DTOs
or services, MapStruct (see the proposal).

## Decisions

### 1. Dependency and build

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
```

- `maven-compiler-plugin` gets `annotationProcessorPaths` with `lombok` (version from the Boot
  BOM via `${lombok.version}`), so `javac` on JDK 23+ runs it, and only it.
- `spring-boot-maven-plugin` excludes `lombok` from the repackaged jar; it is needed only at compile
  time.
- `lombok.config` at the project root: `config.stopBubbling = true` (no configuration from parent
  directories) and `lombok.addLombokGeneratedAnnotation = true` (coverage tools skip generated
  getters).

Alternative: `-proc:full` instead of a processor path. Rejected: it runs every processor on the
class path, including any that a future dependency brings in.

### 2. Where `@Getter` goes

- Class-level `@Getter` on `Dealership`, `ServiceType`, `ServiceBay`, `Technician`, `Customer`,
  `Vehicle` and `Appointment`. It generates exactly the getters that exist today, with the same names
  (`isActive()` for `boolean`, `getId()` for `Long`). `Appointment` additionally gains
  `getVersion()`, which is harmless and read-only.
- `TechnicianSkill`: field-level `@Getter` on `active` only. `getTechnicianId()` and
  `getServiceTypeId()` read the embedded id and stay hand-written, and class-level `@Getter` would add
  an unneeded public `getId()` returning the key record.
- Javadoc that lived on a getter moves to its field. Only `Dealership.timeZone` has one, and it is
  already on the field.

### 3. The boundary, as a project rule

Added to `AGENTS.md` and `openspec/config.yaml`: on JPA entities, Lombok is used for `@Getter`
only. No `@Setter`, because state changes go through factories and conditional updates. No
`@Data`/`@EqualsAndHashCode`, because a hash over mutable fields breaks entities in sets and
Hibernate's identity. No `@ToString`, which reads every field. No `@Builder`, which bypasses
factory invariants. DTOs stay records.

## Concurrency, metrics and logs

Unaffected: the generated getters are byte-for-byte equivalent accessors. Booking, locking,
retries, metrics and log events do not change, and the existing suite (204 tests, including the
concurrency tests) is the regression check.

## Risks / Trade-offs

- [The IDE does not run Lombok and shows "method undefined" errors] → VS Code's Java extension and
  IntelliJ bundle Lombok support. Maven is the reference build, and the verification uses
  `./mvnw clean test`.
- [A later change adds `@Data` or `@Builder` out of habit] → The rule is written in `AGENTS.md`,
  which every agent loads.
- [Lombok lags behind a new JDK] → The version comes from the Spring Boot BOM and moves with Boot
  upgrades. The build fails loudly rather than silently if Lombok cannot run.

## Migration Plan

Build-only change: no schema or configuration migration. Rollback means reverting the commit.

## Open Questions

None.
