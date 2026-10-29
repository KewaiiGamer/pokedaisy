/* WILD ENCOUNTERS' switch found in real ROMs, the same answers as the app's WildEncountersTest. ROMs are the
 * player's own: POKEDAISY_ROM_DIR names a folder of them (as for the app's ROM-backed tests); missing ones are
 * skipped, none at all passes with a note. */
#include "wild.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

int main(void) {
    static const struct { const char* file; uint32_t sw; } roms[] = {
        { "Pokemon - Emerald Version (USA, Europe).gba", 0x02038C00 },
        { "Pokemon - FireRed Version (USA).gba", 0x020386DC },
        { "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba", 0x020386DC },
        { "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba", 0x020386DC },
        { "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba", 0x0202FF7C },
        { "Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba", 0x0202FF7C },
        { "Pokemon Unbound (v2.1.1.1).gba", 0x020386DC },
    };
    const char* dir = getenv("POKEDAISY_ROM_DIR");
    int checked = 0;
    for (size_t i = 0; dir && i < sizeof roms / sizeof *roms; i++) {
        char path[1024];
        snprintf(path, sizeof path, "%s/%s", dir, roms[i].file);
        FILE* f = fopen(path, "rb");
        if (!f) continue;
        static uint8_t rom[32 << 20];
        size_t n = fread(rom, 1, sizeof rom, f);
        fclose(f);
        uint32_t got = pk_wild_switch_find(rom, n);
        if (got != roms[i].sw) fprintf(stderr, "%s: %08X, expected %08X\n", roms[i].file, got, roms[i].sw);
        assert(got == roms[i].sw);
        checked++;
    }
    static const uint8_t blank[4096];
    assert(pk_wild_switch_find(blank, sizeof blank) == 0);
    printf("PASS WILD ENCOUNTERS' switch in %d ROM(s)%s\n", checked, checked ? "" : " (no POKEDAISY_ROM_DIR: none checked)");
}
