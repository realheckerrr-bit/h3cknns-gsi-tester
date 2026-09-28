cmake_minimum_required(VERSION 3.22.1)
project(gsi_runner)

add_library(gsi_runner SHARED src/main/cpp/gsi_runner.cpp)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(gsi_runner ${log-lib} ${dl-lib})
