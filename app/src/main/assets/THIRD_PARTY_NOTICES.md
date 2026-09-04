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

## QEMU x86_64 user-mode emulator

Starting with multi-container support, Lindroid bundles QEMU 11.0.3
(qemu-x86_64, aarch64 host build) from the official Termux package repository
so x86_64 containers can run through the PRoot `-q` user-mode emulator. QEMU is
licensed under GPL-2.0-or-later. Source:
https://github.com/qemu/qemu/tree/v11.0.3

Package SHA-256:

- qemu-user-x86-64 11.0.3: `92623b49e6c470fe59f54ab3d1ed876378c59f21f5b2a6a77ba0e59639a5ada4`

The emulator's runtime libraries also come from the official Termux package
repository (LGPL-2.1+ and other permissive licenses, per upstream project):

- glib 2.88.3: `b1bfc40c4cfde83470de9efac1e546fdf643b77a966c20a8612bddc7c59cd3dc`
- libdw 0.193-1: `9b24d896aba9aa306b19c2dff9697074194ab6abeb9821b7f59ff5fe7e466b68`
- libelf 0.193-1: `98fba46ed571c59d38269e5e0c36a7555335ed3546dc9eb0ea5889763e66e621`
- libgnutls 3.8.13-1: `eb12f2cf82923caf288edd99bc4c19a9dd0cf1de4a03855ff4457a1bbdae146d`
- libpixman 0.46.4-1: `364b8c82ff68f8513930e5d413459fb7c52eccc7ba75a97a8c5a38760eee8d33`
- pcre2 10.47: `51f915d22de639bfca6ec029ae613987bbe3bc73626eede13319fd2e95f50b63`
- libiconv 1.18-1: `b19e6f348034bb48d2a5590b5cb242769f682c476717374d134d004cc663dc84`
- libandroid-support 29-1: `f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517`
- argp 1.5.0-1: `4096bfb8cba379efc3699e94e1cee400e72c274d734b5c1ed5f274ec42003405`
- zstd 1.5.7-1: `e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3`
- liblzma 5.8.3: `594925a313879f590fbd24050305551a78eadd9a9319f6e612389b1a521113c6`
- libbz2 1.0.8-8: `4335d7f060650b0aabef545d1334c2f9f280223d5962e13c24a00ec934b794ba`
- p11-kit 0.26.5: `7911d7427e3c7182cf364e56a45a92307e5e7fddc105dc468f25ae39aa3e0ad7`
- libidn2 2.3.8-1: `a450a1ba25759ebf78738484a3efee316c51a1fe7bafb0b01a68e2c058a91020`
- libunistring 1.4.2: `5ff75cdf3ddd4ddf5dc9705050f270c3422820295a4b26f93496d3e8e9060122`
- libtasn1 4.21.0: `1d02de3a7e5ef4ff7d893597091176b8ddf135f1ddfacff951f76c18711a7342`
- libnettle 4.0+really3.10.2: `f2d2a84083e66beab642e88496eb16976ccdb12d1278466e687f01431db8a5fb`
- libgmp 6.3.0-2: `5a3c1325638946ca212ddcb89bffb2c4459b4c90757d6e69f820e176534037fa`
- libffi 3.5.2: `8c8c1d6ffb049d8496a21c1202d9b4dc9145140886fdbb45716684565f4ed3f5`

Soname references inside these ELF binaries were rewritten in place from
versioned names (for example `libglib-2.0.so.0`) to the APK-compatible
unversioned names; program behavior is otherwise unchanged.
