#include "pd_settings.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void pd_settings_defaults(struct pd_settings* s) {
    s->screenMode = PD_SCREEN_SHARP;
    s->ffSpeed = PD_FF_MIN;
    s->guideNotice = 0;
    s->aspect = PD_ASPECT_ORIGINAL;
    s->autoZoom = true;
    s->wildOff = false;
    s->tabBar = PD_TAB_BAR_DEFAULT;
    s->hud = true;
    s->hudHidden = 0;
}

static bool int_in(const char* v, int lo, int hi, int* out) {
    char* end;
    long n = strtol(v, &end, 10);
    if (end == v || n < lo || n > hi) return false;
    *out = (int) n;
    return true;
}

bool pd_settings_load(struct pd_settings* s, const char* path) {
    pd_settings_defaults(s);
    FILE* f = fopen(path, "r");
    if (!f) return false;
    char line[128];
    while (fgets(line, sizeof(line), f)) {
        char* eq = strchr(line, '=');
        if (!eq) continue;
        *eq = 0;
        const char* key = line;
        const char* value = eq + 1;
        if (!strcmp(key, "screen")) int_in(value, 0, PD_SCREEN_MODES - 1, &s->screenMode);
        else if (!strcmp(key, "ff")) int_in(value, PD_FF_MIN, PD_FF_MAX, &s->ffSpeed);
        else if (!strcmp(key, "guide")) int_in(value, 0, 0xFFFF, &s->guideNotice);
        else if (!strcmp(key, "aspect")) int_in(value, 0, PD_ASPECTS - 1, &s->aspect);
        else if (!strcmp(key, "autozoom")) {
            int on;
            if (int_in(value, 0, 1, &on)) s->autoZoom = on;
        } else if (!strcmp(key, "wildoff")) {
            int on;
            if (int_in(value, 0, 1, &on)) s->wildOff = on;
        } else if (!strcmp(key, "tabs")) {
            int bits;
            if (int_in(value, 1, (1 << PD_TAB_COUNT) - 1, &bits)) s->tabBar = bits;
        } else if (!strcmp(key, "hud")) {
            int on;
            if (int_in(value, 0, 1, &on)) s->hud = on;
        } else if (!strcmp(key, "hudhide")) {
            int bits;
            if (int_in(value, 0, (1 << PD_HUD_PARTS) - 1, &bits)) s->hudHidden = bits;
        }
    }
    fclose(f);
    return true;
}

bool pd_settings_save(const struct pd_settings* s, const char* path) {
    FILE* f = fopen(path, "w");
    if (!f) return false;
    fprintf(f, "screen=%d\nff=%d\nguide=%d\naspect=%d\nautozoom=%d\nwildoff=%d\ntabs=%d\nhud=%d\nhudhide=%d\n",
            s->screenMode, s->ffSpeed, s->guideNotice, s->aspect, s->autoZoom ? 1 : 0, s->wildOff ? 1 : 0, s->tabBar,
            s->hud ? 1 : 0, s->hudHidden);
    return fclose(f) == 0;
}
