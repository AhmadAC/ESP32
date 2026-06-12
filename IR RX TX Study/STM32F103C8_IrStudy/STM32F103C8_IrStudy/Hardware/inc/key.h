#ifndef __KEY_H
#define __KEY_H
#include "type.h"

#define K1	0x0002
#define K2	0x0001

#define SHORT_CLICK_PERIOD		2
#define DOUBLE_CLICK_PERIOD		25
#define LONG_CLICK_PERIOD		200
#define LONG_AUTO_PERIOD		5

typedef enum {
	KEY_IDLE = 0,
	KEY_DOWN,
	KEY_UP,
	KEY_SHORT_CLICK,
	KEY_DOUBLE_CLICK,
	KEY_LONG_CLICK,
	KEY_LONG_ACUM
} KeyType;

typedef enum {
	KEY_IDLE_STATE = 0,
	KEY_PRESS_STATE,
	KEY_RELEASE_STATE,
	KEY_DOUBLE_STATE,
	KEY_LONG_STATE
} KeyState;

typedef struct {
	KeyState state;
	uint8_t count;
	uint16_t code;
} KeyStruct;

void Key_Init(void);
void Key_Read(void);
KeyType Key_Scan(KeyStruct *p);
void Key_Test(char *name, KeyStruct *p);
#endif
