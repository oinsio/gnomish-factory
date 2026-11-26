# Work stage instructions

The `service` program submits the job in `start` and answers `pending` with the job's key; its polls
answer `pending` until the job ends, then write the job's change (`new-1.txt`) and answer
`completed`. The stage's only check is that change, so the round passes only with the service's
work in it.
