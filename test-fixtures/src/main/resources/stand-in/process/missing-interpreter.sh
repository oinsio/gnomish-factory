#!/nonexistent/gnomish-interpreter
# An executable script whose interpreter does not exist: the launch owner's lookup follows the
# interpreter line and refuses the launch before any process exists, as start() did before the
# launcher (HostProcessLaunchSpec, ExecutableLookupSpec, GitExecLaunchFailureSpec).
exit 0
