#!./hop-4.sh
# Hop 3 of an interpreter chain, its interpreter named relative to the working directory — this
# directory — as the kernel opens it: hop-1 → … → hop-4 → missing-interpreter.sh, whose own
# interpreter is absent, so from here the missing interpreter is the chain's third
# (ExecutableLookupSpec).
exit 0
