// Gen 5's field skills (Black's FLY), the party menu's way, for pk_nds_call.cpp: the check work filled from the field
// map, the skill's own test on it, then the skill's event - none when the test says no (indoors, for FLY), so a job
// posted while the field was fine never flies from a place the game wouldn't. One call from a job's ops; its answer
// (the event, or 0) goes to GameSystem_SetEvent next.
//
// Built by scripts/gen_nds_stub.py like the hook (clang, armv5te, ARM mode, nothing to relocate): copied into the spare
// ITCM at PK_NDS_HELPER_ADDR, its arguments in a PkNdsSkill the host writes beside it.

typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;

#include "../pk_nds_call_abi.h"

typedef u32 (*Fn)(u32, u32, u32, u32);

__attribute__((section(".text.entry"))) u32 pk_nds_skill(volatile PkNdsSkill* s, u32 fieldMap)
{
    ((Fn) s->initCheck)(fieldMap, (u32) s->check, 0, 0);
    if (((Fn) s->canUse)(s->skill, (u32) s->check, 0, 0)) return 0;
    return ((Fn) s->use)(s->skill, (u32) &s->paramSlot, (u32) s->check, 0);
}
