
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

project("prop_override")

# --- prop_override ---
add_library(prop_override SHARED prop_override.cpp)
target_link_libraries(prop_override android log)

# --- doom ---
file(GLOB DOOM_SOURCES "doom/*.c")

# Exclude all platform-specific backends - we provide our own via doom_jni.cpp
list(FILTER DOOM_SOURCES EXCLUDE REGEX
    "doomgeneric_(sdl|emscripten|win|xlib|allegro|soso|sosox|linuxvt)\\.c$")
list(FILTER DOOM_SOURCES EXCLUDE REGEX
    "i_(sdl|allegro)(sound|music)\\.c$")

add_library(doom SHARED
    ${DOOM_SOURCES}
    doom_jni.cpp
)

target_include_directories(doom PRIVATE doom)

target_compile_options(doom PRIVATE
    -O2
    -DNORMALUNIX
    -DLINUX
    -w
)

target_link_libraries(doom android log jnigraphics)
