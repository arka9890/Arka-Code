cmake_minimum_required(VERSION 3.22.1)
project(terminalpty C)
add_library(terminalpty SHARED terminal_pty.c)
target_link_libraries(terminalpty log)
