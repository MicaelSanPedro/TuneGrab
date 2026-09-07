cmake_minimum_required(VERSION 3.22)
project(tunegrab_lame C)

set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lame)

add_library(tunegrab_lame SHARED
        mp3encoder.c
        ${LAME_DIR}/VbrTag.c
        ${LAME_DIR}/bitstream.c
        ${LAME_DIR}/encoder.c
        ${LAME_DIR}/fft.c
        ${LAME_DIR}/gain_analysis.c
        ${LAME_DIR}/id3tag.c
        ${LAME_DIR}/lame.c
        ${LAME_DIR}/newmdct.c
        ${LAME_DIR}/presets.c
        ${LAME_DIR}/psymodel.c
        ${LAME_DIR}/quantize.c
        ${LAME_DIR}/quantize_pvt.c
        ${LAME_DIR}/reservoir.c
        ${LAME_DIR}/set_get.c
        ${LAME_DIR}/tables.c
        ${LAME_DIR}/takehiro.c
        ${LAME_DIR}/util.c
        ${LAME_DIR}/vbrquantize.c
        ${LAME_DIR}/version.c
        )

target_include_directories(tunegrab_lame PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${LAME_DIR}
        )

# Mesmos defines validados no teste de host (scripts/lame_host_test.c)
target_compile_definitions(tunegrab_lame PRIVATE
        HAVE_STDINT_H
        HAVE_INTTYPES_H
        STDC_HEADERS
        ieee754_float32_t=float
        )

# LAME é C puro: sem STL; -O2 para encode mais rápido no aparelho
target_compile_options(tunegrab_lame PRIVATE -O2)
set_target_properties(tunegrab_lame PROPERTIES C_STANDARD 99)
