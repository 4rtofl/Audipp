cmake_minimum_required(VERSION 3.22.1)
project(audipp_pcm LANGUAGES C)

add_library(audipp_pcm SHARED pcm.c)
target_compile_features(audipp_pcm PRIVATE c_std_11)
target_compile_options(audipp_pcm PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
