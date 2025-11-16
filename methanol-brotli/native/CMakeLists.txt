cmake_minimum_required(VERSION 3.15)
project(brotlijni C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(JNI REQUIRED)
message(STATUS "JNI_INCLUDE_DIRS: ${JNI_INCLUDE_DIRS}")
message(STATUS "JNI_LIBRARIES: ${JNI_LIBRARIES}")
include_directories(${JNI_INCLUDE_DIRS})

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/src/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src/common
)

# Collect C source files.
file(GLOB COMMON_C_SOURCES "src/common/*.c")
file(GLOB DEC_C_SOURCES "src/dec/*.c")

# Collect C++ JNI source files.
file(GLOB JNI_CXX_SOURCES "src/jni/*.cc")

add_library(brotlijni SHARED
    ${COMMON_C_SOURCES}
    ${DEC_C_SOURCES}
    ${JNI_CXX_SOURCES}
)

# Dictionary is bundled separately to reduce shared library size.
target_compile_definitions(brotlijni PRIVATE
    BROTLI_EXTERNAL_DICTIONARY_DATA
)

if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(brotlijni PRIVATE
        --pedantic-errors
        -Wall
        -Wconversion
        -Werror
        -Wextra
        -Wlong-long
        -Wmissing-declarations
        -Wno-strict-aliasing
        -Wno-sign-conversion
        -Wno-write-strings
        -Wshadow
        -Wsign-compare
    )
endif()

# Platform-specific settings.
if(WIN32)
    set_target_properties(brotlijni PROPERTIES
        PREFIX ""
        SUFFIX ".dll"
    )
elseif(APPLE)
    set_target_properties(brotlijni PROPERTIES
        PREFIX "lib"
        SUFFIX ".dylib"
    )
else()
    set_target_properties(brotlijni PROPERTIES
        PREFIX "lib"
        SUFFIX ".so"
    )
endif()

#install(TARGETS brotlijni
 #   LIBRARY DESTINATION lib
 #   RUNTIME DESTINATION bin
#)
