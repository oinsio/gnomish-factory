# Rule: diagrams

Use Mermaid diagrams in documentation wherever a visual makes understanding easier — for both humans and AI. Mermaid is text: versioned, diffable, readable by AI as source while humans get the render.

## When to add a diagram

- Architecture and component relationships
- Data / control flow between systems
- State machines and lifecycles (task lifecycle, pipeline position)
- Interaction sequences (escalation, task claiming, resume)
- Pipeline stage descriptions (see `stage-description.md`)

## Rules

- Mermaid only — no ASCII art, no binary images (not diffable, not readable by AI)
- One idea per diagram; split large diagrams instead of growing them
- A diagram supplements prose, never replaces it — key facts must also exist as text
- Prefer `flowchart`, `sequenceDiagram`, `stateDiagram-v2`; pick the simplest type that fits
- Keep node labels short; put detail in the surrounding text
