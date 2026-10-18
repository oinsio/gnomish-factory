#!./hop-2.sh
# Hop 1 of an interpreter chain, its interpreter named relative to the working directory — this
# directory — as the kernel opens it: hop-1 → … → hop-4 → missing-interpreter.sh, whose own
# interpreter is absent, so from here the missing interpreter is the chain's fifth
# (ExecutableLookupSpec).
exit 0
