#include "key.h"

volatile uint8_t Trg = 0, Cont = 0, Release = 0;

void Key_Read(void) {
	uint8_t dat = P3 ^ 0xff;
	Trg = dat & (dat ^ Cont);
	Release = dat ^ Trg ^ Cont;
	Cont = dat;
}

KeyType Key_Scan(KeyStruct *p) {
	KeyType type = 0;
	switch (p->state) {
		case KEY_IDLE_STATE:
			if (p->key & Trg) {
				type = KEY_DOWN;
				p->state = KEY_PRESS_STATE;
				p->count = 0;
			}
			break;
		case KEY_PRESS_STATE:
			if (p->key & Cont) {
				p->count++;
				if (p->count >= LONG_CLICK_PERIOD) {
					type = KEY_LONG_CLICK;
					p->state = KEY_LONG_STATE;
					p->count = 0;
				}
			}
			else {
				if (p->count < SHORT_CLICK_PERIOD) {
					type = KEY_UP;
					p->state = KEY_IDLE_STATE;
				}
				else {
					p->state = KEY_RELEASE_STATE;
					p->count = 0;
				}
			}
			break;
		case KEY_RELEASE_STATE:
			if (p->key & Trg) {
				type = KEY_DOUBLE_CLICK;
				p->state = KEY_DOUBLE_STATE;
			}
			else {
				p->count++;
				if (p->count > DOUBLE_CLICK_PERIOD) {
					type = KEY_SHORT_CLICK;
					p->state = KEY_IDLE_STATE;
				}
			}
			break;
		case KEY_DOUBLE_STATE:
			if (p->key & Release) {
				type = KEY_UP;
				p->state = KEY_IDLE_STATE;
			}
			break;
		case KEY_LONG_STATE:
			if (p->key & Release) {
				type = KEY_UP;
				p->state = KEY_IDLE_STATE;
			}
			else {
				p->count++;
				if (p->count >= LONG_AUTO_PERIOD) {
					p->count = 0;
					type = KEY_LONG_ACUM;
				}
			}
			break;
	}
	return type;
}
