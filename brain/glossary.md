# Glossary

| Term | Meaning |
| --- | --- |
| Artifact | Identified input, intermediate, partition, or final output accessed through the artifact abstraction. |
| Assemble | Explicit stage that validates and combines an ordered set of partition artifacts. |
| Control plane | Job, scheduler, lease, capability, progress, reservation, and artifact-metadata traffic. |
| Data plane | Movement or storage of artifact bytes. |
| Partition | Independently executable unit emitted by planning. |
| Plan | Deterministic conversion of validated job input into partition and assembly requirements. |
| Plugin | Domain extension that plans, executes, and assembles work behind generic core contracts. |
| Runtime signature | Normalized execution profile that must match across all partitions of an initial video job. |
| Scratch reservation | Temporary scheduler accounting that commits worker local capacity to an assignment. |
| Stale completion | Result from an expired or superseded lease; never authoritative. |
