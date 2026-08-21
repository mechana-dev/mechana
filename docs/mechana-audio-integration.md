# Mechana and Mechana Audio integration boundary

Production audio DSP and products live in the private
[`mechana-dev/mechana-audio`](https://github.com/mechana-dev/mechana-audio)
repository. Mechana owns distributed orchestration: coordinator, workers,
sandboxing, storage and artifacts, the plugin SDK, and the Job Launcher.

This repository intentionally retains only the pure-Java distributed convolution
reverb plugin as a platform example. It does not share source with the production
native engine.

The planned `mechana-plugin-audio` adapter will be one generic Java plugin. It
will discover installed effects and parameter schemas through Mechana Audio's
public contract, expose them in the Job Launcher, invoke offline rendering, and
publish the resulting artifacts. It will contain no DSP and will not require a
code change for every newly installed effect.

The target public surfaces are a stable native C ABI for real-time use, a
language-neutral CLI/JSON offline renderer, and a possible future local service
API. These are contract directions, not claims that all surfaces are implemented.
