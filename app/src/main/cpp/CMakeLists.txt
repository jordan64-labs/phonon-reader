cmake_minimum_required(VERSION 3.22.1)
project("phonon_jni")

add_library(
    phonon_jni
    SHARED
    phonon_jni.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    phonon_jni
    ${log-lib}
)
