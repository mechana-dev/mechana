# Glossary

| Term | Meaning |
| --- | --- |
| Artifact | Identified input, intermediate, partition, or final output accessed through the artifact abstraction. |
| Assembler | Plugin component or contract operation that validates and combines the complete ordered work-unit result set into a final artifact. |
| Control plane | Job, scheduler, lease, capability, progress, reservation, and artifact-metadata traffic. |
| Data plane | Movement or storage of artifact bytes. |
| Artifact fabric | Platform responsibility for identifying, locating, staging, validating, transferring, caching, assembling, retaining, and cleaning up artifacts across storage providers. |
| Storage provider | Adapter that persists or transfers artifact bytes; input, intermediate, and output roles may use different providers. |
| Execution model | The constrained `plan -> parallel work units -> assemble` lifecycle; not a generic DAG. |
| Parallel execution plan | Deterministic plugin-produced description of independent work units, their resource estimates, and explicit assembly requirements. |
| Partition | Independently executable unit emitted by planning. |
| Plan | Deterministic conversion of validated job input into partition and assembly requirements. |
| Plugin | Domain extension encapsulating the complete computational contract—descriptions, options, validation, planning, estimation, execution, assembly, and result validation—while Mechana owns platform lifecycle and placement. |
| Processing options | Versioned, plugin-described configuration values that specialize a computation and whose combinations the plugin authoritatively validates. |
| Result validator | Plugin operation that authoritatively checks an assembled final result before Mechana accepts it. |
| Runtime signature | Normalized execution profile that must match across all partitions of an initial video job. |
| Plugin runtime manager | Worker-owned control boundary that resolves policy, launches or reuses a compatible runtime, monitors attempts, delivers cancellation, and guarantees cleanup. |
| Sandbox runtime | Platform-specific adapter that applies and verifies the execution policy before plugin and native-tool code runs. |
| Trusted execution | Operator-approved execution with no hostile-code isolation; it may run in process. |
| Managed execution | Separate-process lifecycle and fault isolation that is not, by itself, a security sandbox. |
| Sandboxed execution | Separate execution under verified OS-enforced controls represented by the host's guarantee matrix. |
| Guarantee matrix | Versioned, platform-specific statement of sandbox controls that are actually enforced and verified. |
| Scratch reservation | Temporary scheduler accounting that commits worker local capacity to an assignment. |
| Stale completion | Result from an expired or superseded lease; never authoritative. |
| Work unit | Independently executable item emitted by a parallel execution plan and run under a Mechana-controlled attempt; also called a partition in existing design text. |
