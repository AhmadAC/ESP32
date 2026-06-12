#include "key.h"

volatile uint16_t Trg = 0, Cont = 0, Release = 0;

void Key_Init(void) {
	GPIO_InitTypeDef GPIO_InitStructure;
	GPIO_InitStructure.GPIO_Pin = GPIO_Pin_0 | GPIO_Pin_1;
	GPIO_InitStructure.GPIO_Mode = GPIO_Mode_IPU;
	GPIO_InitStructure.GPIO_Speed = GPIO_Speed_50MHz;
	GPIO_Init(GPIOB, &GPIO_InitStructure);
}

void Key_Read(void) {
	uint16_t data = GPIO_ReadInputData(GPIOB) ^ 0xffff;
	Trg = data & (data ^ Cont);
	Release = data ^ Trg ^ Cont;
	Cont = data;
}

KeyType Key_Scan(KeyStruct *p) {
	KeyType type = KEY_IDLE;
	switch (p->state) {
		case KEY_IDLE_STATE:
			if (p->code & Trg) {
				type = KEY_DOWN;
				p->state = KEY_PRESS_STATE;
				p->count = 0;
			}
			break;
		case KEY_PRESS_STATE:
			if (p->code & Cont) {
				p->count++;
				if (p->count >= LONG_CLICK_PERIOD) {
					type = KEY_LONG_CLICK;
					p->state = KEY_LONG_STATE;
					p->count = 0;
				}
			}
			else {
				type = KEY_UP;
				if (p->count < SHORT_CLICK_PERIOD) {
					p->state = KEY_IDLE_STATE;
				}
				else {	//判断到底是单击还是双击
					p->state = KEY_RELEASE_STATE;
					p->count = 0;
				}
			}
			break;
		case KEY_RELEASE_STATE:
			if (p->code & Trg) {
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
			if (p->code & Release) {
				type = KEY_UP;
				p->state = KEY_IDLE_STATE;
			}
			break;
		case KEY_LONG_STATE:
			if (p->code & Release) {
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

void Key_Test(char *name, KeyStruct *p) {
	switch (Key_Scan(p)) {
		case KEY_IDLE:
			break;
		case KEY_SHORT_CLICK:
			printf("%s short click\r\n", name);
			break;
		case KEY_DOUBLE_CLICK:
			printf("%s double click\r\n", name);
			break;
		case KEY_LONG_CLICK:
			printf("%s long click\r\n", name);
			break;
		case KEY_LONG_ACUM:
			printf("%s long accumulation\r\n", name);
			break;
		default:
			break;
	}
}

