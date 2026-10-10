#!/bin/sh
# Never exits on its own, and holds its stdout open through a child of its own: only a deadline or
# a kill ends it (ProcessSupervisorStallSpec).
sleep 600
