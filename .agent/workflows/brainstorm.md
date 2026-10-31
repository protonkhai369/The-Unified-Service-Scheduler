---
description: Structured brainstorming for projects and features. Explores multiple options with testability and failure mode evaluation before implementation.
---

# /brainstorm - Structured Idea Exploration

$ARGUMENTS

---

## Purpose

This command activates BRAINSTORM mode for structured idea exploration. Use when you need to explore options before committing to an implementation, evaluating testability and risk profiles upfront.

---

## Behavior

When `/brainstorm` is triggered:

1. **Understand the goal**
   - What problem are we solving?
   - Who is the user?
   - What constraints exist?

2. **Generate options (Adaptive TDD perspective)**
   - Provide at least 3 different approaches
   - Analyze pros and cons for each
   - Evaluate **testability & failure modes** (isolation, mock boundaries, edge cases)
   - Consider unconventional solutions

3. **Compare, assess testability & recommend**
   - Summarize tradeoffs including testing & maintenance effort
   - Identify core test scenarios for the recommended approach
   - Give a clear recommendation with reasoning

---

## Output Format

```markdown
## 🧠 Brainstorm: [Topic]

### Context
[Brief problem statement]

---

### Option A: [Name]
[Description]

✅ **Pros:**
- [benefit 1]
- [benefit 2]

❌ **Cons:**
- [drawback 1]

🧪 **Testability & Risk Profile:**
- **Test Isolation:** High | Medium | Low (easy to unit test vs requires complex mocking)
- **Key Risks / Failure Modes:** [failure mode or edge case 1], [failure mode 2]
- **Key Test Scenarios:**
  - *Scenario 1 (Happy Path):* When [input/action] -> Expect [output/state]
  - *Scenario 2 (Edge/Error):* When [invalid condition] -> Expect [error handling]

📊 **Effort & Complexity:** Low | Medium | High

---

### Option B: [Name]
[Description]

✅ **Pros:**
- [benefit 1]

❌ **Cons:**
- [drawback 1]
- [drawback 2]

🧪 **Testability & Risk Profile:**
- **Test Isolation:** High | Medium | Low
- **Key Risks / Failure Modes:** [risk 1]
- **Key Test Scenarios:**
  - *Scenario 1:* When [action] -> Expect [result]

📊 **Effort & Complexity:** Low | Medium | High

---

### Option C: [Name]
[Description]

✅ **Pros:**
- [benefit 1]

❌ **Cons:**
- [drawback 1]

🧪 **Testability & Risk Profile:**
- **Test Isolation:** High | Medium | Low
- **Key Risks / Failure Modes:** [risk 1]
- **Key Test Scenarios:**
  - *Scenario 1:* When [action] -> Expect [result]

📊 **Effort & Complexity:** Low | Medium | High

---

## 💡 Recommendation

**Option [X]** because [reasoning].

### 🎯 Recommended Test & Verification Strategy (Adaptive TDD)
- **Strict TDD (Red-Green-Refactor):** [Core services, business logic, auth, API endpoints]
- **Adaptive / Contract Verification:** [UI components, configuration, styling]
- **Primary Scenarios to Validate First:**
  1. [Scenario 1]
  2. [Scenario 2]

What direction would you like to explore?
```

---

## Examples

```
/brainstorm authentication system
/brainstorm state management for complex form
/brainstorm database schema for social app
/brainstorm caching strategy
```

---

## Key Principles

- **No code** - this is about ideas, architecture, and verification strategy, not code implementation
- **Testability upfront** - evaluate how each option will be tested and proven before building
- **Adaptive TDD awareness** - differentiate between high-risk logic (strict TDD) and presentation/configs (lean verification)
- **Visual when helpful** - use diagrams for architecture and data flow
- **Honest tradeoffs** - don't hide testing or operational complexity
- **Defer to user** - present options, let them decide
