# Third-party notices

Lindroid bundles PRoot 5.1.107.92 from the official Termux package repository.
PRoot is licensed under GPL-2.0. Source: https://github.com/termux/proot/tree/v5.1.107.92

Official package SHA-256:

- proot 5.1.107.92: `1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9`
- libandroid-shmem 0.7: `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6`
- libtalloc 2.4.3: `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da`

It also bundles libandroid-shmem 0.7 and libtalloc 2.4.3 from the official
Termux package repository. Their licenses and source links are available at:

- https://github.com/termux/libandroid-shmem
- https://talloc.samba.org/

The Debian filesystem is not bundled in the APK. At the user's request,
Lindroid downloads the official `library/debian:12-slim` image from Docker Hub.

Lindroid embeds noVNC 1.7.0 as its local graphical desktop client. noVNC is
licensed under MPL-2.0, with some components under compatible licenses. Its full
license file is included at `assets/novnc/LICENSE.txt`.
Source: https://github.com/novnc/noVNC/tree/v1.7.0
Source archive SHA-256: `b1003a11b6e6e8d8f7f5e5586daae7f8ca651d8aee0aa155ff9ac841c48f52c6`
