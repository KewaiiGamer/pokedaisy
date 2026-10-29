/* Optional host scaling capability; default 3DS UI stays unchanged. */
#include "../core/pd_settings.h"
#include "../core/pd_ui_internal.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static uint32_t pixels[512*384];
static bool has(const struct pd_ui* ui,int id) {
    for(int i=0;i<ui->hitCount;i++)if(ui->hits[i].id==id)return true;
    return false;
}
static const struct pd_host_info noHost={0};
static void tap(struct pd_ui* ui,int id) {
    for(int i=0;i<ui->hitCount;i++)if(ui->hits[i].id==id) {
        struct pd_hit h=ui->hits[i];pd_ui_touch(ui,h.x+1,h.y+1,true);pd_ui_touch(ui,h.x+1,h.y+1,false);return;
    }
    assert(!"no such hit");
}
int main(void) {
    struct pd_settings settings={.ffSpeed=2};struct pd_ui ui;
    pd_ui_init(&ui,&settings);ui.tab=PD_TAB_SETTINGS;
    struct pd_snapshot snap={0};struct pd_game game={0};
    struct pd_host_info host={.hasState=true};struct pd_canvas c;
    for(int size=0;size<2;size++) {
        pd_canvas_init(&c,size?512:320,size?384:240,pixels);
        pd_ui_draw(&ui,&c,&game,&snap,NULL);assert(has(&ui,HIT_SETTING+SETTING_SCREEN));
        pd_ui_draw(&ui,&c,&game,&snap,&host);assert(has(&ui,HIT_SETTING+SETTING_SCREEN));
        host.hideScreenMode=true;pd_ui_draw(&ui,&c,&game,&snap,&host);
        assert(!has(&ui,HIT_SETTING+SETTING_SCREEN));
        for(int row=SETTING_FF;row<=SETTING_LEAVE;row++)assert(has(&ui,HIT_SETTING+row));
        for(int i=0;i<ui.hitCount;i++)if(ui.hits[i].id==HIT_SETTING+SETTING_FF) {
            struct pd_hit h=ui.hits[i];pd_ui_touch(&ui,h.x+1,h.y+1,true);pd_ui_touch(&ui,h.x+1,h.y+1,false);break;
        }
        assert(ui.overlay==PD_OVERLAY_PICK_FF);assert(pd_ui_back(&ui));host.hideScreenMode=false;
    }
    // EXPAND hosts: ASPECT opens a pick-list; AUTO ZOOM IN shows once it isn't
    // ORIGINAL and flips in place; a game EXPAND can't do shows ASPECT untappable.
    pd_canvas_init(&c,512,384,pixels);
    struct pd_host_info wide={.hideScreenMode=true,.hasExpand=true,.expandGame=true};
    pd_settings_defaults(&settings);ui.overlay=PD_OVERLAY_NONE;
    pd_ui_draw(&ui,&c,&game,&snap,NULL);assert(!has(&ui,HIT_SETTING+SETTING_ASPECT));
    pd_ui_draw(&ui,&c,&game,&snap,&wide);
    assert(has(&ui,HIT_SETTING+SETTING_ASPECT) && !has(&ui,HIT_SETTING+SETTING_AUTO_ZOOM));
    tap(&ui,HIT_SETTING+SETTING_ASPECT);assert(ui.overlay==PD_OVERLAY_PICK_ASPECT);
    pd_ui_draw(&ui,&c,&game,&snap,&wide);tap(&ui,HIT_OPTION+PD_ASPECT_ZOOM_OUT);
    assert(ui.overlay==PD_OVERLAY_NONE && settings.aspect==PD_ASPECT_ZOOM_OUT);
    assert(pd_ui_take_action(&ui)==PD_ACTION_SETTINGS_CHANGED);
    pd_ui_draw(&ui,&c,&game,&snap,&wide);assert(has(&ui,HIT_SETTING+SETTING_AUTO_ZOOM) && settings.autoZoom);
    tap(&ui,HIT_SETTING+SETTING_AUTO_ZOOM);assert(!settings.autoZoom);
    assert(pd_ui_take_action(&ui)==PD_ACTION_SETTINGS_CHANGED);
    wide.expandGame=false;pd_ui_draw(&ui,&c,&game,&snap,&wide);
    assert(!has(&ui,HIT_SETTING+SETTING_ASPECT) && !has(&ui,HIT_SETTING+SETTING_AUTO_ZOOM));
    // Saved and read back; a file from before (no aspect / autozoom keys) keeps the defaults.
    const char* path="/tmp/pd-host-display-settings.ini";
    assert(pd_settings_save(&settings,path));
    struct pd_settings back;assert(pd_settings_load(&back,path));
    assert(back.aspect==PD_ASPECT_ZOOM_OUT && !back.autoZoom && back.ffSpeed==settings.ffSpeed);
    FILE* old=fopen(path,"w");fputs("screen=1\nff=3\nguide=0\n",old);fclose(old);
    assert(pd_settings_load(&back,path) && back.aspect==PD_ASPECT_ORIGINAL && back.autoZoom && back.ffSpeed==3);
    remove(path);
    // PARTY at a Linux canvas size: Android's slot, edge to edge, three columns past 2:1 (426x240's tab).
    // The selected look (orange outline) follows the host's D-pad focus, else slot 0; empty slots are
    // faint and take no taps.
    static uint32_t partyPx[426*240];
    pd_canvas_init(&c,426,240,partyPx);
    struct pd_snapshot party={.valid=true,.partyCount=3};
    for(int i=0;i<3;i++){party.party[i].species=1+i;party.party[i].level=5;party.party[i].hp=10;party.party[i].maxHp=20;}
    // A FireRed rev 1 header: a supported game (no ROM bytes, so no icons).
    uint8_t header[0xC0]={0};memcpy(header+0xAC,"BPRE",4);header[0xBC]=1;
    struct pd_game fr;pd_game_detect(&fr,header,0x1000000);assert(fr.cfg);
    ui.tab=PD_TAB_PARTY;ui.summarySlot=-1;ui.focusId=-1;
    pd_ui_draw(&ui,&c,&fr,&party,&noHost);
    struct pd_hit cell[3];int slots=0;
    for(int i=0;i<ui.hitCount;i++)if(ui.hits[i].id>=HIT_SLOT&&ui.hits[i].id<HIT_SLOT+3)cell[ui.hits[i].id-HIT_SLOT]=ui.hits[i],slots++;
    assert(slots==3 && cell[1].y==cell[0].y && cell[2].y==cell[0].y && cell[1].x>cell[0].x && cell[2].x>cell[1].x);
    assert(cell[2].x+cell[2].w-cell[0].x>426*9/10); // edge to edge (less the Poke Ball overhang)
    // Slot i's frame top edge (a slot's hit is its frame).
    #define FRAME_TOP(i) partyPx[cell[i].y*426+cell[i].x+20]
    assert(FRAME_TOP(0)==0xFF7331 && FRAME_TOP(2)==0x4A4A63);
    ui.focusId=HIT_SLOT+2;pd_ui_draw(&ui,&c,&fr,&party,&noHost);
    assert(FRAME_TOP(0)==0x4A4A63 && FRAME_TOP(2)==0xFF7331);
    // The empty row's outlines are blended into the backdrop, never drawn solid.
    int solid=0;
    for(int y=cell[0].y+cell[0].h;y<240;y++)for(int x=0;x<426;x++)solid+=partyPx[y*426+x]==0x4A4A63;
    assert(solid==0);
    puts("PASS default/null host, optional scaling row, remaining settings and FF interaction, EXPAND rows, party grid");
}
