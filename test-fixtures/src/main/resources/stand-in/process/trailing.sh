#!/bin/sh
# Speaks and exits at once, leaving a holder of its stdout that speaks once more two seconds later
# and then ends (CaptureRunnerDrainSpec): the real-process twin of the post-exit fake, asserting
# only that a holder which ends on its own never flags a clean exit. No linger before the exit —
# the order of the drain's first read and the exit is the spec's to fix through FakeProcess.
( sleep 2; echo from-the-holder ) &
echo from-the-parent
