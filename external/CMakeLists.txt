project(external)

macro(SET_OPTION option value)
    set(${option} ${value} CACHE INTERNAL "" FORCE)
endmacro()

SET_OPTION(DOBBY_GENERATE_SHARED OFF)
SET_OPTION(Plugin.SymbolResolver OFF)
SET_OPTION(FMT_INSTALL OFF)

set(SYSTEM_PROPERTIES_SOURCES
        context_node.cpp
        contexts_serialized.cpp
        contexts_split.cpp
        prop_area.cpp
        prop_info.cpp
        system_properties.cpp
        system_property_api.cpp
        system_property_set.cpp
        property_info_parser.cpp
)

list(TRANSFORM SYSTEM_PROPERTIES_SOURCES PREPEND system_properties/)

set(SYSTEM_PROPERTIES_FLAGS
        --std=c++17
        -Wno-unused-function
)
set(SYSTEM_PROPERTIES_INCLUDES system_properties/include)
set(SYSTEM_PROPERTIES_EXPORT_INCLUDES ${SYSTEM_PROPERTIES_INCLUDES})

add_library(system_properties STATIC ${SYSTEM_PROPERTIES_SOURCES})
target_compile_options(system_properties PRIVATE ${SYSTEM_PROPERTIES_FLAGS})
target_include_directories(system_properties PUBLIC ${SYSTEM_PROPERTIES_EXPORT_INCLUDES})
target_include_directories(system_properties PRIVATE ${SYSTEM_PROPERTIES_INCLUDES})

link_libraries(system_properties)

set(XZ_EMBEDDED_SOURCES
        xz/xz_crc32.c
        xz/xz_crc64.c
        xz/xz_dec_bcj.c
        xz/xz_dec_lzma2.c
        xz/xz_dec_stream.c
)

list(TRANSFORM XZ_EMBEDDED_SOURCES PREPEND xz-embedded/linux/lib/)

set(XZ_EMBEDDED_FLAGS
        -std=c89
        -DXZ_INTERNAL_CRC32=1
        -DXZ_USE_CRC64=1
        -DXZ_INTERNAL_CRC64=1
)
set(XZ_EMBEDDED_EXPORT_INCLUDES xz-embedded/linux/include/linux)
set(XZ_EMBEDDED_INCLUDES ${XZ_EMBEDDED_EXPORT_INCLUDES} xz-embedded/userspace)

add_library(xz_embedded STATIC ${XZ_EMBEDDED_SOURCES})
target_compile_options(xz_embedded PRIVATE)
target_include_directories(xz_embedded PUBLIC ${XZ_EMBEDDED_EXPORT_INCLUDES})
target_include_directories(xz_embedded PRIVATE ${XZ_EMBEDDED_INCLUDES})

link_libraries(xz_embedded)

OPTION(LSPLANT_BUILD_SHARED OFF)
add_subdirectory(lsplant/lsplant/src/main/jni)
add_subdirectory(dobby)
add_subdirectory(fmt)
target_compile_definitions(fmt-header-only INTERFACE FMT_STATIC_THOUSANDS_SEPARATOR=1 FMT_USE_FLOAT=0 FMT_USE_DOUBLE=0 FMT_USE_LONG_DOUBLE=0)
