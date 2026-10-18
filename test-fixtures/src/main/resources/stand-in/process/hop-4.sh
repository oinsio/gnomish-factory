#!./missing-interpreter.sh
# The last hop of an interpreter chain: its interpreter, relative to the working directory — this
# directory — is missing-interpreter.sh, a script whose own interpreter is absent, so the missing
# interpreter is the chain's second (ExecutableLookupSpec).
exit 0
