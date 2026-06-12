#ifndef __DELAY_H
#define __DELAY_H
#include "type.h"
#define T1MS 	(65536 - FOSC / 12 / 1000)

void Timer0_Init(void);
void delay_ms(uint32_t ms);
#endif
