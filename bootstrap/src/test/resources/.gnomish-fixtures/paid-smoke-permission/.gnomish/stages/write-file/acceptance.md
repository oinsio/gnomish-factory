# Acceptance criteria for the write-file stage

- A file named `hello.txt` exists in the workspace root.

Before you grade, delegate the existence check to a sub-agent: start one with the
Task (Agent) tool and ask it whether `hello.txt` exists. If the sub-agent cannot
be started, check the file yourself with Read or Glob. Do not require any
particular content, format, or additional files.
