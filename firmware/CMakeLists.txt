cmake_minimum_required(VERSION 3.22)

# without this the version comes from git, and the same source gives different images
set(PROJECT_VER 1)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
idf_build_set_property(MINIMAL_BUILD ON)
project(fidont)

# the app flashes only the image with this hash
add_custom_target(pin ALL COMMAND ${CMAKE_COMMAND} -E sha256sum merged-binary.bin > ${CMAKE_SOURCE_DIR}/sha256)
add_dependencies(merge-bin partition_table_bin blank_ota_data)
add_dependencies(pin merge-bin)
