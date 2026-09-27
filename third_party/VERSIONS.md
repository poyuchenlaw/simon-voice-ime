# Vendored native components

| Component | Upstream tag | Commit | License | Source |
|---|---|---|---|---|
| libchewing | v0.12.0 | 05ae6bcb9309c466a1b32d69c146bc583be04747 | LGPL-2.1-or-later | https://github.com/chewing/libchewing |
| libchewing-data | pinned upstream submodule | c44e81aef24b06f1509f19e1be54c99812d0c43f | LGPL-2.1-or-later (data files identify per-file notices) | https://github.com/chewing/libchewing-data |

The application consumes libchewing as a replaceable shared library. Upstream
source files are not modified. The nested data submodule is pinned by the
libchewing v0.12.0 gitlink.
