#include "delay.h"

static volatile uint32_t count = 0;

//定时器初始化
void Timer0_Init(void)
{
	TMOD &= 0xF0;
	TMOD |= 0x01;
	TL0 = T1MS;
    TH0 = T1MS >> 8;
	ET0 = 1;
	TR0 = 0;
}

//延时N毫秒
void delay_ms(uint32_t ms) 
{
	count = ms;
	TR0 = 1;
	while (count);
	TR0 = 0;
}

void tm0_isr() interrupt 1
{
	TL0 = T1MS;
	TH0 = T1MS >> 8;
	count--;
}