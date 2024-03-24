#!/bin/sh
input=$(cat)
fileCommit=$(git rev-list -n 1 file)

IFS=$'\n'
input=($input)
for line in $input; do
    if [[ $line == $fileCommit* ]]; then
        lastElement=${line##* }
        git tag -d file
        git tag file "$lastElement" --no-sign
        echo "Updated tag file from $fileCommit to $lastElement"
    fi
done
