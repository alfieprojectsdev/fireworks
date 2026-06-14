cmake_minimum_required(VERSION 3.22.1)
project(anniversary_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    anniversary_native
    SHARED
    particle_engine.cpp
    particle_step.cpp
)

target_include_directories(anniversary_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)

target_link_libraries(
    anniversary_native
    ${log-lib}
)
