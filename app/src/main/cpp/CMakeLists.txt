cmake_minimum_required(VERSION 3.22.1)
project(gamejs LANGUAGES C CXX)
add_library(gamejs SHARED gamejs.cpp quickjs/quickjs.c quickjs/dtoa.c
    quickjs/libregexp.c quickjs/libunicode.c quickjs/cutils.c)
target_compile_definitions(gamejs PRIVATE _GNU_SOURCE CONFIG_VERSION="2026-06-04")
target_compile_options(gamejs PRIVATE -O2 -fvisibility=hidden)
set_target_properties(gamejs PROPERTIES C_STANDARD 11 CXX_STANDARD 17)
target_link_libraries(gamejs m)
