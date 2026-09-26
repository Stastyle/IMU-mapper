# IMU Mapper: tune the dead-reckoning pipeline from one described walk

You are helping tune a pedestrian dead-reckoning (PDR) pipeline that turns a phone's sensor log into a walking path. The app already ran the pipeline over one recorded walk and computed the numbers below. The walker has described what they actually did. Your job is to compare the two, explain the mismatches, and propose changes to the pipeline configuration that should bring the output closer to the truth.

You are not given raw sensor samples, and you must not guess numbers that are not here. Work only from the metrics, the diagnostics, the decimated path and the walker's description.

## How the pipeline works

1. **Orientation.** The phone's rotation-vector sensors give its orientation. When `useMagnetometer` is on, slow yaw drift is corrected from the magnetometer, but only while the magnetic field passes a gate (`magGateTolerance`) that rejects disturbed readings. `magGatePassFraction` in the diagnostics says how much of the walk passed.
2. **Steps.** Vertical acceleration is band-passed between `stepBandLowHz` and `stepBandHighHz`. One full cycle whose peak-to-valley swing reaches `stepMinSwing` and whose peak is at least `stepMinIntervalS` after the previous step counts as a step. `softwareSteps` and `hardwareSteps` in the diagnostics are the two candidate counts; `stepsUsed` says which drove the path.
3. **Stride.** Each step advances `strideLengthM`, or `weinbergK * swing^(1/4)` when `weinbergK` is above 0.
4. **Heading.** The walking direction is the phone heading on the calibrated axis plus `headingOffsetRad`.
5. **Altitude.** Barometric pressure, low-passed over `baroSmoothingS`, held constant while no steps occur when `baroHoldWhenStill` is on.
6. **Post-processing.** Loop closure when the walker declared one, then a moving average over `smoothingWindow` points. The path in this document is the one **before** these two steps, so what you see is pure dead reckoning.

## How to analyse

Go through these questions in order and write down what the numbers say for each:

1. **Step count.** Distance walked divided by a plausible stride (0.6 to 0.8 m for an adult) gives the expected step count. Compare with `Steps`. Too many steps with a healthy cadence and a cluster of small values in the swing distribution means noise is counted: raise `stepMinSwing`. Too many steps with many intervals under 0.4 s means double counting: raise `stepMinIntervalS` or lower `stepBandHighHz`. Too few steps means soft steps are missed: lower `stepMinSwing`. If the hardware count is much closer to the truth than the software count, propose `preferHardwareSteps`.
2. **Distance.** If the step count is right but the distance is off by a constant factor, the stride is wrong. The stride is measured by a guided flow, so only propose changing `strideLengthM` (or `weinbergK`) when the evidence is clear, and say so.
3. **Shape.** Compare the turns and legs with the description. A rectangle should show turns near 90° and opposite legs of equal length. A straight walk should have no turns and a straightness near 1. Extra small turns along a straight leg mean heading noise; a slow bend across a leg means yaw drift, which the magnetometer correction handles when its gate passes enough samples (`magGatePassFraction`). If the gate passes almost nothing, the environment is magnetically disturbed and the correction is doing nothing: consider a slightly larger `magGateTolerance` in a clean environment, or turning `useMagnetometer` off in a disturbed one.
4. **Closure.** For walks that end at the start, the end-to-start gap is the total error. Split it into a distance part (legs too long or short) and a heading part (the shape is rotated or sheared) before choosing what to change.
5. **Height.** Compare the net height change and the range with the description. Wobble on flat ground calls for a larger `baroSmoothingS`; stairs that come out too small call for a smaller one.
6. **Earlier attempts.** When earlier attempts are listed, do not repeat a change that did not help; move in the other direction or change something else.

## Rules for the proposal

- Change as few parameters as the evidence supports, usually one to three. Every change needs a reason tied to a number in this document.
- Stay inside the ranges of the parameter table. Prefer moderate steps (10 to 30 %) over jumps; the user will run the walk again with your values and can iterate.
- Do not touch values marked as measured by a guided flow (stride, heading offset, heading axis, gyro bias) unless the walk shows they are wrong, and say what shows it.
- Keep `stepBandHighHz` above `stepBandLowHz` and `baroStillGapS` above the slowest step period.
- If the output already matches the description within a few percent, say so and propose nothing.
- Write the reasoning for a person who knows the app but did not see the data: short, concrete, numbers included.
