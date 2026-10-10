# 2000 lines of 100 characters on each stream, then a clean exit.
line=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
i=0
while [ $i -lt 2000 ]; do
    echo "$line"
    echo "$line" 1>&2
    i=$((i + 1))
done
exit 0
