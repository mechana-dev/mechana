Mechana Reverb — Starter Impulse Responses
===========================================

Included profiles
-----------------

- small-room-ir.wav: shortest, least pronounced room sound
- medium-room-ir.wav: moderate room ambience
- large-room-short-ir.wav: larger space with a restrained tail
- large-stone-church-ir.wav: long, spacious church effect
- vocal-plate-ir.wav: synthetic plate-style effect
- scott-rvb-first-pass-ir.wav: Scott's first measured hardware-reverb profile

These synthetic development profiles are starting points. Use the Impulse
response WAV chooser in Mechana Reverb to select any profile in this folder or a
compatible IR WAV stored anywhere else on the Mac.

Using a hardware reverb
-----------------------

1. Play the exact standardized Mechana logarithmic sweep through the hardware
   reverb at 100% wet.
2. Record the stereo wet return without trimming it, including the complete tail.
3. Keep the sweep and recording at the same sample rate (48 kHz is recommended).
4. Open Create IR from Sweep in this app and select the original sweep plus the
   recorded wet return to create the actual impulse-response WAV.
5. Select that deconvolved WAV as the Impulse response WAV in Mechana Reverb.

Do not select the raw recorded sweep return as the IR. Generate the profile in
Create IR from Sweep first, then select the resulting WAV in Apply Reverb.

Supported inputs are mono or stereo 16-bit PCM, 24-bit PCM, or 32-bit IEEE-float
WAV. The dry WAV and IR WAV must have matching sample rates.
