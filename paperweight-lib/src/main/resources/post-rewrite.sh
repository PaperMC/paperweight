#!/bin/sh
input=$(cat)                             # <old-object> SP <new-object> [SP <extra-info>] LF
fileCommit=$(git rev-list -n 1 file)     # current commit tagged as "file"
oldObject=$(echo $input | cut -d' ' -f1) # <old-object>
newObject=$(echo $input | cut -d' ' -f2) # <new-object>

if [ $oldObject = $fileCommit ]; then
    git tag -d file > /dev/null          # delete old tag (silent)
    git tag file "$newObject" --no-sign
    echo "Updated tag 'file' from $oldObject to $newObject"
fi
