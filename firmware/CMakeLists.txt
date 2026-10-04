cmake_minimum_required(VERSION 3.22)

# without this the version comes from git, and the same source gives different images
set(PROJECT_VER 1)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
idf_build_set_property(MINIMAL_BUILD ON)
project(fidont)
