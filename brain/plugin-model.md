# Plugin model

Last reviewed: 2026-08-01

Plugins supply domain behavior while the core supplies lifecycle, scheduling,
artifacts, leases, and observability. The generic lifecycle is:

1. Validate a plugin-owned job specification.
2. Plan deterministic partitions plus explicit assembly requirements.
3. Execute each partition against artifact references and an execution context.
4. Publish partition artifacts only for the current valid attempt/lease.
5. Assemble the ordered compatible partition set into a final artifact.

## Invariants

- The scheduler matches declared capabilities and resources, not plugin payload
  semantics.
- Plugin inputs and outputs cross core boundaries as versioned values and artifact
  references, not assumed shared paths.
- Planning does not execute the workload or move large artifact bytes.
- Retries cannot silently overwrite another attempt's authoritative output.
- Plugin versions and runtime signatures needed for compatibility are explicit.
- Cancellation, timeout, progress, and external-process failure have defined task
  outcomes.

The sleep plugin in the current branch is an implementation slice, not the full
accepted plan/partition/assemble contract. See [media plugin](media-plugin.md) for
the first domain-specific design.
