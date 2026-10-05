# Work stage instructions

Do the work described in the task. A run of this fixture is meant to stop before this stage:
its `external` check names the `github` provider, and the run configures no
`factory.check.github` section, so the pipeline is refused at load.
