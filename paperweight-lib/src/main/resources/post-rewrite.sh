#!/bin/bash
tags=("ATs" "Imports" "file")
declare -A tagCommits

# get the current commit for each tag
for tag in "${tags[@]}"; do
    tagCommits["$tag"]=$(git rev-list -n 1 "$tag")
done

while IFS= read -r line; do
    # <old-object> SP <new-object> [SP <extra-info>] LF
    oldObject=$(echo "$line" | cut -d' ' -f1)
    newObject=$(echo "$line" | cut -d' ' -f2)

    for tag in "${tags[@]}"; do
        if [ "$oldObject" = "${tagCommits[$tag]}" ]; then
            # delete old tag (silent) and add new tag
            git tag -d "$tag" > /dev/null
            git tag "$tag" "$newObject" --no-sign
            echo "Updated tag '$tag' from $oldObject to $newObject"
        fi
    done

    # if we have updated the file tag, we can stop, no tags after that
    if [ "$oldObject" = "${tagCommits["file"]}" ] || [ "$newObject" = "${tagCommits["file"]}" ]; then
        break
    fi
done
