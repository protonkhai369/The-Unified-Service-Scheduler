## 1. Build

- [x] 1.1 Add `org.projectlombok:lombok` (optional, version from the Boot BOM), a `maven-compiler-plugin` `annotationProcessorPaths` entry for it, the `lombok` exclusion in `spring-boot-maven-plugin`, and `lombok.config` (`config.stopBubbling`, `lombok.addLombokGeneratedAnnotation`); confirm `./mvnw clean compile` succeeds on JDK 23

## 2. Entities

- [x] 2.1 Replace the hand-written getters with class-level `@Getter` on `Dealership`, `ServiceType`, `ServiceBay`, `Technician`, `Customer`, `Vehicle` and `Appointment`, and field-level `@Getter` on `TechnicianSkill.active` (its id-derived getters stay); keep constructors, factories and field Javadoc
- [x] 2.2 Check with `javap` that every entity still exposes the same getter names as before (plus `Appointment.getVersion()`), so callers and Jackson-free mappers are unaffected

## 3. Rules and documentation

- [x] 3.1 Add the Lombok boundary to `AGENTS.md` and the stack in `openspec/config.yaml` (entities: `@Getter` only; no `@Setter`, `@Data`, `@EqualsAndHashCode`, `@ToString`, `@Builder`; DTOs stay records; no MapStruct)
- [x] 3.2 Add the assumption to `docs/SYSTEM_DESIGN.md` and the change to `docs/AI_LOG.md` (why `@Getter` only, why an explicit processor path on JDK 23, why no spec delta)

## 4. Verification

- [x] 4.1 Run `./mvnw clean test` and confirm every test passes (the suite exercises every entity getter); quote the Surefire summary
- [x] 4.2 Run `./mvnw -DskipTests package` and confirm no `lombok` jar is inside `BOOT-INF/lib` of the repackaged jar
