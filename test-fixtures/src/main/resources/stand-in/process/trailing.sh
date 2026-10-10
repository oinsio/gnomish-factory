#!/bin/sh
# Speaks, and leaves a holder that speaks once more two seconds later, after this process exited
# half a second in (CaptureRunnerDrainSpec).
( sleep 2; echo from-the-holder ) &
echo from-the-parent
sleep 0.5
