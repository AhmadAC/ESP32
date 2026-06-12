#include "led.h"

sbit LED0 = P2^0;

//LEDÁÁÃð¿ØÖÆ
void LED_OnOff(uint8_t status)
{
	LED0 = status ? 0 : 1;
}

//LEDµÆ×´Ì¬·­×ª
void LED_Toggle(void)
{
	LED0 = !LED0;
}