# Invoked as `<link> git-upload-pack <path>` by the ext:: transport.
exec git "${1#git-}" "$2"
